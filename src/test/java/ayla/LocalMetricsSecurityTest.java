package ayla;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import java.net.URI;
import java.net.http.*;
import static org.assertj.core.api.Assertions.*;

@Import(TestcontainersConfiguration.class)
@ActiveProfiles("observability-local")
@AutoConfigureMetrics
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "management.server.port=0","management.tracing.export.otlp.enabled=false",
        "logging.file.name=target/isolated-checks/metrics-tests.log",
        "ayla.metrics.scrape-key=synthetic-metrics-key-at-least-32-characters"})
class LocalMetricsSecurityTest {
    @Autowired Environment environment;
    @Test void collectorKeyOnlyOpensMetricsOnTheManagementPort()throws Exception{
        var client=HttpClient.newHttpClient();
        String management="http://127.0.0.1:"+environment.getRequiredProperty("local.management.port");
        var url=URI.create(management+"/actuator/prometheus");
        assertThat(client.send(HttpRequest.newBuilder(url).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
        assertThat(client.send(HttpRequest.newBuilder(url).header("Authorization","Bearer incorrect").GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
        var request=HttpRequest.newBuilder(url).header("Authorization","Bearer synthetic-metrics-key-at-least-32-characters").GET().build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);assertThat(response.body()).contains("jvm_memory_used_bytes");
        var finance=HttpRequest.newBuilder(URI.create(management+"/api/v1/finance/entries")).header("Authorization","Bearer synthetic-metrics-key-at-least-32-characters").GET().build();
        assertThat(client.send(finance,HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
    }
}
