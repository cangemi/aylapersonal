package ayla.hermes;

import ayla.chat.controller.ChatController;
import ayla.chat.controller.ConversationController;
import ayla.chat.session.*;
import ayla.chat.dto.*;
import ayla.chat.service.ChatService;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HermesApiTest {
    MockMvc api;
    ChatService chat;
    HermesSettingsService settings;
    HermesModels models;
    HermesClient client;
    ConversationService conversations;
    @BeforeEach void setup() {
        chat = mock(ChatService.class);
        settings = mock(HermesSettingsService.class);
        models = mock(HermesModels.class);
        client = mock(HermesClient.class);
        conversations = mock(ConversationService.class);
        when(settings.get()).thenReturn(new HermesConfiguration());
        var identity = new ChatIdentity("local-owner");
        api = MockMvcBuilders.standaloneSetup(new ChatController(chat, identity),
                new ConversationController(conversations, identity), new HermesController(models, settings, client))
                .setControllerAdvice(new HermesExceptionHandler()).defaultRequest(get("/").principal(()->"local-owner")).build();
    }
    @Test void keepsOriginalMessageContractAndReturnsSessionId() throws Exception {
        when(chat.chat(new ChatRequest("Olá", null), "local-owner")).thenReturn(new ChatResponse("Olá!", "session-1"));
        api.perform(post("/api/v1/chat").contentType("application/json").content("{\"message\":\"Olá\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response").value("Olá!"))
                .andExpect(jsonPath("$.sessionId").value("session-1"));
    }
    @Test void rejectsBlankMessageAndUnsafeSessionWithoutCallingHermes() throws Exception {
        for (String body : List.of("{\"message\":\" \"}", "{\"message\":\"Olá\",\"sessionId\":\"../etc/passwd\"}"))
            api.perform(post("/api/v1/chat").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(chat);
    }
    @Test void configurationClearlyShowsAutomaticFreePrimary() throws Exception {
        api.perform(get("/api/v1/hermes/configuration")).andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("nous")).andExpect(jsonPath("$.freeOnly").value(true));
    }
    @Test void updatesModelThroughJavaApi() throws Exception {
        var config = new HermesConfiguration();
        config.setModel("free-model");
        when(settings.update("free-model", "Seja breve")).thenReturn(config);
        api.perform(put("/api/v1/hermes/configuration").contentType("application/json")
                .content("{\"model\":\"free-model\",\"instructions\":\"Seja breve\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.model").value("free-model"));
    }
    @Test void statusExplainsMissingFreeModelInsteadOfClaimingReady() throws Exception {
        when(models.select(null)).thenThrow(new HermesException(503, "Faça login no Hermes."));
        api.perform(get("/api/v1/hermes/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true)).andExpect(jsonPath("$.ready").value(false));
    }
    @Test void chatReturnsActionableHermesError() throws Exception {
        when(chat.chat(any(), anyString())).thenThrow(new HermesException(503, "Nenhum modelo gratuito disponível."));
        api.perform(post("/api/v1/chat").contentType("application/json").content("{\"message\":\"Olá\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.provider").value("hermes"));
    }
    @Test void authenticatedUserComesFromPrincipalAndCannotBeSelectedInBody() throws Exception {
        when(chat.chat(any(), eq("alice"))).thenReturn(new ChatResponse("Olá", "session"));
        api.perform(post("/api/v1/chat").principal(() -> "alice").contentType("application/json")
                .content("{\"message\":\"Olá\",\"userId\":\"bob\"}"))
                .andExpect(status().isOk());
        verify(chat).chat(new ChatRequest("Olá", null), "alice");
    }
    @Test void acceptsDocumentAndQuotedContextWithoutTreatingTheQuoteAsTheCurrentRequest() throws Exception {
        when(chat.chat(any(),eq("local-owner"))).thenReturn(new ChatResponse("Analisado","session"));
        api.perform(post("/api/v1/chat").contentType("application/json").content("""
                {"message":"Anote esses itens na lista","documents":[{"mimeType":"text/plain","name":"lista.txt","data":"YXJyb3ogZSBsZWl0ZQ=="}],
                "whatsappContext":{"requestedCount":10,"messages":[],"quoted":{"author":"Outro participante","owner":false,"text":"arroz e leite","type":"chat","timestamp":1}}}
                """)).andExpect(status().isOk());
        var captured=org.mockito.ArgumentCaptor.forClass(ChatRequest.class);
        verify(chat).chat(captured.capture(),eq("local-owner"));
        org.assertj.core.api.Assertions.assertThat(captured.getValue().documents()).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(captured.getValue().whatsappContext().quoted().text()).isEqualTo("arroz e leite");
        org.assertj.core.api.Assertions.assertThat(captured.getValue().message()).isEqualTo("Anote esses itens na lista");
    }
    @Test void conversationRoutesUseCurrentUserAndReturnStableConversationId() throws Exception {
        var id = java.util.UUID.randomUUID();
        var conversation = new ConversationService.View(id, id.toString(), "Jogos", java.time.Instant.now(), true);
        when(conversations.newConversation("local-owner", "Jogos")).thenReturn(conversation);
        when(conversations.list("local-owner")).thenReturn(List.of(conversation));
        when(conversations.activate("local-owner", id)).thenReturn(conversation);
        api.perform(post("/api/v1/conversations").contentType("application/json").content("{\"title\":\"Jogos\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.conversationId").value(id.toString()));
        api.perform(get("/api/v1/conversations")).andExpect(status().isOk()).andExpect(jsonPath("$[0].current").value(true));
        api.perform(put("/api/v1/conversations/" + id + "/current")).andExpect(status().isOk());
    }
}
