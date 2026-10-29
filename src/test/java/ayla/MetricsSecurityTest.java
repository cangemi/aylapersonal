package ayla;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;

@Import(TestcontainersConfiguration.class)
@ActiveProfiles("vps")
@AutoConfigureMetrics
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"management.server.port=0","management.tracing.export.otlp.enabled=false"})
class MetricsSecurityTest {
    @Autowired org.springframework.core.env.Environment environment;
    @Test void metricsAreOnlyAccessibleOnThePrivateManagementListener()throws Exception{
        var client=java.net.http.HttpClient.newHttpClient();
        String api="http://127.0.0.1:"+environment.getRequiredProperty("local.server.port");
        String management="http://127.0.0.1:"+environment.getRequiredProperty("local.management.port");
        var main=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(api+"/actuator/prometheus")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        org.assertj.core.api.Assertions.assertThat(main.statusCode()).isEqualTo(401);
        var ready=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(api+"/readyz")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        org.assertj.core.api.Assertions.assertThat(ready.statusCode()).isEqualTo(200);
        var metrics=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(management+"/actuator/prometheus")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        org.assertj.core.api.Assertions.assertThat(metrics.statusCode()).isEqualTo(200);
        org.assertj.core.api.Assertions.assertThat(metrics.body()).contains("jvm_memory_used_bytes");
        var blocked=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(management+"/api/v1/finance/entries")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        org.assertj.core.api.Assertions.assertThat(blocked.statusCode()).isEqualTo(403);
    }
}
