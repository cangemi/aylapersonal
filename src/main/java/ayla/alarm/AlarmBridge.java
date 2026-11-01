package ayla.alarm;

import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import io.swagger.v3.oas.annotations.Hidden;
import static ayla.alarm.AlarmData.*;

@Hidden @RestController @RequestMapping("/internal/hermes/alarm")
public class AlarmBridge {
    private final AlarmService alarms; private final JdbcTemplate jdbc;
    public AlarmBridge(AlarmService alarms,JdbcTemplate jdbc){this.alarms=alarms;this.jdbc=jdbc;}
    public record Command(UUID id,@Min(0) Long version,
            @Size(max=200) String title,@Size(max=4000) String description,
            String type,java.time.OffsetDateTime alarmAt,@Min(1) @Max(40320) Integer delayMinutes,@Size(max=8) String timeOfDay,
            @Size(max=80) String timeZone,Boolean confirmNeeded,Boolean active){}
    @PostMapping("/{operation}") @Transactional
    public Object execute(@RequestHeader("X-Ayla-Session") String session,@PathVariable String operation,@Valid @RequestBody Command command){
        var owners=jdbc.queryForList("select user_id from chat_conversations where hermes_session_id=?",String.class,session);
        if(owners.size()!=1)throw AlarmService.error(403,"Abra a conversa pela API Java.");String user=owners.getFirst();
        return switch(operation){
            case "list" -> alarms.list(user);
            case "get" -> alarms.get(user,required(command.id()));
            case "save" -> {
                var input=new Input(command.title(),command.description(),command.type()==null?"ONCE":command.type(),command.alarmAt(),command.delayMinutes(),command.timeOfDay(),command.timeZone(),command.confirmNeeded(),command.active(),command.version());
                yield alarms.save(user,command.id(),input,null);
            }
            case "delete" -> {alarms.delete(user,required(command.id()),command.version()==null?-1:command.version());yield Map.of("deleted",true);}
            case "confirm" -> {alarms.confirm(user,required(command.id()));yield Map.of("confirmed",true);}
            default -> throw AlarmService.error(404,"Operação de alarme não encontrada.");
        };
    }
    private UUID required(UUID id){if(id==null)throw AlarmService.error(400,"Consulte o alarme para identificar qual alterar.");return id;}
}
