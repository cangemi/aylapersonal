package ayla.chat.service;

import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class AgentActionReceiptDatabaseTest {
    @Container static final PostgreSQLContainer<?> database=new PostgreSQLContainer<>("postgres:17-alpine");
    @Test void receiptIsPersistedOnlyForTheOwnedSessionAndCurrentTurn() throws Exception {
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(database.getJdbcUrl(),database.getUsername(),database.getPassword()));
        jdbc.execute("create table chat_conversations(id uuid primary key,user_id text,hermes_session_id text)");
        jdbc.execute("create table finance_agent_turns(id uuid primary key,conversation_id uuid,user_id text,sequence bigint)");
        try(var stream=getClass().getResourceAsStream("/db/migration/V59__agent_action_receipts.sql")){
            jdbc.execute(new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        }
        var conversation=UUID.randomUUID();var previous=UUID.randomUUID();var current=UUID.randomUUID();
        jdbc.update("insert into chat_conversations values (?,?,?)",conversation,"alice","session-a");
        jdbc.update("insert into finance_agent_turns values (?,?,?,?),(?,?,?,?)",previous,conversation,"alice",1,current,conversation,"alice",2);
        var service=new AgentActionReceipts(jdbc);
        service.record("session-a","finance","summary");
        service.record("missing-session","finance","record");
        assertThat(jdbc.queryForObject("select count(*) from agent_action_receipts",Integer.class)).isZero();
        service.record("session-a","finance","record");
        assertThat(service.missingDomains("alice",current,"gastei 10 reais","Registro de despesa concluído: R$ 10,00.")).isEmpty();
        assertThat(service.missingDomains("alice",previous,"gastei 10 reais","Registro de despesa concluído: R$ 10,00.")).containsExactly("finance");
        assertThat(service.missingDomains("bob",current,"gastei 10 reais","Registro de despesa concluído: R$ 10,00.")).containsExactly("finance");
    }
}
