package ayla.hermes;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ayla.monitoring.AiUsageService;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HermesUsageCaptureTest {
    @Test void recordsEachChatAttemptOnceIncludingFailuresButSkipsSessionReads() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/sessions/",exchange->{
            String result=exchange.getRequestURI().getPath().contains("failure")?"{\"status\":\"failed\",\"usage\":{\"total_tokens\":7}}"
                    :"{\"status\":\"completed\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"total_tokens\":15}}";
            exchange.getRequestBody().readAllBytes();
            byte[] payload=result.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,payload.length);
            try(var output=exchange.getResponseBody()){output.write(payload);}
        });
        server.start();
        try {
            var usage=mock(AiUsageService.class);
            var client=new HermesClient("http://127.0.0.1:"+server.getAddress().getPort(),"test-key",5);
            client.setUsage(usage);
            client.get("/api/sessions/example");
            var request=Map.<String,Object>of("message","not captured by monitoring");
            client.post("/api/sessions/example/chat",request);
            client.post("/api/sessions/failure/chat",request);
            verify(usage).record(eq("default"),same(request),argThat(value->"completed".equals(value.get("status"))),eq(200),anyLong());
            verify(usage).record(eq("default"),same(request),argThat(value->"failed".equals(value.get("status"))),eq(502),anyLong());
            verifyNoMoreInteractions(usage);
        } finally {server.stop(0);}
    }
}
