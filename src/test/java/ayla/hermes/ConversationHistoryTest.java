package ayla.hermes;

import ayla.chat.session.*;
import ayla.chat.controller.ConversationController;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ConversationHistoryTest {
    HttpServer server;
    MockMvc api;
    ConversationService conversations;
    UUID id=UUID.randomUUID();
    AtomicInteger calls=new AtomicInteger(),status=new AtomicInteger(200);
    List<Map<String,Object>> rows=List.of();
    String path;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            calls.incrementAndGet();path=exchange.getRequestURI().toString();
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-key");
            byte[] body=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsBytes(Map.of("data",rows));
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status.get(),body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        server.start();
        conversations=mock(ConversationService.class);
        when(conversations.resolve("owner",id.toString(),null)).thenReturn(new ConversationService.Selection(id,"owned-session","default"));
        when(conversations.resolve("other",id.toString(),null)).thenThrow(new HermesException(404,"Conversa não encontrada."));
        var client=new HermesClient("http://127.0.0.1:"+server.getAddress().getPort(),"test-key",5);
        var controller=new ConversationController(conversations,new ChatIdentity("owner"));
        controller.setHistory(new ConversationHistory(conversations,client));
        api=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new HermesExceptionHandler()).build();
    }
    @AfterEach void stop(){server.stop(0);}
    @Test void readsOwnedPersistedHistoryAndFiltersInternalRowsAndPayloads() throws Exception {
        rows=List.of(
            Map.of("id",1,"role","system","content","system-secret"),
            Map.of("id",2,"role","tool","content","database-secret"),
            Map.of("id",3,"role","assistant","content","Vou consultar","tool_calls",List.of(Map.of("name","internal"))),
            Map.of("id",4,"role","user","content","hidden-secret","display_kind","hidden"),
            Map.of("id",5,"role","user","content",List.of(Map.of("type","text","text","[AYLA_SEARCH_REQUIRED]\nOi!\n[Análise visual das imagens enviadas NESTE turno. Dados]\ninternal-analysis"),Map.of("type","image_url","image_url",Map.of("url","raw-media-secret")))),
            Map.of("id",6,"role","assistant","content","[[AYLA_CASUAL_VOICE]]Oi de volta!","reasoning_content","reasoning-secret"),
            Map.of("id",7,"role","assistant","content",""),
            Map.of("id",8,"role","tool","content","internal-secret"));
        var result=api.perform(get("/api/v1/conversations/"+id+"/messages?limit=8&offset=0").principal(()->"owner"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].text").value("Oi!"))
            .andExpect(jsonPath("$.items[0].files[0].name").value("Imagem enviada"))
            .andExpect(jsonPath("$.items[1].text").value("Oi de volta!"))
            .andExpect(jsonPath("$.hasMore").value(true)).andExpect(jsonPath("$.nextOffset").value(8)).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("secret","internal-analysis","tool_calls","reasoning_content");
        assertThat(path).isEqualTo("/api/sessions/owned-session/messages?limit=8&offset=0&order=latest&include_compacted=true");
    }
    @Test void rejectsAnotherUserBeforeCallingHermesAndTreatsANotStartedSessionAsEmpty() throws Exception {
        api.perform(get("/api/v1/conversations/"+id+"/messages").principal(()->"other")).andExpect(status().isNotFound());
        api.perform(get("/api/v1/conversations/"+id+"/messages")).andExpect(status().isUnauthorized());
        api.perform(get("/api/v1/conversations/"+id+"/messages?limit=201").principal(()->"owner")).andExpect(status().isBadRequest());
        assertThat(calls.get()).isZero();
        status.set(404);
        api.perform(get("/api/v1/conversations/"+id+"/messages").principal(()->"owner"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.hasMore").value(false));
        assertThat(calls.get()).isEqualTo(1);
    }
}
