package ayla.monitoring;

import java.util.*;
import org.junit.jupiter.api.Test;
import ayla.auth.AuthData.Identity;
import ayla.chat.session.ChatIdentity;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiUsageAccessTest {
    @Test void onlyTheActualOwnerCanReadUsageAndStorageFailuresNeverBreakChat() {
        var usage=mock(AiUsageService.class);
        var controller=new MonitoringController(mock(MonitoringService.class),new ChatIdentity("owner"));
        controller.setAiUsage(usage);
        for(Identity user:Arrays.asList(null,new Identity("member","member","USER",UUID.randomUUID()),new Identity("other","other","OWNER",UUID.randomUUID()))) {
            assertThatThrownBy(()->controller.aiUsage(user,null,null,null,null)).isInstanceOfSatisfying(ResponseStatusException.class,
                    error->assertThat(error.getStatusCode().value()).isEqualTo(403));
            assertThatThrownBy(()->controller.aiCalls(user,null,null,null,null,0,25)).isInstanceOfSatisfying(ResponseStatusException.class,
                    error->assertThat(error.getStatusCode().value()).isEqualTo(403));
        }
        verifyNoInteractions(usage);
        when(usage.overview(null,null,null,null)).thenReturn(Map.of("totals",Map.of()));
        var response=controller.aiUsage(new Identity("owner","owner","OWNER",UUID.randomUUID()),null,null,null,null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        when(usage.calls(null,null,null,null,0,25)).thenReturn(Map.of("items",List.of(),"total",0));
        assertThat(controller.aiCalls(new Identity("owner","owner","OWNER",UUID.randomUUID()),null,null,null,null,0,25).getHeaders().getCacheControl()).contains("no-store");

        var unavailable=mock(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate.class);
        when(unavailable.queryForList(anyString(),anyMap(),eq(String.class))).thenThrow(new IllegalStateException("offline"));
        assertThatCode(()->new AiUsageService(unavailable).record("profile",Map.of(),null,502,1)).doesNotThrowAnyException();
    }
}
