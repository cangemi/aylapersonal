package ayla.whatsapp;

import ayla.chat.controller.ChatController;
import ayla.chat.service.ChatService;
import ayla.chat.session.ChatIdentity;
import ayla.chat.dto.*;
import ayla.hermes.HermesExceptionHandler;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WhatsappApiTest {
    @Test void connectionModesAndBothQrSessionsAreScopedToTheAuthenticatedUser() throws Exception {
        var gateway=mock(WhatsappGateway.class);
        when(gateway.write("PUT","/configuration",Map.of("mode","dedicated"),"alice")).thenReturn(Map.of("mode","dedicated","ready",false));
        when(gateway.get("/ayla/qr","alice")).thenReturn(Map.of("qrImageDataUrl","private-qr"));
        when(gateway.post("/ayla/connect","alice")).thenReturn(Map.of("state","connecting"));
        var api=MockMvcBuilders.standaloneSetup(new MyWhatsappController(gateway,new ChatIdentity("owner"))).build();
        api.perform(put("/api/v1/me/whatsapp/configuration").principal(()->"alice").contentType("application/json").content("{\"mode\":\"dedicated\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(false));
        api.perform(get("/api/v1/me/whatsapp/ayla/qr").principal(()->"alice"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        api.perform(post("/api/v1/me/whatsapp/ayla/connect").principal(()->"alice"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("connecting"));
        verify(gateway).get("/ayla/qr","alice");
        verify(gateway).post("/ayla/connect","alice");
        verify(gateway,never()).get("/ayla/qr","owner");
    }
    @Test void recoveryGatewaySerializesThePrivatePayloadWithoutSendingItThroughChat() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var captured=new java.util.concurrent.atomic.AtomicReference<java.util.Map>();
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
        String key="recovery-test-key-0123456789-0123456789";
        server.createContext("/internal/password-recovery",exchange->{
            org.assertj.core.api.Assertions.assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer "+key);
            captured.set(mapper.readValue(exchange.getRequestBody().readAllBytes(),java.util.Map.class));
            byte[] bytes="{\"sent\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();
        try {
            var gateway=new WhatsappGateway("http://127.0.0.1:"+server.getAddress().getPort(),key);
            gateway.sendRecovery("owner","5511888888888","a".repeat(24),"00000000-0000-4000-8000-000000000001");
            org.assertj.core.api.Assertions.assertThat(captured.get()).containsEntry("userId","owner").containsEntry("password","a".repeat(24)).doesNotContainKey("phone");
        }finally{server.stop(0);}
    }
    @Test void trustedContactSelectsStoredOwnerAndConversationInsteadOfRequestBody() throws Exception {
        var chat=mock(ChatService.class); var access=mock(WhatsappAccess.class);
        UUID contact=UUID.randomUUID(), conversation=UUID.randomUUID();
        var image=new ChatImage("image/jpeg","/9j/4AAA");
        when(access.resolve("Bearer node-key",contact.toString(),null)).thenReturn(new WhatsappAccess.Access("owner",conversation));
        when(chat.chat(any(),eq("owner"))).thenReturn(new ChatResponse("Oi!","session"));
        var api=MockMvcBuilders.standaloneSetup(new ChatController(chat,new ChatIdentity("owner"),access)).build();
        api.perform(post("/api/v1/chat").header("Authorization","Bearer node-key").header("X-Ayla-WhatsApp-Chat",contact)
            .contentType("application/json").content(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(Map.of("message","Oi","sessionId","untrusted","conversationId",UUID.randomUUID().toString(),"images",List.of(image)))))
            .andExpect(status().isOk());
        verify(chat).chat(new ChatRequest("Oi",null,conversation.toString(),null,List.of(image)),"owner");
    }
    @Test void connectionUsesTheAuthenticatedUsersInstanceAndQrIsNeverCached() throws Exception {
        var gateway=mock(WhatsappGateway.class);
        when(gateway.get("/qr","owner")).thenReturn(Map.of("qrImageDataUrl","data:image/png;base64,test"));
        when(gateway.get("/qr","other")).thenReturn(Map.of("qrImageDataUrl","data:image/png;base64,other"));
        var api=MockMvcBuilders.standaloneSetup(new WhatsappController(gateway,new ChatIdentity("owner")))
            .setControllerAdvice(new WhatsappExceptionHandler(),new HermesExceptionHandler()).build();
        api.perform(get("/api/v1/whatsapp/qr").principal(()->"other")).andExpect(status().isOk()).andExpect(jsonPath("$.qrImageDataUrl").value("data:image/png;base64,other"));
        verify(gateway).get("/qr","other");
        verify(gateway,never()).get("/qr");
        when(gateway.post("/personal-chat","owner")).thenReturn(Map.of("created",true));
        api.perform(post("/api/v1/whatsapp/personal-chat").principal(()->"owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.created").value(true));
        api.perform(get("/api/v1/whatsapp/qr").principal(()->"owner")).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.qrImageDataUrl").exists());
    }
}

