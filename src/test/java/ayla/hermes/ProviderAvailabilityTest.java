package ayla.hermes;

import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProviderAvailabilityTest {
    final Instant now=Instant.parse("2026-11-04T22:00:00Z");
    @Test void extractsStructuredProviderDelayWithoutInventingRetrySchedule(){
        var limit=ProviderLimitParser.parse("{\"error\":{\"reason\":\"rate_limited\",\"retry_after\":1645,\"alternates\":[\"nous/model:free\",\"<script>\"]}}",null,now);
        assertThat(limit.providerConfirmed()).isTrue();assertThat(limit.seconds()).isEqualTo(1645);
        assertThat(limit.alternates()).containsExactly("nous/model:free");
    }
    @Test void unknownGateway429IsNotClaimedAsNousQuota(){
        var limit=ProviderLimitParser.parse("{\"detail\":\"busy\"}",null,now);
        assertThat(limit.providerConfirmed()).isFalse();assertThat(limit.seconds()).isNull();
        assertThat(ProviderLimitParser.parse("{\"reason\":\"rate_limited\"}",null,now).seconds()).isNull();
    }
    @Test void sdkMessageWrappedByHermesRetainsTheRealProviderDeadline(){
        var limit=ProviderLimitParser.parse("{\"error\":{\"message\":\"Error code: 429 - {'status':429,'reason':'rate_limited','retry_after':5812,'alternates':['other-model:free']}\"}}",null,now);
        assertThat(limit.providerConfirmed()).isTrue();
        assertThat(limit.seconds()).isEqualTo(5812);
        assertThat(limit.alternates()).containsExactly("other-model:free");
    }
    @Test void supportsBothRetryAfterFormsAndRejectsBogusDelays(){
        assertThat(ProviderLimitParser.parse("{}","61",now).seconds()).isEqualTo(61);
        assertThat(ProviderLimitParser.parse("{}","Wed, 4 Nov 2026 22:01:00 GMT",now).seconds()).isEqualTo(60);
        for(String value:List.of("-1","NaN","Infinity","31536001","true"))assertThat(ProviderLimitParser.seconds(value,now)).isNull();
    }
    @Test void expiryRequiresSuccessBeforeGreen(){
        assertThat(ModelAvailabilityService.status("RATE_LIMITED",now.plusSeconds(1),now)).isEqualTo("RATE_LIMITED");
        assertThat(ModelAvailabilityService.status("RATE_LIMITED",now,now)).isEqualTo("PENDING_CONFIRMATION");
        assertThat(ModelAvailabilityService.status("WAITING_PROVIDER",null,now)).isEqualTo("WAITING_PROVIDER");
        assertThat(ModelAvailabilityService.status("AVAILABLE",null,now)).isEqualTo("AVAILABLE");
    }
    @Test void onlyKnownQuotasCanYieldPercentages(){
        var unknown=new ProviderUsageData.Quota("Tokens observados","UNKNOWN",null,1000L,null,null,null,"ACCOUNT",null);
        var real=new ProviderUsageData.Quota("Cota diária","DAILY",null,25L,100L,75L,null,"ACCOUNT",null);
        assertThat(ModelAvailabilityService.validQuotas(List.of(unknown,real))).singleElement().satisfies(q->assertThat(q.usagePercentage()).isEqualByComparingTo(new BigDecimal("25")));
    }
}
