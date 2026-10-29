package ayla.hermes;

import ayla.chat.dto.ChatAudio;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HermesMediaTest {
    @Test void nativeDashboardUsesSessionCookieLocalSttAndOnlyDeletesExactCacheFile() throws Exception {
        var json=JsonMapper.builder().build();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        List<Map<String,Object>> bodies=new ArrayList<>();
        server.createContext("/",e->{
            assertThat(e.getRequestHeaders().getFirst("Upgrade")).isNull();
            assertThat(e.getProtocol()).isEqualTo("HTTP/1.1");
            Map<String,Object> body=json.readValue(e.getRequestBody().readAllBytes(),Map.class);
            bodies.add(body);
            Object result;
            if(e.getRequestURI().getPath().equals("/auth/password-login")) {
                assertThat(body).containsEntry("provider","basic");
                e.getResponseHeaders().add("Set-Cookie","session=test; Path=/; HttpOnly");
                result=Map.of("ok",true);
            } else {
                assertThat(e.getRequestHeaders().getFirst("Cookie")).contains("session=test");
                result=e.getRequestMethod().equals("DELETE")?Map.of("ok",true):
                    e.getRequestURI().getPath().endsWith("/speak")?Map.of("ok",true,"provider","edge","mime_type","audio/ogg","data_url","data:audio/ogg;base64,T2dnUw=="):
                    Map.of("ok",true,"provider","local","transcript","Gastei dez reais no café");
            }
            byte[] bytes=json.writeValueAsString(result).getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();
        });
        server.start();
        try {
            var dashboard=new HermesDashboardClient("http://127.0.0.1:"+server.getAddress().getPort(),"ayla","test-password","/opt/data");
            assertThat(dashboard.transcribe("default",new ChatAudio("audio/ogg","T2dnUw=="))).isEqualTo("Gastei dez reais no café");
            assertThat(dashboard.speak("default","Oi!")).isEqualTo(new ChatAudio("audio/ogg","T2dnUw=="));
            String name="api_"+"a".repeat(32)+".jpg";
            dashboard.deleteImageCache("default",name);
            assertThat(bodies.getLast()).containsEntry("recursive",false).containsEntry("path","/opt/data/cache/images/"+name);
            assertThatThrownBy(()->dashboard.deleteImageCache("default","../config.yaml")).isInstanceOf(IllegalArgumentException.class);
            assertThat(bodies).hasSize(4);
        } finally {server.stop(0);}
    }
    @Test void failedCleanupKeepsDurableJobUntilRetrySucceedsEvenIfSessionAlreadyDeleted() {
        var jdbc=mock(JdbcTemplate.class);var client=mock(HermesClient.class);var dashboard=mock(HermesDashboardClient.class);
        var cleanup=new HermesMediaCleanup(jdbc,client,dashboard);
        String session="ayla-media-"+UUID.randomUUID(),name="api_"+"b".repeat(32)+".png";
        doThrow(new HermesException(503,"offline")).doNothing().when(dashboard).deleteImageCache("default",name);
        cleanup.clean("default",session,List.of(name));
        verifyNoInteractions(jdbc);
        when(client.delete("default","/api/sessions/"+session)).thenThrow(new HermesException(404,"deleted"));
        cleanup.clean("default",session,List.of(name));
        verify(jdbc).update("delete from hermes_media_cleanup where session_id=?",session);
    }
}


