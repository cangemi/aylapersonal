package ayla.hermes;

import static ayla.hermes.ProviderUsageData.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Shared availability only; local usage/history always remains user scoped. */
@Service
public class ModelAvailabilityService {
    private final JdbcTemplate jdbc;
    private final JsonMapper json=JsonMapper.builder().findAndAddModules().build();
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public ModelAvailabilityService(JdbcTemplate jdbc){this(jdbc,Clock.systemUTC());}
    ModelAvailabilityService(JdbcTemplate jdbc,Clock clock){this.jdbc=jdbc;this.clock=clock;}
    static String identifier(String value){return value!=null&&value.matches("[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,254}")?value:null;}
    static Instant time(Object value){return value instanceof Timestamp stamp?stamp.toInstant():value instanceof Instant instant?instant:null;}

    @Transactional
    public ProviderUsageData.Limited rejected(String profile,String selected,String raw,String retryHeader,String session){
        var now=clock.instant();var parsed=ProviderLimitParser.parse(raw,retryHeader,now);
        String model=parsed.model()!=null?parsed.model():identifier(selected);
        String state=parsed.providerConfirmed()?"RATE_LIMITED":"WAITING_PROVIDER";
        var meta=publicNotice(new Availability("nous",model,state,now,parsed.seconds(),parsed.seconds()==null?null:now.plusSeconds(parsed.seconds()),null,parsed.alternates(),null));
        // Only a previously authenticated telemetry binding can establish account scope.
        var rows=jdbc.queryForList("select user_id,credential_scope,credential_observed_at,last_model from provider_connections where profile=? and provider='nous'",profile);
        if(rows.size()==1){var row=rows.getFirst();if(model==null)model=identifier(Objects.toString(row.get("last_model"),null));
            if(model!=null){meta.put("model",model);observe((String)row.get("user_id"),new Event(UUID.randomUUID().toString(),profile,(String)row.get("credential_scope"),"nous",model,state,now,time(row.get("credential_observed_at")),parsed.seconds(),null,parsed.alternates(),null,null,session));}
        }
        return new Limited(notice(meta),meta);
    }

