package ayla;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ayla.chat.session.ConversationService;
import ayla.whatsapp.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties="ayla.whatsapp.api-key=whatsapp-integration-test-key-1234567890")
class WhatsappDatabaseTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired ConversationService conversations;
    @Autowired WhatsappAccess access;
    @Autowired PostgreSQLContainer postgres;
    private final String token="Bearer whatsapp-integration-test-key-1234567890";

    @Test void contactsShareOwnerButKeepSeparateStableConversationsAndRespectRevocation() {
        String owner="wa-test-"+UUID.randomUUID(), instance="test-"+UUID.randomUUID();
        var frontend=conversations.newConversation(owner,"Frontend");
        jdbc.update("insert into whatsapp_instances(id,owner_user_id) values (?,?)",instance,owner);
        UUID first=UUID.randomUUID(), second=UUID.randomUUID();
        jdbc.update("insert into whatsapp_chats(id,instance_id,chat_id,display_name) values (?,?,?,?)",first,instance,"5511999999999@c.us","Primeiro");
        jdbc.update("insert into whatsapp_chats(id,instance_id,chat_id,display_name,mention_only) values (?,?,?,?,true)",second,instance,"5511888888888@c.us","Segundo");
        assertThatThrownBy(()->access.resolve("Bearer invalid",first.toString())).isInstanceOfSatisfying(WhatsappException.class,e->assertThat(e.status()).isEqualTo(401));
        var a=access.resolve(token,first.toString()); var b=access.resolve(token,second.toString());
        assertThat(a.userId()).isEqualTo(owner); assertThat(b.userId()).isEqualTo(owner);
        assertThat(a.conversationId()).isNotEqualTo(b.conversationId()).isNotEqualTo(frontend.conversationId());
        assertThat(access.resolve(token,first.toString())).isEqualTo(a);
        assertThat(jdbc.queryForObject("select current_conversation_id from chat_users where user_id=?",UUID.class,owner)).isEqualTo(frontend.conversationId());
        jdbc.update("update whatsapp_chats set enabled=false where id=?",first);
        assertThatThrownBy(()->access.resolve(token,first.toString())).isInstanceOfSatisfying(WhatsappException.class,e->assertThat(e.status()).isEqualTo(403));
    }

    @Test void nodePersistsContactsAndEncryptedSessionAgainstMigratedPostgres() throws Exception {
        Path log=Path.of("target","whatsapp-node-postgres-tests.log");
        var process=new ProcessBuilder("node","--test","agentNode/test/postgres.integration.test.js");
        String url="postgresql://"+postgres.getUsername()+":"+postgres.getPassword()+"@"+postgres.getHost()+":"+postgres.getMappedPort(5432)+"/"+postgres.getDatabaseName();
        process.environment().put("WHATSAPP_TEST_DATABASE_URL",url);
        var running=process.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!running.waitFor(45,TimeUnit.SECONDS)) {running.destroyForcibly();throw new AssertionError("Node PostgreSQL test timed out");}
        assertThat(running.exitValue()).withFailMessage(Files.readString(log)).isZero();
    }
    @Test void voiceSettingsSurviveNewServiceInstancesAndDriveSynthesis() {
        var dashboard=org.mockito.Mockito.mock(ayla.hermes.HermesDashboardClient.class);
        var service=new ayla.hermes.VoiceSettingsService(jdbc,dashboard);
        var initial=service.get();
        assertThat(initial.voice()).isEqualTo("pt-BR-FranciscaNeural");
        assertThat(initial.speed()).isEqualByComparingTo("1.02");
        assertThat(initial.pitchHz()).isEqualTo(2);
        var changed=new ayla.hermes.VoiceSettings("pt-BR-ThalitaMultilingualNeural",new java.math.BigDecimal("0.95"),-3);
        try {
            service.update(changed);
            var reloaded=new ayla.hermes.VoiceSettingsService(jdbc,dashboard);
            assertThat(reloaded.get()).isEqualTo(changed);
            reloaded.speak("default","Oi!");
            org.mockito.Mockito.verify(dashboard).speak("default","Oi!",changed);
        } finally {service.update(initial);}
    }
    @Test void spontaneousVoiceCooldownPersistsPerConversationAndIsReservedOnlyOnce() {
        String owner="voice-"+UUID.randomUUID();
        var conversation=conversations.newConversation(owner,"Voz ocasional");
        var budget=new ayla.chat.service.SpontaneousVoice(jdbc);
        UUID id=conversation.conversationId();
        assertThat(budget.available(owner,id)).isTrue();
        assertThat(budget.reserve(owner,id)).isTrue();
        assertThat(budget.reserve(owner,id)).isFalse();
        assertThat(new ayla.chat.service.SpontaneousVoice(jdbc).available(owner,id)).isFalse();
        assertThat(budget.reserve("another-owner",id)).isFalse();
        jdbc.update("update chat_conversations set last_spontaneous_voice_at=CURRENT_TIMESTAMP-INTERVAL '16 minutes' where id=?",id);
        assertThat(budget.reserve(owner,id)).isTrue();
    }

}
