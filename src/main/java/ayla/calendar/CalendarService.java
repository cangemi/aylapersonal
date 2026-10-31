package ayla.calendar;

import static ayla.calendar.CalendarData.*;
import ayla.hermes.HermesException;
import java.time.*;
import java.util.*;
import java.sql.Timestamp;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class CalendarService {
    final JdbcTemplate jdbc;
    final GoogleCalendarSync google;
    static final RowMapper<Event> ROW=(r,n)->new Event(r.getObject("id",UUID.class),r.getString("title"),r.getString("description"),r.getString("location"),
            r.getTimestamp("starts_at").toInstant(),r.getTimestamp("ends_at").toInstant(),r.getString("time_zone"),r.getBoolean("all_day"),
            Arrays.asList((Integer[])r.getArray("reminder_minutes").getArray()),r.getLong("version"),r.getString("status"),r.getString("source"),
            r.getBoolean("google_dirty"),r.getString("google_id")!=null,r.getTimestamp("updated_at").toInstant());
    public CalendarService(JdbcTemplate jdbc, GoogleCalendarSync google){this.jdbc=jdbc;this.google=google;}
    public Event get(String user,UUID id){return jdbc.query("select * from calendar_events where user_id=? and id=?",ROW,user,id).stream().findFirst().orElseThrow(()->error(404,"Compromisso não encontrado."));}
    public List<Event> withScheduledReminders(String user,List<Event> events){
        if(events.isEmpty())return events;
        var ids="{"+String.join(",",events.stream().map(e->e.id().toString()).toList())+"}";
        var rows=jdbc.queryForList("select r.id,r.event_id,r.due_at,r.whatsapp_status,r.notified_at,r.reminder_type,r.minutes_before from calendar_reminders r join calendar_events e on e.id=r.event_id where e.user_id=? and e.id=any(?::uuid[]) and r.event_version=e.version and not r.cancelled order by r.due_at,r.id",user,ids);
        var grouped=new HashMap<UUID,List<ScheduledReminder>>();
        for(var row:rows){
            var notified=(Timestamp)row.get("notified_at");
            grouped.computeIfAbsent((UUID)row.get("event_id"),unused->new ArrayList<>()).add(new ScheduledReminder((UUID)row.get("id"),((Timestamp)row.get("due_at")).toInstant(),(String)row.get("whatsapp_status"),notified==null?null:notified.toInstant(),(String)row.get("reminder_type"),((Number)row.get("minutes_before")).intValue()));
        }
        return events.stream().map(e->e.withScheduledReminders(grouped.getOrDefault(e.id(),List.of()))).toList();
    }
    public Page withScheduledReminders(String user,Page page){return new Page(withScheduledReminders(user,page.items()),page.total(),page.page(),page.size());}
    public Map<String,Object> agentSnapshot(String user){
        var from=LocalDate.now(ZoneId.of("America/Sao_Paulo"));
        var page=list(user,from,from.plusDays(30),null,0,40);
        return Map.of("from",from,"through",from.plusDays(30),"total",page.total(),"events",page.items().stream().map(this::agentEvent).toList(),
                "note","Somente eventos ativos, até 40 neste período. Ausência fora desse limite não significa agenda livre. Consulte ferramentas para datas diferentes, detalhes, cancelados e lembretes. Após qualquer alteração, prevalece o resultado atual da ferramenta.");
    }
    public Map<String,Object> agentContext(String user,UUID conversation){
        var result=new LinkedHashMap<>(agentSnapshot(user));
        result.put("pendingPlan",plan(user,conversation));return result;
    }
    public Object plan(String user,UUID conversation){
        var rows=jdbc.queryForList("select plan::text from calendar_conversation_plans where user_id=? and conversation_id=?",String.class,user,conversation);
        return rows.isEmpty()?Map.of():JsonMapper.builder().build().readValue(rows.getFirst(),Map.class);
    }
    public Object rememberPlan(String user,UUID conversation,Plan plan){
        if(plan==null)throw error(400,"Informe o pedido ainda pendente.");
        String json=JsonMapper.builder().build().writeValueAsString(plan);
        if(json.length()>16000)throw error(400,"O pedido tem dados demais. Divida em partes.");
        jdbc.update("insert into calendar_conversation_plans(conversation_id,user_id,plan) values (?,?,?::jsonb) on conflict(conversation_id) do update set plan=excluded.plan,updated_at=now() where calendar_conversation_plans.user_id=excluded.user_id",conversation,user,json);
        return Map.of("pendingPlan",plan,"saved",true,"instruction","Rascunho do pedido, não compromissos gravados. Preserve todas as tarefas ainda pendentes. Não confirme agendamento com este resultado.");
    }
    public void clearPlan(String user,UUID conversation){jdbc.update("delete from calendar_conversation_plans where user_id=? and conversation_id=?",user,conversation);}
    @Transactional
    public List<Event> apply(String user,UUID conversation,List<Change> changes,String key){
        if(changes==null||changes.isEmpty()||changes.size()>10)throw error(400,"Informe de uma a dez alterações.");
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"calendar-schedule:"+user);
        var pending=plan(user,conversation);
        if(pending instanceof Map<?,?> draft && draft.get("changes") instanceof List<?> expected){
            var remaining=new ArrayList<>(changes);
            for(var item:expected){
                if(!(item instanceof Map<?,?> task))continue;
                Object expectedId=task.get("id");
                String expectedTitle=task.get("event") instanceof Map<?,?> data && data.get("title") instanceof String title?title:null;
                int match=-1;
                for(int n=0;n<remaining.size();n++){
                    var candidate=remaining.get(n);
                    if(expectedId!=null?expectedId.toString().equals(String.valueOf(candidate.id())):
                            candidate.id()==null&&candidate.event()!=null&&expectedTitle!=null&&expectedTitle.equalsIgnoreCase(candidate.event().title())){match=n;break;}
                }
                if(match<0)throw error(400,"O pedido tem outras ações pendentes. Inclua todos os compromissos solicitados ou atualize o rascunho se o usuário mudou de ideia.");
                remaining.remove(match);
            }
        }
        var ordered=new ArrayList<>(changes);ordered.sort(Comparator.comparing(c->c.id()==null));
        var result=new ArrayList<Event>();int index=0;
        for(var change:ordered){
            var event=change.event();if(event==null)throw error(400,"Informe os dados de cada compromisso.");
            Long version=change.version()==null?event.version():change.version();
            if(change.version()!=null&&event.version()!=null&&!change.version().equals(event.version()))throw error(400,"As versões informadas são diferentes.");
            var input=new Input(event.title(),event.description(),event.location(),event.startsAt(),event.endsAt(),event.timeZone(),event.allDay(),event.reminderMinutes(),version,event.durationMinutes());
            result.add(save(user,change.id(),input,key+":"+index++));
        }
        clearPlan(user,conversation);
        return result;
    }
    public Map<String,Object> agentEvent(Event event){
        var zone=ZoneId.of(event.timeZone());
        var start=event.startsAt().atZone(zone);var end=event.endsAt().atZone(zone);
        var result=new LinkedHashMap<String,Object>();
        result.put("id",event.id());result.put("title",event.title());result.put("description",event.description());result.put("location",event.location());
        result.put("startsAt",start.toOffsetDateTime().toString());result.put("endsAt",end.toOffsetDateTime().toString());result.put("timeZone",event.timeZone());
        var format=java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
        result.put("localStart",start.format(format));result.put("localEnd",end.format(format));
        result.put("timeInstruction","Horários já convertidos para o fuso do compromisso. Leia localStart/localEnd diretamente; não some nem subtraia horas.");
        result.put("allDay",event.allDay());result.put("reminderMinutes",event.reminderMinutes());result.put("version",event.version());result.put("status",event.status());
        result.put("googlePending",event.googlePending());result.put("googleLinked",event.googleLinked());result.put("source",event.source());
        return result;
    }
    public Map<String,Object> availability(String user,OffsetDateTime startsAt,OffsetDateTime endsAt){
        if(startsAt==null)throw error(400,"Esclareça o dia e se o horário é de manhã, tarde ou noite antes de verificar.");
        if(endsAt!=null&&!endsAt.isAfter(startsAt))throw error(400,"O término deve ser posterior ao início.");
        Instant start=startsAt.toInstant();Instant end=endsAt==null?start:endsAt.toInstant();
        String where=" where user_id=? and status='ACTIVE' and starts_at"+(endsAt==null?"<=?":"<?")+" and ends_at>?";
        long total=jdbc.queryForObject("select count(*) from calendar_events"+where,Long.class,user,Timestamp.from(end),Timestamp.from(start));
        var conflicts=jdbc.query("select * from calendar_events"+where+" order by starts_at limit 10",ROW,user,Timestamp.from(end),Timestamp.from(start));
        return Map.of("hasConflict",total>0,"canConfirmFree",endsAt!=null&&total==0,"completeInterval",endsAt!=null,"totalConflicts",total,
                "conflicts",conflicts.stream().map(this::agentEvent).toList(),
                "instruction",endsAt==null?"Falta término/duração: não afirme que está livre. Conflitos listados já ocupam o início informado.":"Resultado calculado no PostgreSQL para o intervalo completo. Não deduza conflitos comparando horários UTC.");
    }
    public Map<String,Object> agentPage(Page page){
        return Map.of("items",page.items().stream().map(this::agentEvent).toList(),"total",page.total(),"page",page.page(),"size",page.size());
    }
    public Page list(String user,LocalDate from,LocalDate to,String query,int page,int size){
        if(page<0||size<1||size>100)throw error(400,"Paginação inválida.");
        var zone=ZoneId.of("America/Sao_Paulo");if(from==null)from=LocalDate.now(zone);if(to==null)to=from.plusDays(30);
        if(to.isBefore(from)||to.isAfter(from.plusYears(3)))throw error(400,"Informe um período de até três anos.");
        var args=new ArrayList<Object>(List.of(user,Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant()),Timestamp.from(from.atStartOfDay(zone).toInstant()),"%"+(query==null?"":query.strip().toLowerCase(Locale.ROOT)).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%"));
        String where=" where user_id=? and status='ACTIVE' and starts_at<? and ends_at>? and lower(title) like ?";
        long total=jdbc.queryForObject("select count(*) from calendar_events"+where,Long.class,args.toArray());
        args.add(size);args.add((long)page*size);
        return new Page(jdbc.query("select * from calendar_events"+where+" order by starts_at,id limit ? offset ?",ROW,args.toArray()),total,page,size);
    }
    @Transactional
    public Event save(String user,UUID id,Input input,String key){
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"calendar-schedule:"+user);
        String fingerprint=hash((id==null?"create":id.toString())+JsonMapper.builder().build().writeValueAsString(input));
        if(key!=null){if(key.isBlank()||key.length()>200)throw error(400,"Chave de operação inválida.");
            jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"calendar:"+user+":"+key);
            var receipts=jdbc.queryForList("select fingerprint,event_id from calendar_action_receipts where user_id=? and action_key=?",user,key);
            if(!receipts.isEmpty()){if(!fingerprint.equals(receipts.getFirst().get("fingerprint")))throw error(409,"A chave já foi usada em outro pedido.");return get(user,(UUID)receipts.getFirst().get("event_id"));}}
        GoogleCalendarSync.GoogleFailure googleFailure = null;
        // Sincronizar Google antes de salvar, se conectado
        if(jdbc.queryForObject("select count(*) from calendar_google_connections where user_id=? and refresh_secret<>''",Integer.class,user) > 0){
            try{google.sync(user);}catch(Exception ignored){}
        }
        Event old=null;
        if(id!=null){jdbc.queryForList("select id from calendar_events where user_id=? and id=? for update",user,id);old=get(user,id);
            if(!old.status().equals("ACTIVE"))throw error(409,"Esse compromisso já foi cancelado.");
            if(input.version()==null)throw error(400,"Consulte os detalhes atuais do compromisso antes de editar.");
            if(input.version()!=old.version())throw error(409,"O compromisso mudou. Consulte novamente antes de editar.");}
        String title=input.title()==null?(old==null?"":old.title()):input.title().strip();if(title.isBlank())throw error(400,"Qual é o compromisso?");
        String zone=input.timeZone()==null?(old==null?"America/Sao_Paulo":old.timeZone()):input.timeZone();try{ZoneId.of(zone);}catch(Exception e){throw error(400,"Fuso horário inválido.");}
        Instant start=input.startsAt()==null?(old==null?null:old.startsAt()):input.startsAt().toInstant();if(start==null)throw error(400,"Em qual dia e horário será o compromisso?");
        if(old==null&&!Boolean.TRUE.equals(input.allDay())&&input.endsAt()==null&&input.durationMinutes()==null)
            throw error(400,"Até que horas será o compromisso ou quanto tempo vai durar?");
        Instant end=input.endsAt()==null?(old==null?start.atZone(ZoneId.of(zone)).plusDays(1).toInstant():input.startsAt()==null?old.endsAt():start.plus(Duration.between(old.startsAt(),old.endsAt()))):input.endsAt().toInstant();
        if(input.durationMinutes()!=null){
            if(input.durationMinutes()<1||input.durationMinutes()>525600)throw error(400,"Informe uma duração positiva de até um ano.");
            Instant durationEnd=start.plusSeconds(input.durationMinutes()*60L);
            if(input.endsAt()!=null&&!end.equals(durationEnd))throw error(400,"A duração e o horário de término não correspondem. Informe apenas um deles.");
            if(Boolean.TRUE.equals(input.allDay())||(old!=null&&old.allDay()&&!Boolean.FALSE.equals(input.allDay())))
                throw error(400,"Para duração em minutos, desmarque dia inteiro e informe o horário.");
            end=durationEnd;
        }
        if(!end.isAfter(start))throw error(400,"O término deve ser depois do início.");
        var reminders=input.reminderMinutes()==null?(old==null?List.<Integer>of():old.reminderMinutes()):input.reminderMinutes().stream().distinct().sorted().toList();
        if(reminders.size()>5||reminders.stream().anyMatch(m->m==null||m<0||m>40320))throw error(400,"Use até cinco lembretes, com antecedência de até 28 dias.");
        UUID eventId=id==null?UUID.randomUUID():id;
        String description=input.description()==null?(old==null?"":old.description()):input.description();String location=input.location()==null?(old==null?"":old.location()):input.location();
        boolean allDay=input.allDay()==null?old!=null&&old.allDay():input.allDay();
        if(allDay){start=start.atZone(ZoneId.of(zone)).toLocalDate().atStartOfDay(ZoneId.of(zone)).toInstant();end=end.atZone(ZoneId.of(zone)).toLocalDate().atStartOfDay(ZoneId.of(zone)).toInstant();if(!end.isAfter(start))end=start.atZone(ZoneId.of(zone)).plusDays(1).toInstant();}
        if(old==null||!start.equals(old.startsAt())||!end.equals(old.endsAt())){
            var conflicts=jdbc.query("select * from calendar_events where user_id=? and status='ACTIVE' and id<>? and starts_at<? and ends_at>? order by starts_at limit 3",
                    ROW,user,eventId,Timestamp.from(end),Timestamp.from(start));
            if(!conflicts.isEmpty()){
                var formatter=java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of(zone));
                String details=String.join("; ",conflicts.stream().map(e->e.title()+" ("+formatter.format(e.startsAt())+" até "+formatter.format(e.endsAt())+")").toList());
                throw error(409,"Esse horário conflita com: "+details+". O compromisso não foi salvo. Escolha outro horário ou remarque o compromisso existente.");
            }
        }
        if(old==null)jdbc.update("insert into calendar_events(id,user_id,title,description,location,starts_at,ends_at,time_zone,all_day,reminder_minutes) values (?,?,?,?,?,?,?,?,?,?::integer[])",eventId,user,title,description,location,Timestamp.from(start),Timestamp.from(end),zone,allDay,array(reminders));
        else jdbc.update("update calendar_events set title=?,description=?,location=?,starts_at=?,ends_at=?,time_zone=?,all_day=?,reminder_minutes=?::integer[],version=version+1,google_dirty=true,updated_at=now() where id=? and user_id=?",title,description,location,Timestamp.from(start),Timestamp.from(end),zone,allDay,array(reminders),eventId,user);
        if(input.reminderMinutes()!=null&&input.reminderMinutes().isEmpty())
            jdbc.update("update calendar_reminders set cancelled=true where event_id=? and reminder_type='AT_TIME'",eventId);
        Event saved=get(user,eventId);carryReminders(old,saved);schedule(saved,false);
        wakeGoogle(user);
        if(key!=null)jdbc.update("insert into calendar_action_receipts(user_id,action_key,fingerprint,event_id) values (?,?,?,?)",user,key,fingerprint,eventId);
        return saved;
    }
    @Transactional
    public Event cancel(String user,UUID id,long version){
        jdbc.queryForList("select id from calendar_events where user_id=? and id=? for update",user,id);var event=get(user,id);
        if(event.status().equals("CANCELLED"))return event;
        if(event.version()!=version)throw error(409,"O compromisso mudou. Consulte novamente.");
        jdbc.update("update calendar_events set status='CANCELLED',version=version+1,google_dirty=true,updated_at=now() where user_id=? and id=?",user,id);
        jdbc.update("update calendar_reminders set cancelled=true where event_id=?",id);wakeGoogle(user);return get(user,id);
    }
    private void wakeGoogle(String user){jdbc.update("update calendar_google_connections set sync_status='PENDING',next_sync_at=least(next_sync_at,now()) where user_id=? and refresh_secret<>''",user);}
    @Transactional
    public List<Event> cancelMany(String user,List<Target> targets){
        if(targets==null||targets.isEmpty()||targets.size()>20)throw error(400,"Informe de um a vinte compromissos.");
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"calendar-schedule:"+user);
        var result=new ArrayList<Event>();
        for(var target:targets)result.add(cancel(user,target.id(),target.version()));
        return result;
    }
    @Transactional
    public Map<String,Object> remind(String user,UUID id,ReminderInput input,String key){
        jdbc.queryForList("select id from calendar_events where user_id=? and id=? for update",user,id);
        var event=get(user,id);
        if(key!=null){
            var existing=jdbc.queryForList("select id,due_at as \"dueAt\",reminder_type as \"reminderType\",cancelled from calendar_reminders where event_id=? and action_key=?",id,key);
            if(!existing.isEmpty())return localReminder(existing.getFirst(),event.timeZone());
        }
        if(!event.status().equals("ACTIVE"))throw error(409,"Esse compromisso foi cancelado.");
        if(input.version()==null||input.version()!=event.version())throw error(409,"Consulte o compromisso atual antes de configurar o lembrete.");
        if((input.remindAt()==null)==(input.delayMinutes()==null))throw error(400,"Informe quando lembrar ou daqui a quantos minutos.");
        if(input.delayMinutes()!=null&&(input.delayMinutes()<1||input.delayMinutes()>40320))throw error(400,"Informe um intervalo entre um minuto e 28 dias.");
        Instant now=Instant.now();
        Instant due=input.remindAt()!=null?input.remindAt().toInstant():now.plusSeconds(input.delayMinutes()*60L);
        if(!due.isAfter(now)||due.isAfter(now.plus(Duration.ofDays(28)))||!due.isBefore(event.endsAt()))throw error(400,"O lembrete deve ser futuro, em até 28 dias e antes do fim do compromisso.");
        UUID reminderId=UUID.randomUUID();
        jdbc.update("insert into calendar_reminders(id,event_id,event_version,minutes_before,due_at,reminder_type,action_key) values (?,?,?,0,?,'AT_TIME',?) on conflict do nothing",reminderId,id,event.version(),Timestamp.from(due),key);
        return localReminder(jdbc.queryForMap("select id,due_at as \"dueAt\",reminder_type as \"reminderType\",cancelled from calendar_reminders where event_id=? and event_version=? and reminder_type='AT_TIME' and due_at=?",id,event.version(),Timestamp.from(due)),event.timeZone());
    }
    public List<Map<String,Object>> pendingReminders(String user,UUID id){
        var event=get(user,id);
        return jdbc.queryForList("select r.id,r.due_at as \"dueAt\",r.reminder_type as \"reminderType\",r.minutes_before as \"minutesBefore\",r.whatsapp_status as \"whatsappStatus\" from calendar_reminders r join calendar_events e on e.id=r.event_id where e.user_id=? and e.id=? and r.event_version=e.version and not r.cancelled order by r.due_at",user,id).stream().map(row->localReminder(row,event.timeZone())).toList();
    }
    private Map<String,Object> localReminder(Map<String,Object> row,String timeZone){
        var result=new LinkedHashMap<>(row);
        if(row.get("dueAt") instanceof Timestamp timestamp){
            var local=timestamp.toInstant().atZone(ZoneId.of(timeZone));
            result.put("dueAt",local.toOffsetDateTime().toString());
            result.put("localDueAt",local.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")));
            result.put("timeZone",timeZone);
        }
        return result;
    }
    void carryReminders(Event previous,Event next){
        if(previous!=null)
            jdbc.update("update calendar_reminders set event_version=? where event_id=? and event_version=? and not cancelled and reminder_type='AT_TIME'",next.version(),next.id(),previous.version());
        if(previous!=null&&previous.startsAt().equals(next.startsAt()))
            jdbc.update("update calendar_reminders set event_version=? where event_id=? and event_version=? and not cancelled and reminder_type='BEFORE_START' and minutes_before=any(?::integer[])",next.version(),next.id(),previous.version(),array(next.reminderMinutes()));
    }
    void schedule(Event event,boolean imported){
        jdbc.update("update calendar_reminders set cancelled=true where event_id=? and event_version<>?",event.id(),event.version());
        if(!event.status().equals("ACTIVE")||!event.startsAt().isAfter(Instant.now()))return;
        for(int before:event.reminderMinutes()){
            Instant due=event.startsAt().minusSeconds(before*60L);if(due.isBefore(Instant.now())){if(imported)continue;due=Instant.now();}
            jdbc.update("insert into calendar_reminders(id,event_id,event_version,minutes_before,due_at) values (?,?,?,?,?) on conflict do nothing",UUID.randomUUID(),event.id(),event.version(),before,Timestamp.from(due));
        }
    }
    public List<Map<String,Object>> notifications(String user,boolean unread){return notifications(user,unread,false);}
    public List<Map<String,Object>> notifications(String user,boolean unread,boolean archived){return jdbc.queryForList("""
        select r.id,e.id as "eventId",e.title,e.starts_at as "startsAt",e.time_zone as "timeZone",r.due_at as "dueAt",r.reminder_type as "reminderType",r.notified_at as "notifiedAt",r.read_at as "readAt",r.whatsapp_status as "whatsappStatus"
        from calendar_reminders r join calendar_events e on e.id=r.event_id
        where e.user_id=? and r.notified_at is not null
        """+(archived?" and (r.whatsapp_status='SENT' or r.read_at is not null)":" and not r.cancelled and e.version=r.event_version"+(unread?" and r.read_at is null and r.whatsapp_status<>'SENT'":""))+" order by r.notified_at desc limit 50",user);}
    public void read(String user,UUID id){jdbc.update("update calendar_reminders r set read_at=coalesce(read_at,now()) from calendar_events e where r.event_id=e.id and e.user_id=? and r.id=?",user,id);}
    static String array(List<Integer> values){return "{"+String.join(",",values.stream().map(String::valueOf).toList())+"}";}
    static String hash(String value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    static HermesException error(int status,String message){return new HermesException(status,message);}
}
