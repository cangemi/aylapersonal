package ayla.memory;

import java.time.Instant;
import java.util.UUID;
import jakarta.validation.constraints.*;

public final class PersonalMemory {
    private PersonalMemory() {}
    public enum Kind { PROFILE, PREFERENCE, PET, POSSESSION, EXPERIENCE }
    public enum Source { USER, IMAGE }
    public record Fact(@NotBlank @Size(max=160) String subject,
                       @NotBlank @Size(max=80) String attribute,
                       @NotNull Kind kind, @NotBlank @Size(max=800) String content,
                       @NotNull Source source, @NotBlank @Size(min=3,max=800) String evidence,
                       @NotNull @Min(1) @Max(5) Integer importance,
                       @Min(1) @Max(3650) Integer ttlDays) {}
    public record View(UUID id,String subject,String attribute,Kind kind,String content,Source source,
                       int importance,Instant updatedAt,Instant expiresAt) {}
}
