package ayla.calendar;

import static ayla.calendar.CalendarService.*;
import java.time.*;
import java.util.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.security.SecureRandom;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/** PostgreSQL outbox + bounded rolling Google snapshot. No credentials leave the server. */
@Service
public class GoogleCalendarSync {
    private final JdbcTemplate jdbc;private final CalendarService calendar;private final ayla.cluster.ClusterOperations cluster;
    private final TransactionTemplate tx;private final String clientId,clientSecret,redirect,frontendUrl;private final byte[] encryptionKey;
    private final JsonMapper json=JsonMapper.builder().build();private final SecureRandom random=new SecureRandom();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final String API="https://www.googleapis.com/calendar/v3";
    public static class GoogleFailure extends RuntimeException {final int status;public GoogleFailure(int status){super("Google Calendar unavailable");this.status=status;}}
    public GoogleCalendarSync(JdbcTemplate jdbc,@Lazy CalendarService calendar,ayla.cluster.ClusterOperations cluster,PlatformTransactionManager manager,
            @Value("${ayla.calendar.google.client-id:}") String clientId,@Value("${ayla.calendar.google.client-secret:}") String clientSecret,
            @Value("${ayla.calendar.google.redirect-uri:http://localhost:8080/api/v1/calendar/google/callback}") String redirect,
            @Value("${ayla.calendar.encryption-key:}") String encryptionKey,
            @Value("${ayla.frontend.url:http://localhost:3000}") String frontendUrl){
        this.jdbc=jdbc;this.calendar=calendar;this.cluster=cluster;tx=new TransactionTemplate(manager);this.clientId=clientId;this.clientSecret=clientSecret;this.redirect=redirect;this.frontendUrl=frontendUrl;
        byte[] decoded;try{decoded=Base64.getDecoder().decode(encryptionKey);}catch(Exception e){decoded=new byte[0];}this.encryptionKey=decoded;
    }
    boolean configured(){return !clientId.isBlank()&&!clientSecret.isBlank()&&encryptionKey.length==32;}
    public Map<String,Object> status(String user){
        var rows=jdbc.queryForList("select label,refresh_secret<>' ' and refresh_secret<>'' as connected,last_sync_at,sync_status from calendar_google_connections where user_id=? and refresh_secret<>''",user);
        var value=new LinkedHashMap<String,Object>();value.put("configured",configured());value.put("connected",false);value.put("connections",rows.size());value.put("syncWindow","Google: últimos 30 dias e próximos 24 meses; eventos locais são enviados independentemente desse período.");
        if(!rows.isEmpty()){value.put("connected",true);value.putAll(rows.getFirst());Object instant=value.remove("last_sync_at");value.put("lastSyncAt",instant);value.put("syncStatus",value.remove("sync_status"));}
        return value;
    }
    public String connect(String user){
        if(!configured())throw error(503,"A agenda própria está pronta. Configure a conexão Google no servidor para autorizar sua conta.");
        byte[] bytes=new byte[32];random.nextBytes(bytes);String state=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("delete from calendar_oauth_states where expires_at<now() or user_id=?",user);
        jdbc.update("insert into calendar_oauth_states(state_hash,user_id) values (?,?)",hash(state),user);
        return "https://accounts.google.com/o/oauth2/v2/auth?"+form(Map.of("client_id",clientId,"redirect_uri",redirect,"response_type","code","scope","https://www.googleapis.com/auth/calendar.events https://www.googleapis.com/auth/calendar.readonly","access_type","offline","prompt","consent","state",state));
    }
    public void callback(String state,String code){
        if(!configured()||state==null||code==null||state.length()>200||code.length()>4096)throw error(400,"Autorização inválida.");
        String user=tx.execute(status->{var rows=jdbc.queryForList("delete from calendar_oauth_states where state_hash=? and expires_at>now() returning user_id",String.class,hash(state));if(rows.size()!=1)throw error(400,"Autorização expirada. Conecte novamente.");return rows.getFirst();});
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select enabled from auth_accounts where user_id=?",Boolean.class,user)))throw error(403,"Conta inativa.");
        Map<String,Object> tokens;
        try{tokens=token(Map.of("client_id",clientId,"client_secret",clientSecret,"redirect_uri",redirect,"grant_type","authorization_code","code",code));}catch(RuntimeException e){throw error(503,"Não foi possível concluir a autorização Google. Tente conectar novamente.");}
        String refresh=string(tokens,"refresh_token");if(refresh.isBlank())throw error(409,"Autorize o acesso novamente para permitir sincronização em segundo plano.");
        var meta=api("GET","/calendars/primary",string(tokens,"access_token"),null,null);
        String calendarId=string(meta,"id"),label=string(meta,"summary");if(calendarId.isBlank())throw error(503,"Não consegui identificar a agenda Google.");
        cluster.exclusive("google-calendar:"+user,()->tx.execute(status->{
            var existing=jdbc.queryForList("select connection_id from calendar_google_connections where user_id=? and calendar_id=? for update",user,calendarId);
            String connectionId=existing.isEmpty()?UUID.randomUUID().toString():existing.getFirst().get("connection_id").toString();
            if(!existing.isEmpty())jdbc.update("update calendar_events set google_id=null,google_etag=null,google_dirty=true where user_id=?",user);
            jdbc.update("""
                insert into calendar_google_connections(user_id,refresh_secret,calendar_id,label,connection_id,can_schedule) values (?,?,?,?,?,false)
                on conflict(user_id,calendar_id) do update set refresh_secret=excluded.refresh_secret,label=excluded.label,
                next_sync_at=now(),sync_status='PENDING',connected_at=now()
                """,user,seal(user,refresh),calendarId,label.substring(0,Math.min(300,label.length())),connectionId);return true;
        }));
    }
    public String returnUrl(boolean success) {
        return frontendUrl.replaceAll("/+$", "") + "/agenda?google="
            + (success ? "connected" : "error");
    }
    public void disconnect(String user){cluster.exclusive("google-calendar:"+user,()->{jdbc.update("update calendar_google_connections set refresh_secret='',sync_status='DISCONNECTED' where user_id=?",user);jdbc.update("delete from calendar_oauth_states where user_id=?",user);return true;});}
    public List<Map<String,Object>> listConnections(String user){
        var rows=jdbc.queryForList("""
            select connection_id,label,calendar_id,can_schedule,last_sync_at,sync_status,
                   refresh_secret<>'' as connected
            from calendar_google_connections 
            where user_id=? and refresh_secret<>''
            order by connected_at desc
            """,user);
        var result=new ArrayList<Map<String,Object>>();
        for(var row:rows){
            var item=new LinkedHashMap<String,Object>();
            item.put("connectionId",String.valueOf(row.get("connection_id")));
            item.put("label",row.get("label"));
            item.put("calendarId",row.get("calendar_id"));
            item.put("canSchedule",row.get("can_schedule"));
            item.put("connected",row.get("connected"));
            item.put("lastSyncAt",row.get("last_sync_at"));
            item.put("syncStatus",row.get("sync_status"));
            result.add(item);
        }
        return result;
    }
    public void updateCanSchedule(String user,String connectionId,boolean canSchedule){
        int updated=jdbc.update("update calendar_google_connections set can_schedule=? where user_id=? and connection_id=?::uuid",canSchedule,user,connectionId);
        if(updated==0)throw error(404,"Conexão não encontrada.");
    }
    public void disconnectConnection(String user,String connectionId){
        cluster.exclusive("google-calendar:"+user,()->{
            jdbc.update("update calendar_google_connections set refresh_secret='',sync_status='DISCONNECTED' where user_id=? and connection_id=?::uuid",user,connectionId);
            return true;
        });
    }
    public void requestSync(String user){if(jdbc.update("update calendar_google_connections set next_sync_at=now(),sync_status='PENDING' where user_id=? and refresh_secret<>''",user)==0)throw error(409,"Conecte o Google primeiro. Sua agenda própria continua disponível.");}
    /** Agenda uma atualização para todas as contas conectadas. */
    public void queueAutomaticSync(){
        if(!configured())return;
        jdbc.update("""
            update calendar_google_connections c
            set next_sync_at=now(),sync_status='PENDING'
            from auth_accounts a
            where a.user_id=c.user_id and a.enabled and c.refresh_secret<>''
            """);
    }
    public void scheduled(){
        if(!configured())return;
        var users=jdbc.queryForList("select c.user_id from calendar_google_connections c join auth_accounts a on a.user_id=c.user_id where a.enabled and c.refresh_secret<>'' and c.next_sync_at<=now() order by c.next_sync_at limit 5",String.class);
        for(String user:users)try{cluster.exclusive("google-calendar:"+user,()->{sync(user);return true;});}catch(RuntimeException ignored){}
        jdbc.update("delete from calendar_oauth_states where expires_at<now()");
    }
    public void sync(String user){
        var connections=jdbc.queryForList("select * from calendar_google_connections where user_id=? and refresh_secret<>'' and next_sync_at<=now()",user);if(connections.isEmpty())return;
        try{
            boolean pushed=false;
            for(var connection:connections){
                Boolean canSchedule=(Boolean)connection.get("can_schedule");boolean writable=!Boolean.FALSE.equals(canSchedule)&&!pushed;
                jdbc.update("update calendar_google_connections set next_sync_at=now()+interval '10 minutes',sync_status='SYNCING' where user_id=? and calendar_id=?",user,connection.get("calendar_id"));
                var token=token(Map.of("client_id",clientId,"client_secret",clientSecret,"grant_type","refresh_token","refresh_token",open(user,(String)connection.get("refresh_secret"))));String access=string(token,"access_token");
                String base="/calendars/"+encode((String)connection.get("calendar_id"))+"/events";
                if(writable){
                    var dirty=jdbc.queryForList("select * from calendar_events where user_id=? and google_dirty order by updated_at limit 100",user);
                    for(var event:dirty)push(user,base,access,event);
                    pushed=true;
                }
                Instant from=Instant.now().minus(Duration.ofDays(30)),to=Instant.now().plus(Duration.ofDays(730));
                String page="";Set<String> seen=new HashSet<>();boolean complete=false;
                for(int count=0;count<20;count++){
                    String query="?singleEvents=true&showDeleted=true&maxResults=2500&timeMin="+encode(from.toString())+"&timeMax="+encode(to.toString())+(page.isBlank()?"":"&pageToken="+encode(page));
                    var result=api("GET",base+query,access,null,null);
                    var defaults=reminders(result.get("defaultReminders"));
                    for(var remote:maps(result.get("items"))){String id=string(remote,"id");seen.add(id);importEvent(user,remote,defaults);}
                    page=string(result,"nextPageToken");if(page.isBlank()){complete=true;break;}
                }
                if(!complete)throw new GoogleFailure(429);
                var linked=jdbc.queryForList("select google_id from calendar_events where user_id=? and status='ACTIVE' and not google_dirty and google_id is not null and starts_at>=? and starts_at<?",String.class,user,Timestamp.from(from),Timestamp.from(to));
                for(String id:linked)if(!seen.contains(id)){try{importEvent(user,api("GET",base+"/"+encode(id),access,null,null),List.of());}catch(GoogleFailure failure){if(failure.status==404||failure.status==410)remoteCancel(user,id);else throw failure;}}
                jdbc.update("update calendar_google_connections set last_sync_at=now(),sync_status=case when sync_status='PENDING' or exists(select 1 from calendar_events e where e.user_id=? and e.google_dirty) then 'PENDING' else 'OK' end,next_sync_at=case when sync_status='PENDING' or exists(select 1 from calendar_events e where e.user_id=? and e.google_dirty) then now() else 'infinity'::timestamptz end where user_id=? and calendar_id=?",user,user,user,connection.get("calendar_id"));
            }
        }catch(RuntimeException failure){
            String status=failure instanceof GoogleFailure g&&(g.status==400||g.status==401)?"RECONNECT":"RETRYING";
            jdbc.update("""
                update calendar_google_connections
                set sync_status=case when sync_status='PENDING' then 'PENDING' else ? end,
                    next_sync_at=case when sync_status='PENDING' then now()
                        when ?='RECONNECT' then 'infinity'::timestamptz
                        else now()+interval '5 minutes' end
                where user_id=? and refresh_secret<>''
                """,status,status,user);
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Sincronização Google pendente. tipo={}",failure.getClass().getSimpleName());
        }
    }
    private void push(String user,String base,String access,Map<String,Object> row){
        UUID id=(UUID)row.get("id");String remoteId=(String)row.get("google_id");long version=((Number)row.get("version")).longValue();
        if("CANCELLED".equals(row.get("status"))){if(remoteId!=null)try{api("DELETE",base+"/"+encode(remoteId),access,null,(String)row.get("google_etag"));}catch(GoogleFailure failure){if(failure.status==412){importEvent(user,api("GET",base+"/"+encode(remoteId),access,null,null),List.of());return;}if(failure.status!=404&&failure.status!=410)throw failure;}jdbc.update("update calendar_events set google_dirty=false where user_id=? and id=? and version=?",user,id,version);return;}
        var body=new LinkedHashMap<String,Object>();body.put("summary",row.get("title"));body.put("description",row.get("description"));body.put("location",row.get("location"));
        var zone=ZoneId.of((String)row.get("time_zone"));var start=((Timestamp)row.get("starts_at")).toInstant();var end=((Timestamp)row.get("ends_at")).toInstant();
        boolean allDay=Boolean.TRUE.equals(row.get("all_day"));
        body.put("start",allDay?Map.of("date",start.atZone(zone).toLocalDate().toString()):Map.of("dateTime",start.toString(),"timeZone",zone.getId()));
        body.put("end",allDay?Map.of("date",end.atZone(zone).toLocalDate().toString()):Map.of("dateTime",end.toString(),"timeZone",zone.getId()));
        var event=calendar.get(user,id);body.put("reminders",Map.of("useDefault",false,"overrides",event.reminderMinutes().stream().map(m->Map.of("method","popup","minutes",m)).toList()));
        body.put("extendedProperties",Map.of("private",Map.of("aylaId",id.toString())));
        String deterministic="a"+id.toString().replace("-","");Map<String,Object> remote;
        try{
            if(remoteId==null){body.put("id",deterministic);remote=api("POST",base,access,body,null);}
            else remote=api("PATCH",base+"/"+encode(remoteId),access,body,(String)row.get("google_etag"));
        }catch(GoogleFailure failure){
            if(remoteId==null&&failure.status==409){remote=api("GET",base+"/"+deterministic,access,null,null);if(!id.toString().equals(string(map(map(remote.get("extendedProperties")).get("private")),"aylaId")))throw new GoogleFailure(409);jdbc.update("update calendar_events set google_id=?,google_etag=?,google_dirty=true where user_id=? and id=?",string(remote,"id"),string(remote,"etag"),user,id);return;}
            else if(failure.status==412){var changed=api("GET",base+"/"+encode(remoteId),access,null,null);Instant remoteUpdated=instant(changed.get("updated"));if(remoteUpdated.isAfter(((Timestamp)row.get("updated_at")).toInstant()))importEvent(user,changed,List.of());else jdbc.update("update calendar_events set google_etag=? where user_id=? and id=?",string(changed,"etag"),user,id);return;}
            else if(remoteId!=null&&(failure.status==404||failure.status==410)){remoteCancel(user,remoteId);return;}
            else throw failure;
        }
        jdbc.update("update calendar_events set google_id=?,google_etag=?,google_dirty=case when version=? then false else google_dirty end where user_id=? and id=?",string(remote,"id"),string(remote,"etag"),version,user,id);
    }
    private void importEvent(String user,Map<String,Object> remote,List<Integer> defaults){
        String googleId=string(remote,"id");if(googleId.isBlank())return;
        if("cancelled".equals(remote.get("status"))){remoteCancel(user,googleId);return;}
        var startData=map(remote.get("start"));var endData=map(remote.get("end"));if(startData.isEmpty()||endData.isEmpty())return;
        String zone=string(startData,"timeZone");try{ZoneId.of(zone);}catch(Exception ignored){zone="America/Sao_Paulo";}
        boolean allDay=startData.containsKey("date");Instant start=date(startData,zone),end=date(endData,zone);if(!end.isAfter(start))return;
        var remindersData=map(remote.get("reminders"));var minutes=Boolean.TRUE.equals(remindersData.get("useDefault"))?defaults:reminders(remindersData.get("overrides"));
        String finalZone=zone;
        tx.execute(status->{
            jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"calendar-schedule:"+user);
            var local=jdbc.queryForList("select * from calendar_events where user_id=? and google_id=? for update",user,googleId);
            if(local.isEmpty()){
                String linked=string(map(map(remote.get("extendedProperties")).get("private")),"aylaId");
                try{UUID known=UUID.fromString(linked);local=jdbc.queryForList("select * from calendar_events where user_id=? and id=? and google_id is null for update",user,known);}catch(IllegalArgumentException ignored){}
            }
            UUID id=local.isEmpty()?UUID.randomUUID():(UUID)local.getFirst().get("id");String etag=string(remote,"etag");
            if(!local.isEmpty()){
                var previous=local.getFirst();if(etag.equals(previous.get("google_etag")))return true;
                if(Boolean.TRUE.equals(previous.get("google_dirty"))&&!instant(remote.get("updated")).isAfter(((Timestamp)previous.get("updated_at")).toInstant()))return true;
            }
            String title=string(remote,"summary");if(title.isBlank())title="Compromisso";
            if(local.isEmpty())jdbc.update("insert into calendar_events(id,user_id,title,description,location,starts_at,ends_at,time_zone,all_day,reminder_minutes,google_id,google_etag,google_dirty,source) values (?,?,?,?,?,?,?,?,?,?::integer[],?,?,false,'GOOGLE')",id,user,clip(title,200),clip(string(remote,"description"),4000),clip(string(remote,"location"),500),Timestamp.from(start),Timestamp.from(end),finalZone,allDay,array(minutes),googleId,etag);
            else jdbc.update("update calendar_events set title=?,description=?,location=?,starts_at=?,ends_at=?,time_zone=?,all_day=?,reminder_minutes=?::integer[],google_id=?,google_etag=?,google_dirty=false,status='ACTIVE',version=version+1,updated_at=now() where user_id=? and id=?",clip(title,200),clip(string(remote,"description"),4000),clip(string(remote,"location"),500),Timestamp.from(start),Timestamp.from(end),finalZone,allDay,array(minutes),googleId,etag,user,id);
            var saved=calendar.get(user,id);
            if(!local.isEmpty())jdbc.update("update calendar_reminders set event_version=? where event_id=? and event_version=? and not cancelled and reminder_type='AT_TIME'",saved.version(),id,local.getFirst().get("version"));
            if(!local.isEmpty()){
                var old=local.getFirst();
                if(((Timestamp)old.get("starts_at")).toInstant().equals(saved.startsAt()))
                    jdbc.update("update calendar_reminders set event_version=? where event_id=? and event_version=? and not cancelled and reminder_type='BEFORE_START' and minutes_before=any(?::integer[])",saved.version(),id,old.get("version"),array(saved.reminderMinutes()));
            }
            calendar.schedule(saved,true);return true;
        });
    }
    private void remoteCancel(String user,String googleId){tx.execute(status->{var ids=jdbc.queryForList("update calendar_events set status='CANCELLED',google_dirty=false,version=version+1,updated_at=now() where user_id=? and google_id=? and status<>'CANCELLED' returning id",UUID.class,user,googleId);for(var id:ids)jdbc.update("update calendar_reminders set cancelled=true where event_id=?",id);return true;});}
    private Map<String,Object> api(String method,String path,String access,Object body,String etag){return send(method,API+path,access,body==null?null:json.writeValueAsString(body),"application/json",etag);}
    private Map<String,Object> token(Map<String,String> fields){return send("POST","https://oauth2.googleapis.com/token",null,form(fields),"application/x-www-form-urlencoded",null);}
    @SuppressWarnings("unchecked") private Map<String,Object> send(String method,String url,String access,String body,String type,String etag){
        try{
            var builder=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).header("Accept","application/json");
            if(access!=null)builder.header("Authorization","Bearer "+access);if(etag!=null&&!etag.isBlank())builder.header("If-Match",etag);
            if(body!=null)builder.header("Content-Type",type);
            var response=http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()<200||response.statusCode()>=300)throw new GoogleFailure(response.statusCode());
            return response.body().isBlank()?Map.of():(Map<String,Object>)json.readValue(response.body(),Map.class);
        }catch(GoogleFailure failure){throw failure;}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new GoogleFailure(503);}catch(Exception failure){throw new GoogleFailure(503);}
    }
    private String seal(String user,String secret){try{byte[] iv=new byte[12];random.nextBytes(iv);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(encryptionKey,"AES"),new GCMParameterSpec(128,iv));cipher.updateAAD(user.getBytes(StandardCharsets.UTF_8));byte[] encrypted=cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));byte[] packed=new byte[iv.length+encrypted.length];System.arraycopy(iv,0,packed,0,iv.length);System.arraycopy(encrypted,0,packed,iv.length,encrypted.length);return Base64.getEncoder().encodeToString(packed);}catch(Exception e){throw error(503,"Criptografia da conexão Google indisponível.");}}
    private String open(String user,String secret){try{byte[] packed=Base64.getDecoder().decode(secret);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(encryptionKey,"AES"),new GCMParameterSpec(128,Arrays.copyOfRange(packed,0,12)));cipher.updateAAD(user.getBytes(StandardCharsets.UTF_8));return new String(cipher.doFinal(Arrays.copyOfRange(packed,12,packed.length)),StandardCharsets.UTF_8);}catch(Exception e){throw error(503,"Reconecte sua agenda Google.");}}
    private static String encode(String text){return URLEncoder.encode(text,StandardCharsets.UTF_8).replace("+","%20");}
    private static String form(Map<String,String> fields){return String.join("&",fields.entrySet().stream().map(e->encode(e.getKey())+"="+encode(e.getValue())).toList());}
    private static String string(Map<String,Object> value,String key){return value.get(key) instanceof String s?s:"";}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return value instanceof Map<?,?>?(Map<String,Object>)value:Map.of();}
    private static List<Map<String,Object>> maps(Object value){if(!(value instanceof List<?> values))return List.of();return values.stream().filter(v->v instanceof Map<?,?>).map(GoogleCalendarSync::map).toList();}
    private static List<Integer> reminders(Object value){return maps(value).stream().map(v->v.get("minutes")).filter(v->v instanceof Number).map(v->((Number)v).intValue()).filter(v->v>=0&&v<=40320).distinct().sorted().limit(5).toList();}
    private static Instant instant(Object value){try{return Instant.parse(String.valueOf(value));}catch(Exception ignored){return Instant.EPOCH;}}
    private static Instant date(Map<String,Object> value,String zone){return value.containsKey("date")?LocalDate.parse(string(value,"date")).atStartOfDay(ZoneId.of(zone)).toInstant():OffsetDateTime.parse(string(value,"dateTime")).toInstant();}
    private static String clip(String text,int max){return text.substring(0,Math.min(max,text.length()));}
}
