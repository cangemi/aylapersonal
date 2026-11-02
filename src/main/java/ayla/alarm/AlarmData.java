package ayla.alarm;

import java.time.*;
import java.util.*;
import jakarta.validation.constraints.*;

public final class AlarmData {
    private AlarmData() {}

    public record Input(
            @Size(max=200) String title,
            @Size(max=4000) String description,
            @Size(max=16) String type,
            OffsetDateTime alarmAt,
            @Min(1) @Max(40320) Integer delayMinutes,
            @Size(max=8) String timeOfDay,
            @Min(1) @Max(7) Integer dayOfWeek,
            @Size(max=80) String timeZone,
            Boolean confirmNeeded,
            Boolean active,
            @Min(0) Long version) {}

    public record Alarm(UUID id, String title, String description, String type, Instant alarmAt, String timeOfDay,
                         Integer dayOfWeek, String timeZone, boolean confirmNeeded, boolean active, long version,
                         Instant createdAt, Instant updatedAt, Instant lastFiredAt,
                         boolean confirmPending, Instant nextSnoozeAt) {}

    public record Page(List<Alarm> items, long total, int page, int size) {}

    public record Notification(UUID id, UUID alarmId, Instant dueAt, Instant notifiedAt, String whatsappStatus,
                               Instant readAt, Instant confirmedAt, boolean snooze, String title) {}
}