    @Transactional
    public void observe(String user,Event event){
        Instant now=clock.instant();
        if(event.detectedAt().isAfter(now.plusSeconds(300))||event.detectedAt().isBefore(now.minus(Duration.ofDays(2)))
            ||event.credentialObservedAt().isAfter(event.detectedAt().plusSeconds(5)))throw new HermesException(400,"Data de observação inválida.");
        if(jdbc.update("insert into provider_telemetry_receipts(event_id) values (?) on conflict do nothing",event.eventId())==0)return;
        if(event.metrics()!=null&&!event.metrics().isEmpty()){
            // Attribute only to this profile's owned session/job. Shared credentials do not share usage.
            var jobs=event.sessionId()==null?List.<Map<String,Object>>of():jdbc.queryForList("""
                select j.id,j.source from chat_jobs j join chat_conversations c on c.id=j.conversation_id
                where j.user_id=? and c.user_id=? and c.hermes_session_id=? and j.started_at<=?
                  and (j.finished_at is null or j.finished_at>=? or (j.status='NEEDS_REVIEW' and j.review_released_at is null))
                order by j.sequence desc limit 1
                """,user,user,event.sessionId(),Timestamp.from(event.detectedAt()),Timestamp.from(event.detectedAt()));
            var job=jobs.isEmpty()?Map.<String,Object>of():jobs.getFirst();
            jdbc.update("""
                insert into provider_inference_events(id,user_id,profile,provider,model,session_id,request_id,source,kind,occurred_at,metrics_json)
                values (?,?,?,?,?,?,?,?,?,?,?) on conflict do nothing
                """,event.eventId(),user,event.profile(),event.provider(),identifier(event.model()),event.sessionId(),
                job.get("id")==null?null:job.get("id").toString(),Objects.toString(job.get("source"),"HERMES"),event.state(),Timestamp.from(event.detectedAt()),json.writeValueAsString(event.metrics()));
        }
        if("TOOL".equals(event.state()))return;
        String model=identifier(event.model());
        jdbc.update("""
            insert into provider_connections(user_id,provider,profile,credential_scope,credential_observed_at,last_model) values (?,?,?,?,?,?)
            on conflict(user_id,provider) do update set profile=excluded.profile,credential_scope=excluded.credential_scope,
            last_model=case when provider_connections.credential_scope=excluded.credential_scope then coalesce(excluded.last_model,provider_connections.last_model) else excluded.last_model end,
            credential_observed_at=excluded.credential_observed_at
            where excluded.credential_observed_at>=provider_connections.credential_observed_at
            """,user,event.provider(),event.profile(),event.credentialScope(),Timestamp.from(event.credentialObservedAt()),model);
        if("QUOTA".equals(event.state())){
            var quotas=validQuotas(event.quotas());
            jdbc.update("""
                insert into provider_quota_snapshots(provider,credential_scope,observed_at,status,quotas_json) values (?,?,?,?,?)
                on conflict(provider,credential_scope) do update set observed_at=excluded.observed_at,status=excluded.status,quotas_json=excluded.quotas_json
                where excluded.observed_at>=provider_quota_snapshots.observed_at
                """,event.provider(),event.credentialScope(),Timestamp.from(event.detectedAt()),
                "ERROR".equals(event.quotaStatus())?"ERROR":quotas.isEmpty()?"UNAVAILABLE":"KNOWN",json.writeValueAsString(quotas));
            return;
        }
        if(model==null)return;
        jdbc.update("insert into provider_observed_models(user_id,provider,model) values (?,?,?) on conflict do nothing",user,event.provider(),model);
        if("REQUEST".equals(event.state()))return; // A retry starting is not proof that the model is available.
        boolean limited=Set.of("RATE_LIMITED","WAITING_PROVIDER").contains(event.state());
        Instant available=limited&&event.retryAfterSeconds()!=null?event.detectedAt().plusSeconds(event.retryAfterSeconds()):null;
        var alternates=event.alternates()==null?List.<String>of():event.alternates().stream().map(ModelAvailabilityService::identifier).filter(Objects::nonNull).distinct().limit(12).toList();
        jdbc.update("""
            insert into provider_model_availability(provider,credential_scope,model,status,observed_at,detected_at,retry_after_seconds,available_at,retry_attempt_at,alternates_json)
            values (?,?,?,?,?,?,?,?,?,?) on conflict(provider,credential_scope,model) do update set
            status=excluded.status,observed_at=excluded.observed_at,
            detected_at=case when excluded.status in ('RATE_LIMITED','WAITING_PROVIDER') then excluded.detected_at else provider_model_availability.detected_at end,
            retry_after_seconds=excluded.retry_after_seconds,available_at=excluded.available_at,retry_attempt_at=excluded.retry_attempt_at,alternates_json=excluded.alternates_json
            where excluded.observed_at>=provider_model_availability.observed_at
            """,event.provider(),event.credentialScope(),model,event.state(),Timestamp.from(event.detectedAt()),limited?Timestamp.from(event.detectedAt()):null,
            limited?event.retryAfterSeconds():null,available==null?null:Timestamp.from(available),event.retryAttemptAt()==null?null:Timestamp.from(event.retryAttemptAt()),json.writeValueAsString(alternates));
        if(limited&&event.sessionId()!=null)notifyWaitingJob(user,event,model,available,alternates);
    }

