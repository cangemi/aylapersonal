package ayla.hermes;
import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
class HermesSessionMetadataCacheTest {
 @Test void cachesOnlySessionExistencePerProfileAndInvalidatesOnDelete() throws Exception {
  var reads=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/",exchange->{
   if(exchange.getRequestMethod().equals("DELETE")){exchange.sendResponseHeaders(204,-1);exchange.close();return;}
   reads.incrementAndGet();byte[] body="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
   exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
  });server.start();
  try {
   var env=new MockEnvironment().withProperty("ayla.hermes.profiles.user-a.api-key","profile-test-key");
   var client=new HermesClient("http://127.0.0.1:"+server.getAddress().getPort(),"default-test-key",5,env);
   client.ensureSession("default","same-session",Map.of());client.ensureSession("default","same-session",Map.of());
   assertThat(reads.get()).isEqualTo(1);
   client.ensureSession("user-a","same-session",Map.of());assertThat(reads.get()).isEqualTo(2);
   client.delete("default","/api/sessions/same-session");client.ensureSession("default","same-session",Map.of());
   assertThat(reads.get()).isEqualTo(3);
  }finally{server.stop(0);}
 }
}
