package ayla.calendar;

import java.time.*;
import java.util.*;
import jakarta.validation.constraints.*;
public final class CalendarData {
    private CalendarData() {}
    public record Input(@Size(max=200) String title,@Size(max=4000) String description,@Size(max=500) String location,
                        OffsetDateTime startsAt,OffsetDateTime endsAt,@Size(max=80) String timeZone,Boolean allDay,
                        @Size(max=5) List<@NotNull @Min(0) @Max(40320) Integer> reminderMinutes,@Min(0) Long version,
                        @Min(1) @Max(525600) Integer durationMinutes) {
        public Input(String title,String description,String location,OffsetDateTime startsAt,OffsetDateTime endsAt,String timeZone,Boolean allDay,List<Integer> reminderMinutes,Long version){this(title,description,location,startsAt,endsAt,timeZone,allDay,reminderMinutes,version,null);}
    }
    public record Event(UUID id,String title,String description,String location,Instant startsAt,Instant endsAt,String timeZone,
                        boolean allDay,List<Integer> reminderMinutes,long version,String status,String source,
                        boolean googlePending,boolean googleLinked,Instant updatedAt,List<ScheduledReminder> scheduledReminders) {
        public Event(UUID id,String title,String description,String location,Instant startsAt,Instant endsAt,String timeZone,
                     boolean allDay,List<Integer> reminderMinutes,long version,String status,String source,boolean googlePending,boolean googleLinked,Instant updatedAt){
            this(id,title,description,location,startsAt,endsAt,timeZone,allDay,reminderMinutes,version,status,source,googlePending,googleLinked,updatedAt,List.of());
        }
        public Event withScheduledReminders(List<ScheduledReminder> reminders){return new Event(id,title,description,location,startsAt,endsAt,timeZone,allDay,reminderMinutes,version,status,source,googlePending,googleLinked,updatedAt,reminders);}
    }
    public record ScheduledReminder(UUID id,Instant dueAt,String deliveryStatus,Instant notifiedAt,String type,int minutesBefore) {}
    public record Page(List<Event> items,long total,int page,int size) {}
    public record Change(UUID id,@Min(0) Long version,@jakarta.validation.Valid @NotNull Input event) {}
    public record Plan(@NotBlank @Size(max=3000) String summary,@Size(max=10) List<@NotNull @jakarta.validation.Valid Change> changes) {}
    public record Target(@NotNull UUID id,@NotNull @Min(0) Long version) {}
    public record ReminderInput(@NotNull @Min(0) Long version,OffsetDateTime remindAt,
                                @Min(1) @Max(40320) Integer delayMinutes) {}
}
