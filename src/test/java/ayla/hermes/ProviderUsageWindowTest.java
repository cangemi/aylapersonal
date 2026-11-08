package ayla.hermes;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProviderUsageWindowTest {
    @Test void dayAndMonthUseBrasiliaBoundariesRatherThanUtcMidnight(){
        var now=Instant.parse("2026-11-08T02:30:00Z");
        assertThat(ProviderUsageService.usageWindow("DAY",now).from()).isEqualTo(Instant.parse("2026-11-07T03:00:00Z"));
        assertThat(ProviderUsageService.usageWindow("MONTH",now).from()).isEqualTo(Instant.parse("2026-11-01T03:00:00Z"));
        assertThat(ProviderUsageService.usageWindow("24H",now).from()).isEqualTo(Instant.parse("2026-11-07T02:30:00Z"));
        assertThat(ProviderUsageService.usageWindow("7D",now).from()).isEqualTo(Instant.parse("2026-11-01T02:30:00Z"));
        assertThatThrownBy(()->ProviderUsageService.usageWindow("invalid",now)).isInstanceOf(HermesException.class);
    }
}
