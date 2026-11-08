package ayla.hermes;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProviderLimitHttpTest {
    @Test void gateway429StopsAfterOneCallAndRetainsProviderMetadata() throws Exception {
        var calls=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            calls.incrementAndGet();exchange.getRequestBody().readAllBytes();
            byte[] body="{\"reason\":\"rate_limited\",\"retry_after\":1645}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.getResponseHeaders().set("Retry-After","1645");
            exchange.sendResponseHeaders(429,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        server.start();
        try{
            var client=new HermesClient("http://127.0.0.1:"+server.getAddress().getPort(),"test-key",2);
            var availability=mock(ModelAvailabilityService.class);client.setAvailability(availability);
            when(availability.rejected(eq("default"),eq("model-a"),anyString(),eq("1645"),eq("session-one")))
                .thenReturn(new ProviderUsageData.Limited("Modelo limitado",Map.of("retryAfterSeconds",1645)));
            try(var context=new ayla.chat.queue.ChatJobContext(()->{},()->{})){
                assertThatThrownBy(()->client.post("/api/sessions/session-one/chat",Map.of("model","model-a","message","Teste")))
                    .isInstanceOfSatisfying(ProviderUsageData.Limited.class,e->assertThat(e.availability()).containsEntry("retryAfterSeconds",1645));
            }
            assertThat(calls.get()).isEqualTo(1);
        }finally{server.stop(0);}
    }
}