    static List<Quota> validQuotas(List<Quota> quotas){
        if(quotas==null)return List.of();
        var result=new ArrayList<Quota>();
        for(var q:quotas){
            BigDecimal percent=q.usagePercentage();
            if(percent==null&&q.used()!=null&&q.limit()!=null&&q.limit()>0&&q.used()<=q.limit())
                percent=BigDecimal.valueOf(q.used()).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(q.limit()),4,java.math.RoundingMode.HALF_UP);
            if(percent!=null&&percent.signum()>=0&&percent.compareTo(BigDecimal.valueOf(100))<=0)
                result.add(new Quota(q.label(),q.period(),percent,q.used(),q.limit(),q.remaining(),q.resetsAt(),q.scope(),identifier(q.model())));
        }
        return List.copyOf(result);
    }
    private void notifyWaitingJob(String user,Event e,String model,Instant available,List<String> alternates){
        var meta=publicNotice(new Availability(e.provider(),model,e.state(),e.detectedAt(),e.retryAfterSeconds(),available,e.retryAttemptAt(),alternates,null));
        String text=notice(meta);
        // Leave the lease token intact: the original worker may still finish an internal Hermes retry.
        // NEEDS_REVIEW releases the UI, but retains the existing no-replay conversation barrier.
        var ids=jdbc.queryForList("""
            update chat_jobs j set status='NEEDS_REVIEW',notice=?,provider_notice_json=?,finished_at=now()
            from chat_conversations c where c.id=j.conversation_id and c.user_id=? and c.hermes_session_id=?
            and j.user_id=? and j.status in ('RUNNING','NEEDS_REVIEW') and j.lease_token is not null
            and j.review_released_at is null and j.lease_until is not null
            returning j.id
            """,UUID.class,text,json.writeValueAsString(meta),user,e.sessionId(),user);
        for(var id:ids)jdbc.update("""
            insert into proactive_messages(id,user_id,conversation_id,role,text,source,source_id,created_at)
            select id,user_id,conversation_id,'assistant',notice,'chat-job-notice',id,finished_at from chat_jobs where id=?
            on conflict(id) do update set text=excluded.text,created_at=excluded.created_at
            """,id);
    }
    public List<Availability> models(String user,String profile){
        return models(user,profile,null);
    }
    public List<Availability> models(String user,String profile,String selected){
        return jdbc.query("""
            select m.* from provider_model_availability m join provider_connections c on c.provider=m.provider and c.credential_scope=m.credential_scope
            where c.user_id=? and c.profile=? and (m.model=? or exists(select 1 from provider_observed_models o where o.user_id=c.user_id and o.provider=m.provider and o.model=m.model)) order by m.model
            """,(r,n)->new Availability(r.getString("provider"),r.getString("model"),status(r.getString("status"),time(r.getTimestamp("available_at")),clock.instant()),
            time(r.getTimestamp("detected_at")),r.getObject("retry_after_seconds",Long.class),time(r.getTimestamp("available_at")),time(r.getTimestamp("retry_attempt_at")),
            strings(r.getString("alternates_json")),"AVAILABLE".equals(r.getString("status"))?time(r.getTimestamp("observed_at")):null),user,profile,selected);
    }
    static String status(String stored,Instant available,Instant now){
        return Set.of("RATE_LIMITED","WAITING_PROVIDER").contains(stored)&&available!=null&&!now.isBefore(available)?"PENDING_CONFIRMATION":stored;
    }
    public void check(String profile,String model){
        if(identifier(model)==null)return;
        var owners=jdbc.queryForList("select user_id from chat_users where hermes_profile=?",String.class,profile);
        if(owners.size()!=1)return;
        for(var m:models(owners.getFirst(),profile,model))if(model.equals(m.model())&&Set.of("RATE_LIMITED","WAITING_PROVIDER").contains(m.status())
            &&m.availableAt()!=null&&clock.instant().isBefore(m.availableAt())){
            var meta=publicNotice(m);throw new Limited(notice(meta),meta);
        }
    }
    public List<Availability> modelsForProfile(String profile){
        var owners=jdbc.queryForList("select user_id from chat_users where hermes_profile=?",String.class,profile);
        return owners.size()==1?models(owners.getFirst(),profile):List.of();
    }
    public Map<String,Object> publicNotice(Availability model){
        var value=new LinkedHashMap<String,Object>();value.put("provider",model.provider());value.put("model",model.model());value.put("status",model.status());
        value.put("detectedAt",model.detectedAt());value.put("retryAfterSeconds",model.retryAfterSeconds());value.put("availableAt",model.availableAt());
        value.put("retryAttemptAt",model.retryAttemptAt());value.put("alternates",model.alternates());value.put("serverNow",clock.instant());return value;
    }
    public static String notice(Map<String,Object> meta){
        String text="Modelo temporariamente indisponível. "+("RATE_LIMITED".equals(meta.get("status"))?"A Nous limitou temporariamente este modelo.":"O Hermes está aguardando disponibilidade do provedor.");
        Object at=meta.get("availableAt");if(at!=null)text+=" Previsão informada: "+at+" (UTC).";
        else text+=" O provedor não informou horário de liberação.";
        return text+" Confira a disponibilidade em Conexões → Hermes. O pedido não foi confirmado como concluído; confira o registro antes de repetir alterações.";
    }
    private List<String> strings(String raw){try{return ((List<?>)json.readValue(raw,List.class)).stream().filter(String.class::isInstance).map(String.class::cast).toList();}catch(RuntimeException e){return List.of();}}
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=3600000,initialDelay=60000)
    public void cleanReceipts(){
        jdbc.update("delete from provider_telemetry_receipts where received_at<now()-interval '7 days'");
        jdbc.update("delete from provider_inference_events where occurred_at<now()-interval '2 months'");
    }
}
