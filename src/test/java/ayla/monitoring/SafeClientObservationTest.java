package ayla.monitoring;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.mock.http.client.MockClientHttpRequest;
import static org.assertj.core.api.Assertions.*;

class SafeClientObservationTest {
    @Test void outboundMetricsAndTracesHideProfileSessionAndQueryParameters(){
        var request=new MockClientHttpRequest(HttpMethod.POST,URI.create("http://hermes:8642/p/private-user/api/sessions/private-session/chat?secret=value"));
        var context=new ClientRequestObservationContext(request);
        var convention=new SafeClientObservation();
        assertThat(convention.getLowCardinalityKeyValues(context).stream().filter(k->k.getKey().equals("uri")).findFirst().orElseThrow().getValue()).isEqualTo("/p/{profile}/api/sessions/{session}/chat");
        assertThat(convention.getHighCardinalityKeyValues(context).toString()).doesNotContain("private-user","private-session","secret","value");
    }
}
