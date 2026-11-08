package ayla.hermes;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HermesDashboardClientTest {
    @Test void refusedConnectionExplainsThatTheDashboardMustFinishStarting() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        var client = new HermesDashboardClient("http://127.0.0.1:" + port, "ayla", "test", "/opt/data");
        assertThatThrownBy(() -> client.admin("POST", "/api/providers/oauth/nous/start", null))
                .isInstanceOfSatisfying(HermesException.class, e -> {
                    assertThat(e.status()).isEqualTo(503);
                    assertThat(e.getMessage()).contains("conexão", "terminou de iniciar", "HERMES_DASHBOARD_URL");
                });
    }

    @Test void invalidResponseIsNotReportedAsAnUnreachableDashboard() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Set-Cookie", "session=test; Path=/; HttpOnly");
            byte[] body = "<html>unexpected response</html>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var client = new HermesDashboardClient("http://127.0.0.1:" + server.getAddress().getPort(), "ayla", "test", "/opt/data");
            assertThatThrownBy(() -> client.admin("POST", "/api/providers/oauth/nous/start", null))
                    .isInstanceOfSatisfying(HermesException.class, e -> {
                        assertThat(e.status()).isEqualTo(502);
                        assertThat(e.getMessage()).contains("resposta inválida");
                    });
        } finally { server.stop(0); }
    }
}
