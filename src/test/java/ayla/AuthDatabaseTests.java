package ayla;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import ayla.auth.*;
import ayla.auth.AuthData.*;
import ayla.chat.dto.ChatResponse;
import ayla.chat.service.ChatService;
import ayla.chat.session.ConversationService;
import ayla.finance.*;
import ayla.whatsapp.WhatsappGateway;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties={"ayla.chat.owner-id=auth-owner","ayla.auth.setup-key=setup-test-key-0123456789-0123456789",
        "ayla.whatsapp.api-key=whatsapp-test-key-0123456789-0123456789","ayla.finance.bridge-key=hermes-test-key-0123456789-0123456789"})
class AuthDatabaseTests {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthService auth;
    @Autowired FinanceService finance;
    @Autowired ConversationService conversations;
    @MockitoBean ChatService chat;
    @MockitoBean WhatsappGateway whatsapp;
    MockMvc api;
    final JsonMapper json=JsonMapper.builder().build();
    final String password="synthetic-test-password";
    @BeforeEach void prepare() {
        jdbc.update("delete from auth_refresh_tokens");jdbc.update("delete from auth_sessions");
        jdbc.update("delete from auth_login_throttle");jdbc.update("delete from auth_accounts");
        jdbc.update("delete from auth_recovery_limits");
        jdbc.update("delete from application_events");
        jdbc.update("update whatsapp_instances set state='disconnected' where owner_user_id='auth-owner'");
        api=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",Filter.class)).build();
    }
    Tokens login(String username,String password) throws Exception {
        var response=api.perform(post("/api/v1/auth/login").contentType("application/json")
                .content(json.writeValueAsString(Map.of("username",username,"password",password))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store"))).andReturn();
        return json.readValue(response.getResponse().getContentAsString(),Tokens.class);
    }
    Tokens owner() throws Exception {auth.setup("setup-test-key-0123456789-0123456789",new Credentials("Pedro",password));return login("PEDRO",password);}

    @Test void inactiveUsersKeepTheirDataButLoseSessionsWhatsappAndAgentToolAccess() throws Exception {
        var admin=owner();var user=auth.createUser(new Credentials("inactive-test@example.test",password));
        var token=login(user.username(),password);
        var conversation=conversations.newConversation(user.id(),"Conversa preservada");
        api.perform(patch("/api/v1/auth/users/"+user.id()+"/status").header("Authorization","Bearer "+token.accessToken()).contentType("application/json").content("{\"enabled\":false}"))
                .andExpect(status().isForbidden());
        api.perform(patch("/api/v1/auth/users/"+user.id()+"/status").header("Authorization","Bearer "+admin.accessToken()).contentType("application/json").content("{\"enabled\":false}"))
                .andExpect(status().isNoContent());
        verify(whatsapp).post("/disconnect",user.id());
        assertThat(jdbc.queryForObject("select count(*) from chat_conversations where user_id=?",Long.class,user.id())).isEqualTo(1);
        api.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token.accessToken())).andExpect(status().isUnauthorized());
        api.perform(post("/api/v1/auth/refresh").contentType("application/json").content(json.writeValueAsString(Map.of("refreshToken",token.refreshToken())))).andExpect(status().isUnauthorized());
        api.perform(post("/internal/hermes/finance/list").header("Authorization","Bearer hermes-test-key-0123456789-0123456789").header("X-Ayla-Session",conversation.sessionId())
                .contentType("application/json").content("{}")).andExpect(status().isForbidden());
        api.perform(patch("/api/v1/auth/users/"+admin.user().id()+"/status").header("Authorization","Bearer "+admin.accessToken()).contentType("application/json").content("{\"enabled\":false}"))
                .andExpect(status().isForbidden());
        api.perform(patch("/api/v1/auth/users/"+user.id()+"/status").header("Authorization","Bearer "+admin.accessToken()).contentType("application/json").content("{\"enabled\":true}"))
                .andExpect(status().isNoContent());
        api.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token.accessToken())).andExpect(status().isUnauthorized());
        assertThat(login(user.username(),password).user().id()).isEqualTo(user.id());
        api.perform(delete("/api/v1/auth/users/"+user.id()).header("Authorization","Bearer "+admin.accessToken())).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from auth_accounts where user_id=?",Long.class,user.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from chat_conversations where user_id=?",Long.class,user.id())).isZero();
        api.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+admin.accessToken())).andExpect(status().isOk());
    }

    @Test void superadminListsUsersAndResetsOnlyTheSelectedAccountsCredentialsAndSessions() throws Exception {
        var admin=owner();var target=auth.createUser(new Credentials("resettable@example.test",password));
        var untouched=auth.createUser(new Credentials("untouched@example.test",password));
        var old=login(target.username(),password);var other=login(untouched.username(),password);
        api.perform(get("/api/v1/auth/users").header("Authorization","Bearer "+old.accessToken())).andExpect(status().isForbidden());
        var list=api.perform(get("/api/v1/auth/users").header("Authorization","Bearer "+admin.accessToken()).param("q","resettable"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].username").value(target.username())).andReturn();
        assertThat(list.getResponse().getContentAsString()).doesNotContain("password","recovery","accessToken","refreshToken");
        String newPassword="new-reset-synthetic-password";
        String body=json.writeValueAsString(Map.of("newPassword",newPassword));
        api.perform(post("/api/v1/auth/users/"+target.id()+"/reset-password").header("Authorization","Bearer "+other.accessToken())
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        api.perform(post("/api/v1/auth/users/"+target.id()+"/reset-password").header("Authorization","Bearer "+admin.accessToken())
                .contentType("application/json").content("{\"newPassword\":\"short\"}")).andExpect(status().isBadRequest());
        api.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+old.accessToken())).andExpect(status().isOk());
        jdbc.update("update auth_accounts set recovery_password_hash='pending',recovery_expires_at=now()+interval '15 minutes' where user_id=?",target.id());
        api.perform(post("/api/v1/auth/users/"+target.id()+"/reset-password").header("Authorization","Bearer "+admin.accessToken())
                .contentType("application/json").content(body)).andExpect(status().isNoContent());
        api.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+old.accessToken())).andExpect(status().isUnauthorized());
        api.perform(post("/api/v1/auth/refresh").contentType("application/json").content(json.writeValueAsString(Map.of("refreshToken",old.refreshToken()))))
                .andExpect(status().isUnauthorized());
        api.perform(post("/api/v1/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("username",target.username(),"password",password))))
                .andExpect(status().isUnauthorized());
        assertThat(login(target.username(),newPassword).user().id()).isEqualTo(target.id());
        assertThat(jdbc.queryForObject("select recovery_password_hash from auth_accounts where user_id=?",String.class,target.id())).isNull();
        api.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+admin.accessToken())).andExpect(status().isOk());
        api.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+other.accessToken())).andExpect(status().isOk());
        assertThat(json.writeValueAsString(jdbc.queryForList("select * from application_events"))).doesNotContain(newPassword);
        api.perform(post("/api/v1/auth/users/"+admin.user().id()+"/reset-password").header("Authorization","Bearer "+admin.accessToken())
                .contentType("application/json").content(body)).andExpect(status().isNoContent());
        api.perform(get("/api/v1/auth/users").header("Authorization","Bearer "+admin.accessToken())).andExpect(status().isUnauthorized());
        assertThat(login(admin.user().username(),newPassword).user().role()).isEqualTo("OWNER");
    }

    @Test void monitoringIsOwnerOnlyTracksAllUsersAndOmitsSecretsWhileKeepingErrorLocations() throws Exception {
        var admin=owner();var additional=auth.createUser(new Credentials("other-monitor@example.test",password));
        var member=login("other-monitor@example.test",password);
        api.perform(get("/api/v1/finance/entries").header("Authorization","Bearer "+member.accessToken()))
                .andExpect(status().isOk()).andExpect(header().exists("X-Ayla-Request-Id"));
        String secret="private-description-must-not-be-stored";
        api.perform(post("/api/v1/finance/entries").header("Authorization","Bearer "+member.accessToken()).contentType("application/json")
                .content("{\"type\":\"EXPENSE\",\"amountCents\":\"12.5\",\"description\":\""+secret+"\"}"))
                .andExpect(status().isBadRequest());
        api.perform(get("/api/v1/monitoring/events").header("Authorization","Bearer "+member.accessToken())).andExpect(status().isForbidden());
        api.perform(get("/api/v1/monitoring")).andExpect(status().isUnauthorized());
        when(whatsapp.get("/status","auth-owner")).thenThrow(new ayla.whatsapp.WhatsappException(503,secret));
        api.perform(get("/api/v1/whatsapp/status").header("Authorization","Bearer "+admin.accessToken())).andExpect(status().isServiceUnavailable());
        var overview=api.perform(get("/api/v1/monitoring").header("Authorization","Bearer "+admin.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totals.server_errors").value(1)).andReturn();
        var listed=api.perform(get("/api/v1/monitoring/events").header("Authorization","Bearer "+admin.accessToken()).param("userId",additional.id()).param("outcome","ERROR"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2)).andReturn();
        UUID failed=jdbc.queryForObject("select id from application_events where status=503",UUID.class);
        var details=api.perform(get("/api/v1/monitoring/events/"+failed).header("Authorization","Bearer "+admin.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.error_type").value("WhatsappException"))
                .andExpect(jsonPath("$.stack_summary").value(org.hamcrest.Matchers.containsString("WhatsappController"))).andReturn();
        String stored=json.writeValueAsString(jdbc.queryForList("select * from application_events"));
        assertThat(stored+listed.getResponse().getContentAsString()+details.getResponse().getContentAsString()+overview.getResponse().getContentAsString())
                .doesNotContain(secret,member.accessToken(),admin.refreshToken(),password);
        api.perform(get("/api/v1/monitoring/events/"+failed).header("Authorization","Bearer "+member.accessToken())).andExpect(status().isForbidden());
        api.perform(post("/internal/monitoring/whatsapp").header("Authorization","Bearer "+member.accessToken()).contentType("application/json").content("{\"stage\":\"AUDIO_DOWNLOAD\"}"))
                .andExpect(status().isUnauthorized());
        api.perform(post("/internal/monitoring/whatsapp").header("Authorization","Bearer whatsapp-test-key-0123456789-0123456789").contentType("application/json").content("{\"stage\":\"AUDIO_DOWNLOAD\"}"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from application_events where source='WHATSAPP' and user_id='auth-owner' and action='Baixar áudio no WhatsApp'",Long.class)).isEqualTo(1);
        api.perform(get("/api/v1/monitoring").header("Authorization","Bearer "+admin.accessToken()).param("from","2026-01-01T00:00:00Z").param("to","2026-03-01T00:00:00Z"))
                .andExpect(status().isBadRequest());
    }

    @Test void profileNameIsPersistedScopedAndUsedForAgentInsteadOfLogin() throws Exception {
        var first=owner();
        var other=auth.createUser(new Credentials("other@example.test",password));
        api.perform(put("/api/v1/auth/profile").contentType("application/json").content("{\"displayName\":\"Inválido\"}"))
                .andExpect(status().isUnauthorized());
        api.perform(put("/api/v1/auth/profile").header("Authorization","Bearer "+first.accessToken())
                .contentType("application/json").content("{\"displayName\":\"  Nome   escolhido  \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("Nome escolhido"))
                .andExpect(jsonPath("$.username").value("pedro"));
        var profiles=context.getBean(UserProfileService.class);
        assertThat(profiles.name(other.id())).isNull();
        assertThat(login("pedro",password).user().displayName()).isEqualTo("Nome escolhido");
        assertThat(profiles.instructions("auth-owner")).contains("Nome escolhido");
        profiles.save("auth-owner","Novo nome");
        assertThat(profiles.instructions("auth-owner")).contains("Novo nome").doesNotContain("Nome escolhido");
        api.perform(put("/api/v1/auth/profile").header("Authorization","Bearer "+first.accessToken())
                .contentType("application/json").content("{\"displayName\":\"  \"}"))
                .andExpect(status().isBadRequest());
        assertThat(profiles.name("auth-owner")).isEqualTo("Novo nome");
    }

    @Test void forgotPasswordSendsOnlyThroughRegisteredWhatsappAndActivatesOnFirstUse() throws Exception {
        var initial=owner();String instance="recovery-"+UUID.randomUUID();
        jdbc.update("insert into whatsapp_instances(id,owner_user_id,state,connected_number) values (?,'auth-owner','ready','5511888888888')",instance);
        auth.bindRecoveryChannel(auth.authenticate(initial.accessToken()),new RecoveryChannel(password));
        var response=api.perform(post("/api/v1/auth/forgot-password").contentType("application/json")
                .content("{\"username\":\"PEDRO\",\"phone\":\"5511777777777\"}"))
                .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store"))).andReturn().getResponse().getContentAsString();
        var passwordSent=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(whatsapp,timeout(5000)).sendRecovery(eq("auth-owner"),eq("5511888888888"),passwordSent.capture(),anyString());
        String next=passwordSent.getValue();assertThat(next).matches("[A-Za-z0-9_-]{24}");
        assertThat(response).doesNotContain(next,"5511888888888","5511777777777");
        assertThat(jdbc.queryForObject("select recovery_password_hash from auth_accounts where user_id='auth-owner'",String.class)).startsWith("$2").isNotEqualTo(next);
        login("pedro",password); // An unsolicited request cannot invalidate the current password.
        api.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+initial.accessToken())).andExpect(status().isOk());
        var recovered=login("pedro",next);
        api.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+initial.accessToken())).andExpect(status().isUnauthorized());
        api.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+recovered.accessToken())).andExpect(status().isOk());
        api.perform(post("/api/v1/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("username","pedro","password",password)))).andExpect(status().isUnauthorized());
        for(String name:List.of("unknown-account","pedro")) {
            var generic=api.perform(post("/api/v1/auth/forgot-password").contentType("application/json").content(json.writeValueAsString(Map.of("username",name))))
                    .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
            assertThat(generic).isEqualTo(response);
        }
        verify(whatsapp,times(1)).sendRecovery(anyString(),anyString(),anyString(),anyString());
    }
    @Test void expiredRecoveryOrDeliveryFailurePreservesCurrentPasswordAndOfflineAccountsDoNotSend() throws Exception {
        var initial=owner();String instance="recovery-"+UUID.randomUUID();
        jdbc.update("insert into whatsapp_instances(id,owner_user_id,state,connected_number) values (?,'auth-owner','ready','5511888888888')",instance);
        jdbc.update("insert into whatsapp_instances(id,owner_user_id,state,connected_number,connection_role,updated_at) values (?,'auth-owner','ready','5511666666666','ayla',now()+interval '1 minute')","assistant-"+UUID.randomUUID());
        auth.bindRecoveryChannel(auth.authenticate(initial.accessToken()),new RecoveryChannel(password));
        doThrow(new ayla.whatsapp.WhatsappException(503,"Entrega indisponível")).when(whatsapp).sendRecovery(anyString(),anyString(),anyString(),anyString());
        api.perform(post("/api/v1/auth/forgot-password").contentType("application/json").content("{\"username\":\"pedro\"}")).andExpect(status().isAccepted());
        var captured=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(whatsapp,timeout(5000)).sendRecovery(eq("auth-owner"),eq("5511888888888"),captured.capture(),anyString());
        jdbc.update("update auth_accounts set recovery_expires_at=now()-interval '1 second' where user_id='auth-owner'");
        api.perform(post("/api/v1/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("username","pedro","password",captured.getValue())))).andExpect(status().isUnauthorized());
        login("pedro",password);
        jdbc.update("update whatsapp_instances set state='disconnected' where owner_user_id='auth-owner'");
        jdbc.update("update auth_accounts set recovery_requested_at=now()-interval '6 minutes' where user_id='auth-owner'");
        for(int request=0;request<7;request++)api.perform(post("/api/v1/auth/forgot-password").contentType("application/json").content("{\"username\":\"pedro\"}")).andExpect(status().isAccepted());
        verify(whatsapp,times(1)).sendRecovery(anyString(),anyString(),anyString(),anyString());
        jdbc.update("delete from auth_recovery_limits");
        jdbc.update("update whatsapp_instances set state='ready',connected_number='5511777777777' where id=?",instance);
        api.perform(post("/api/v1/auth/forgot-password").contentType("application/json").content("{\"username\":\"pedro\"}")).andExpect(status().isAccepted());
        verify(whatsapp,times(1)).sendRecovery(anyString(),anyString(),anyString(),anyString()); // Reconnecting a different number never changes the binding.
        api.perform(post("/api/v1/auth/recovery-channel").header("Authorization","Bearer "+initial.accessToken()).contentType("application/json")
                .content("{\"currentPassword\":\"wrong-password\"}")).andExpect(status().isUnauthorized());
        api.perform(post("/api/v1/auth/recovery-channel").header("Authorization","Bearer "+initial.accessToken()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("currentPassword",password)))).andExpect(status().isOk()).andExpect(jsonPath("$.maskedNumber").value("***7777"));
    }
    @Test void protectsEveryPersonalRouteAndPreservesOwnerDataWhileSeparatingNewUsers() throws Exception {
        for(String route:List.of("/api/v1/finance/entries","/api/v1/memories","/api/v1/pantry/items","/api/v1/shopping-list/items",
                "/api/v1/conversations","/api/v1/hermes/configuration","/api/v1/hermes/voice","/api/v1/whatsapp/status","/api/v1/auth/me"))
            api.perform(get(route)).andExpect(status().isUnauthorized());
        api.perform(post("/api/v1/chat").contentType("application/json").content("{\"message\":\"Oi\"}")).andExpect(status().isUnauthorized());
        api.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        var entry=finance.create("auth-owner",new FinanceData.Input(FinanceData.Type.EXPENSE,1234L,"Registro anterior ao login","Outros",null),null);
        String input=json.writeValueAsString(Map.of("username","Pedro","password",password));
        api.perform(post("/api/v1/auth/setup").contentType("application/json").content(input)).andExpect(status().isForbidden());
        api.perform(post("/api/v1/auth/setup").header("X-Ayla-Setup-Key","setup-test-key-0123456789-0123456789").contentType("application/json").content(input))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("auth-owner"));
        api.perform(post("/api/v1/auth/setup").header("X-Ayla-Setup-Key","setup-test-key-0123456789-0123456789").contentType("application/json").content(input)).andExpect(status().isConflict());
        var token=login("Pedro",password);
        api.perform(get("/api/v1/finance/entries/"+entry.id()).header("Authorization","Bearer "+token.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.amountCents").value(1234));
        api.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token.accessToken())).andExpect(status().isOk()).andExpect(jsonPath("$.id").value("auth-owner"));
        api.perform(post("/api/v1/auth/users").header("Authorization","Bearer "+token.accessToken()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("username","alice","password",password)))).andExpect(status().isOk());
        var other=login("alice",password);
        api.perform(get("/api/v1/finance/entries/"+entry.id()).header("Authorization","Bearer "+other.accessToken())).andExpect(status().isNotFound());
        for(String route:List.of("/api/v1/hermes/configuration","/api/v1/hermes/voice"))
            api.perform(get(route).header("Authorization","Bearer "+other.accessToken())).andExpect(status().isForbidden());
        api.perform(get("/api/v1/memories").header("Authorization","Bearer "+other.accessToken())).andExpect(status().isOk()).andExpect(content().json("[]"));
        api.perform(get("/api/v1/finance/entries").cookie(new jakarta.servlet.http.Cookie("accessToken",token.accessToken()))).andExpect(status().isUnauthorized());
    }
    @Test void rotatesTokensDetectsReplayAndImmediatelyRevokesLogoutAndExpiredAccess() throws Exception {
        var first=owner();
        assertThat(first.expiresIn()).isEqualTo(86400);
        var persistedExpiry=jdbc.queryForObject("select access_expires_at from auth_sessions where access_hash=?",java.sql.Timestamp.class,AuthService.hash(first.accessToken()));
        assertThat(persistedExpiry.toInstant()).isCloseTo(first.accessExpiresAt(),org.assertj.core.api.Assertions.within(1,java.time.temporal.ChronoUnit.MILLIS));
        var response=api.perform(post("/api/v1/auth/refresh").contentType("application/json").content(json.writeValueAsString(Map.of("refreshToken",first.refreshToken()))))
                .andExpect(status().isOk()).andReturn();
        var next=json.readValue(response.getResponse().getContentAsString(),Tokens.class);
        assertThat(next.expiresIn()).isEqualTo(86400);
        assertThat(next.accessToken()).isNotEqualTo(first.accessToken());
        api.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+first.accessToken())).andExpect(status().isUnauthorized());
        api.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+next.accessToken())).andExpect(status().isOk());
        api.perform(post("/api/v1/auth/refresh").contentType("application/json").content(json.writeValueAsString(Map.of("refreshToken",first.refreshToken())))).andExpect(status().isUnauthorized());
        api.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+next.accessToken())).andExpect(status().isUnauthorized());
        var expired=login("pedro",password);
        jdbc.update("update auth_sessions set access_expires_at=now()-interval '1 second' where access_hash=?",AuthService.hash(expired.accessToken()));
        api.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+expired.accessToken())).andExpect(status().isUnauthorized());
        var current=login("pedro",password);
        api.perform(post("/api/v1/auth/logout").header("Authorization","Bearer "+current.accessToken())).andExpect(status().isNoContent());
        api.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+current.accessToken())).andExpect(status().isUnauthorized());
        api.perform(post("/api/v1/auth/refresh").contentType("application/json").content(json.writeValueAsString(Map.of("refreshToken",current.refreshToken())))).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForList("select access_hash from auth_sessions",String.class)).doesNotContain(current.accessToken());
        assertThat(jdbc.queryForObject("select password_hash from auth_accounts where user_id='auth-owner'",String.class)).startsWith("$2").doesNotContain(password);
        assertThat(current.toString()).doesNotContain(current.accessToken(),current.refreshToken());
    }
    @Test void keepsInternalKeysScopedAndSupportsPasswordChangesAndPersistentLoginLimits() throws Exception {
        var token=owner();
        var conversation=conversations.newConversation("auth-owner","Ponte interna");
        String hermes="Bearer hermes-test-key-0123456789-0123456789",whatsapp="Bearer whatsapp-test-key-0123456789-0123456789";
        api.perform(post("/internal/hermes/finance/list").header("Authorization",hermes).header("X-Ayla-Session",conversation.sessionId()).contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        api.perform(post("/internal/hermes/finance/list").header("Authorization","Bearer "+token.accessToken()).header("X-Ayla-Session",conversation.sessionId()).contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        for(String key:List.of(hermes,whatsapp))api.perform(get("/api/v1/finance/entries").header("Authorization",key)).andExpect(status().isUnauthorized());
        api.perform(post("/internal/hermes/memory/search").header("Authorization",hermes).header("X-Ayla-Session","unknown").contentType("application/json").content("{\"query\":\"cachorro\"}"))
                .andExpect(status().isForbidden());
        String instance="auth-test-"+UUID.randomUUID();UUID contact=UUID.randomUUID();
        jdbc.update("insert into whatsapp_instances(id,owner_user_id) values (?,?)",instance,"auth-owner");
        jdbc.update("insert into whatsapp_chats(id,instance_id,chat_id,display_name) values (?,?,?,?)",contact,instance,"5511999999999@c.us","Teste");
        when(chat.chat(any(),eq("auth-owner"))).thenReturn(new ChatResponse("Oi","session"));
        api.perform(post("/api/v1/chat").header("Authorization",whatsapp).header("X-Ayla-WhatsApp-Chat",contact.toString()).contentType("application/json").content("{\"message\":\"Oi\"}"))
                .andExpect(status().isOk());
        api.perform(get("/api/v1/auth/me").header("Authorization",whatsapp)).andExpect(status().isUnauthorized());
        api.perform(post("/api/v1/auth/change-password").header("Authorization","Bearer "+token.accessToken()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("currentPassword",password,"newPassword","new-synthetic-test-password")))).andExpect(status().isNoContent());
        api.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token.accessToken())).andExpect(status().isUnauthorized());
        login("pedro","new-synthetic-test-password");
        for(int attempt=0;attempt<8;attempt++)api.perform(post("/api/v1/auth/login").contentType("application/json")
                .content("{\"username\":\"unknown-login\",\"password\":\"incorrect-password\"}")).andExpect(status().isUnauthorized());
        api.perform(post("/api/v1/auth/login").contentType("application/json").content("{\"username\":\"unknown-login\",\"password\":\"incorrect-password\"}"))
                .andExpect(status().isTooManyRequests());
    }
}
