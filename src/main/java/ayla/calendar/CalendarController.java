package ayla.calendar;

import static ayla.calendar.CalendarData.*;
import static ayla.calendar.CalendarService.*;
import java.security.Principal;
import java.time.LocalDate;
import java.util.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import ayla.chat.session.ChatIdentity;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/calendar")
@Tag(name="Agenda",description="Compromissos e lembretes pessoais. Google opcional; alterações são sincronizadas em segundo plano.")
public class CalendarController {
    private final CalendarService calendar;private final ChatIdentity identity;private final GoogleCalendarSync google;
    public CalendarController(CalendarService calendar,ChatIdentity identity,GoogleCalendarSync google){this.calendar=calendar;this.identity=identity;this.google=google;}
    @GetMapping("/events") public Page list(Principal p,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,@RequestParam(required=false) String q,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size){String user=identity.resolve(p);return calendar.withScheduledReminders(user,calendar.list(user,from,to,q,page,size));}
    @GetMapping("/events/{id}") public Event get(Principal p,@PathVariable UUID id){String user=identity.resolve(p);return calendar.withScheduledReminders(user,List.of(calendar.get(user,id))).getFirst();}
    @PostMapping("/events") public Event create(Principal p,@Valid @RequestBody Input input,@RequestHeader(value="Idempotency-Key",required=false) String key){return calendar.save(identity.resolve(p),null,input,key);}
    @PatchMapping("/events/{id}") public Event edit(Principal p,@PathVariable UUID id,@Valid @RequestBody Input input,@RequestHeader(value="Idempotency-Key",required=false) String key){return calendar.save(identity.resolve(p),id,input,key);}
    @DeleteMapping("/events/{id}") public Event cancel(Principal p,@PathVariable UUID id,@RequestParam long version){return calendar.cancel(identity.resolve(p),id,version);}
    @GetMapping("/events/{id}/reminders") public Object eventReminders(Principal p,@PathVariable UUID id){return calendar.pendingReminders(identity.resolve(p),id);}
    @PostMapping("/events/{id}/reminders") public Object remind(Principal p,@PathVariable UUID id,@Valid @RequestBody ReminderInput input,@RequestHeader(value="Idempotency-Key",required=false) String key){return calendar.remind(identity.resolve(p),id,input,key==null?null:"public:"+CalendarService.hash(identity.resolve(p)+":"+id+":"+key));}
    @GetMapping("/reminders") public Object reminders(Principal p,@RequestParam(defaultValue="true") boolean unread,@RequestParam(defaultValue="false") boolean archived){return calendar.notifications(identity.resolve(p),unread,archived);}
    @PostMapping("/reminders/{id}/read") public Map<String,Boolean> read(Principal p,@PathVariable UUID id){calendar.read(identity.resolve(p),id);return Map.of("read",true);}
    @GetMapping("/google") public Object status(Principal p){return google.status(identity.resolve(p));}
    @GetMapping("/google/connections") public Object listConnections(Principal p){return google.listConnections(identity.resolve(p));}
    @PostMapping("/google/connect") public Object connect(Principal p){return Map.of("authorizationUrl",google.connect(identity.resolve(p)));}
    @PutMapping("/google/connections/{connectionId}/can-schedule") public Object updateCanSchedule(Principal p,@PathVariable String connectionId,@RequestBody Map<String,Boolean> body){
        Boolean canSchedule=body.get("canSchedule");
        if(canSchedule==null)throw error(400,"canSchedule é obrigatório");
        google.updateCanSchedule(identity.resolve(p),connectionId,canSchedule);
        return Map.of("canSchedule",canSchedule);
    }
    @DeleteMapping("/google/connections/{connectionId}") public Object disconnect(Principal p,@PathVariable String connectionId){
        google.disconnectConnection(identity.resolve(p),connectionId);
        return Map.of("disconnected",true);
    }
    @DeleteMapping("/google") public Object disconnectAll(Principal p){google.disconnect(identity.resolve(p));return Map.of("connected",false);}
    @PostMapping("/google/sync") public Object sync(Principal p){google.requestSync(identity.resolve(p));return Map.of("queued",true);}
    @GetMapping("/google/callback") public org.springframework.http.ResponseEntity<Void> callback(@RequestParam(required=false) String state,@RequestParam(required=false) String code,@RequestParam(required=false) String error){
        boolean success=false;try{if(error==null){google.callback(state,code);success=true;}}catch(RuntimeException ignored){}
        return org.springframework.http.ResponseEntity.status(303).location(java.net.URI.create(google.returnUrl(success))).header("Cache-Control","no-store").build();
    }
}
