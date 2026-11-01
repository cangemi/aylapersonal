package ayla.alarm;

import static ayla.alarm.AlarmData.*;
import java.security.Principal;
import java.time.LocalDate;
import java.util.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import ayla.chat.session.ChatIdentity;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/alarms")
@Tag(name="Alarmes",description="Alarmes únicos e recorrentes por usuário.")
public class AlarmController {
    private final AlarmService alarms; private final ChatIdentity identity;
    public AlarmController(AlarmService alarms, ChatIdentity identity){this.alarms=alarms;this.identity=identity;}
    @GetMapping("") public List<Alarm> list(Principal p){return alarms.list(identity.resolve(p));}
    @GetMapping("/{id}") public Alarm get(Principal p,@PathVariable UUID id){return alarms.get(identity.resolve(p),id);}
    @PostMapping("") public Alarm create(Principal p,@Valid @RequestBody Input input){return alarms.save(identity.resolve(p),null,input,null);}
    @PatchMapping("/{id}") public Alarm edit(Principal p,@PathVariable UUID id,@Valid @RequestBody Input input){return alarms.save(identity.resolve(p),id,input,null);}
    @DeleteMapping("/{id}") public Map<String,Boolean> delete(Principal p,@PathVariable UUID id,@RequestParam long version){alarms.delete(identity.resolve(p),id,version);return Map.of("deleted",true);}
    @PostMapping("/{id}/confirm") public Map<String,Boolean> confirm(Principal p,@PathVariable UUID id){alarms.confirm(identity.resolve(p),id);return Map.of("confirmed",true);}
    @GetMapping("/notifications") public List<Notification> notifications(Principal p,@RequestParam(defaultValue="true") boolean unread){return alarms.notifications(identity.resolve(p),unread);}
    @PostMapping("/notifications/{id}/read") public Map<String,Boolean> read(Principal p,@PathVariable UUID id){alarms.read(identity.resolve(p),id);return Map.of("read",true);}
}
