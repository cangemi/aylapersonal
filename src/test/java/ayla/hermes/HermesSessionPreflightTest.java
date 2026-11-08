package ayla.hermes;

import ayla.chat.queue.ChatJobContext;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HermesSessionPreflightTest {
    @Test void pausedSessionIsNeverDispatchedAndDoesNotCreateAnotherGatewayAdmission() throws Exception {
        var calls=new AtomicInteger();var barrier=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{calls.incrementAndGet();exchange.sendResponseHeaders(500,-1);exchange.close();});
        server.start();
        try {
            var dashboard=mock(HermesDashboardClient.class);var usage=mock(ayla.monitoring.AiUsageService.class);
            var client=new HermesClient("http://127.0.0.1:"+server.getAddress().getPort(),"key",2);
            client.setSessionStateReader(dashboard);client.setUsage(usage);
            doThrow(new HermesSessionPausedException()).when(dashboard).requireSessionReady("default","session-a");
            try(var context=new ChatJobContext(barrier::incrementAndGet)){
                assertThatThrownBy(()->client.post("/api/sessions/session-a/chat",Map.of("message","gastei 10 reais")))
                    .isInstanceOf(HermesSessionPausedException.class);
                assertThat(context.dispatched()).isFalse();
            }
            assertThat(calls.get()).isZero();assertThat(barrier.get()).isZero();verifyNoInteractions(usage);
        } finally {server.stop(0);}
    }
    @Test void readySessionCanCallTheGatewayNormally() throws Exception {
        var calls=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            calls.incrementAndGet();exchange.getRequestBody().readAllBytes();
            byte[] response="{\"status\":\"completed\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });
        server.start();
        try {
            var dashboard=mock(HermesDashboardClient.class);
            var client=new HermesClient("http://127.0.0.1:"+server.getAddress().getPort(),"key",2);client.setSessionStateReader(dashboard);
            assertThat(client.post("/api/sessions/session-a/chat",Map.of("message","oi"))).containsEntry("status","completed");
            verify(dashboard).requireSessionReady("default","session-a");assertThat(calls.get()).isEqualTo(1);
        } finally {server.stop(0);}
    }
    @Test void dashboardStateMustExplicitlyConfirmReadiness(){
        var client=new HermesDashboardClient("http://unused","ayla","test","/opt/data"){
            @Override public Map<String,Object> admin(String method,String path,Map<String,Object> body){return Map.of("state","PAUSED");}
        };
        org.springframework.test.util.ReflectionTestUtils.setField(client,"bridgeKey","a".repeat(32));
        assertThatThrownBy(()->client.requireSessionReady("profile-a","session-a")).isInstanceOf(HermesSessionPausedException.class);
    }
}
