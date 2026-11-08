package ayla.hermes;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ProviderUsageData {
    private ProviderUsageData() {}
    public record Quota(@NotBlank @Size(max=100) String label,
        @Pattern(regexp="DAILY|WEEKLY|MONTHLY|MINUTE|HOUR|SUBSCRIPTION|MODEL|UNKNOWN") String period,
        @DecimalMin("0") @DecimalMax("100") BigDecimal usagePercentage,
        @PositiveOrZero Long used, @Positive Long limit, @PositiveOrZero Long remaining,
        Instant resetsAt, @Pattern(regexp="ACCOUNT|MODEL|MODEL_FAMILY") String scope, @Size(max=255) String model) {}
    public record Event(@NotBlank @Pattern(regexp="[A-Za-z0-9_.:-]{1,128}") String eventId,
        @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String profile,
        @NotBlank @Pattern(regexp="[0-9a-f]{64}") String credentialScope,
        @NotBlank @Pattern(regexp="nous") String provider,
        @Size(max=255) String model,
        @NotNull @Pattern(regexp="REQUEST|AVAILABLE|RATE_LIMITED|WAITING_PROVIDER|ERROR|QUOTA|TOOL") String state,
        @NotNull Instant detectedAt, @NotNull Instant credentialObservedAt,
        @Min(0) @Max(31536000) Long retryAfterSeconds, Instant retryAttemptAt,
        @Size(max=12) List<@Size(max=255) String> alternates,
        @Size(max=20) List<@Valid Quota> quotas,
        @Pattern(regexp="KNOWN|UNAVAILABLE|ERROR") String quotaStatus,
        @Pattern(regexp="[A-Za-z0-9_-]{1,128}") String sessionId,
        @Size(max=24) Map<@Pattern(regexp="inputTokens|outputTokens|totalTokens|cacheReadTokens|cacheWriteTokens|approxInputTokens|systemBytes|toolBytes|historyBytes|memoryBytes|requestBytes|toolCount|retryCount|toolCalls|durationMs|httpStatus") String,@Min(0) @Max(9007199254740991L) Long> metrics) {
        public Event(String eventId,String profile,String credentialScope,String provider,String model,String state,Instant detectedAt,Instant credentialObservedAt,
                Long retryAfterSeconds,Instant retryAttemptAt,List<String> alternates,List<Quota> quotas,String quotaStatus,String sessionId){
            this(eventId,profile,credentialScope,provider,model,state,detectedAt,credentialObservedAt,retryAfterSeconds,retryAttemptAt,alternates,quotas,quotaStatus,sessionId,null);
        }
    }
    public record Availability(String provider,String model,String status,Instant detectedAt,Long retryAfterSeconds,
        Instant availableAt,Instant retryAttemptAt,List<String> alternates,Instant confirmedAt) {}
    public static class Limited extends HermesException {
        private final Map<String,Object> availability;
        public Limited(String message,Map<String,Object> availability){super(429,message);this.availability=availability;}
        public Map<String,Object> availability(){return availability;}
    }
}
