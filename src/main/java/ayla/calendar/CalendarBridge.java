package ayla.calendar;

import java.util.*;
import java.time.LocalDate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import io.swagger.v3.oas.annotations.Hidden;
import static ayla.calendar.CalendarData.*;

@Hidden @RestController @RequestMapping("/internal/hermes/calendar")
public class CalendarBridge {
    private final CalendarService calendar;private final GoogleCalendarSync google;private final JdbcTemplate jdbc;
    public CalendarBridge(CalendarService calendar,GoogleCalendarSync google,JdbcTemplate jdbc){this.calendar=calendar;this.google=google;this.jdbc=jdbc;}
    public record Command(UUID id,@Min(0) Long version,LocalDate from,LocalDate to,@Size(max=200) String query,@Min(0) Integer page,@Min(1) @Max(100) Integer size,@Valid Input event,@Size(min=1,max=20) List<@Valid Target> targets,java.time.OffsetDateTime remindAt,@Min(1) @Max(40320) Integer delayMinutes,java.time.OffsetDateTime startsAt,java.time.OffsetDateTime endsAt,@Valid Plan plan,@Size(min=1,max=10) List<@NotNull @Valid Change> changes){}
    @PostMapping("/{operation}") @Transactional
    public Object execute(@RequestHeader("X-Ayla-Session") String session,@PathVariable String operation,@Valid @RequestBody Command command){
        var owners=jdbc.queryForList("select user_id from chat_conversations where hermes_session_id=?",String.class,session);
        if(owners.size()!=1)throw CalendarService.error(403,"Abra a conversa pela API Java.");String user=owners.getFirst();ayla.monitoring.MonitoringContext.user(user);
        String receipt=null;
        if(Set.of("save","cancel","cancel_many","remind","apply").contains(operation)){
            var turns=jdbc.queryForList("select t.id from finance_agent_turns t join chat_conversations c on c.id=t.conversation_id where c.hermes_session_id=? and t.user_id=? order by t.sequence desc limit 1",UUID.class,session,user);
            if(turns.isEmpty())throw CalendarService.error(409,"Faça o pedido na conversa antes de alterar a agenda.");
            receipt="agent:"+turns.getFirst()+":"+CalendarService.hash(operation+tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(command));
        }
        UUID conversation=jdbc.queryForObject("select id from chat_conversations where hermes_session_id=? and user_id=?",UUID.class,session,user);
        return switch(operation){
            case "plan" -> calendar.rememberPlan(user,conversation,command.plan());
            case "plan_clear" -> {calendar.clearPlan(user,conversation);yield Map.of("cleared",true);}
            case "apply" -> calendar.apply(user,conversation,command.changes(),receipt).stream().map(calendar::agentEvent).toList();
            case "list" -> calendar.agentPage(calendar.list(user,command.from(),command.to(),command.query(),command.page()==null?0:command.page(),command.size()==null?50:command.size()));
            case "check" -> calendar.availability(user,command.startsAt(),command.endsAt());
            case "get" -> Map.of("event",calendar.agentEvent(calendar.get(user,required(command.id()))),"reminders",calendar.pendingReminders(user,command.id()));
            case "cancel_many" -> calendar.cancelMany(user,command.targets()).stream().map(calendar::agentEvent).toList();
            case "remind" -> calendar.remind(user,required(command.id()),new ReminderInput(command.version(),command.remindAt(),command.delayMinutes()),receipt);
            case "save" -> {
                var event=command.event();if(event==null)throw CalendarService.error(400,"Informe o compromisso e os dados a alterar.");
                if(command.version()!=null&&event.version()!=null&&!command.version().equals(event.version()))
                    throw CalendarService.error(400,"As versões informadas são diferentes. Consulte o compromisso atual.");
                Long version=command.version()!=null?command.version():event.version();
                var normalized=new Input(event.title(),event.description(),event.location(),event.startsAt(),event.endsAt(),event.timeZone(),event.allDay(),event.reminderMinutes(),version,event.durationMinutes());
                yield calendar.agentEvent(calendar.save(user,command.id(),normalized,receipt));
            }
            case "cancel" -> {if(command.version()==null)throw CalendarService.error(400,"Consulte o compromisso antes de cancelar.");yield calendar.agentEvent(calendar.cancel(user,required(command.id()),command.version()));}
            case "google_status" -> google.status(user);
            case "google_sync" -> {google.requestSync(user);yield Map.of("queued",true);}
            case "google_disconnect" -> {google.disconnect(user);yield Map.of("connected",false);}
            case "google_connect" -> Map.of("authorizationUrl",google.connect(user),"message","Abra este link para autorizar sua conta Google. A agenda própria já funciona sem conectar.");
            default -> throw CalendarService.error(404,"Operação da agenda não encontrada.");
        };
    }
    private UUID required(UUID id){if(id==null)throw CalendarService.error(400,"Consulte o compromisso para identificar qual alterar.");return id;}
}
