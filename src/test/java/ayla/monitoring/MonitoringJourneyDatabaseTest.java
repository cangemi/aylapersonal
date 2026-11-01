package ayla.monitoring;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class MonitoringJourneyDatabaseTest {
    @Container static final PostgreSQLContainer<?> database=new PostgreSQLContainer<>("postgres:17-alpine");
    JdbcTemplate jdbc;
    MonitoringService service;
    @org.junit.jupiter.api.BeforeEach void setup() throws Exception {
        jdbc=new JdbcTemplate(new DriverManagerDataSource(database.getJdbcUrl(),database.getUsername(),database.getPassword()));
        jdbc.execute("drop table if exists ai_usage_events,application_events,chat_jobs,chat_conversations,auth_accounts,chat_users cascade");
        jdbc.execute("create table auth_accounts(user_id varchar(255) primary key,username varchar(120),display_name varchar(120))");
        jdbc.execute("create table chat_users(user_id varchar(255),hermes_profile varchar(64))");
        try(var migration=getClass().getResourceAsStream("/db/migration/V25__application_monitoring.sql")) {
            jdbc.execute(new String(Objects.requireNonNull(migration).readAllBytes(),StandardCharsets.UTF_8));
        }
        try(var migration=getClass().getResourceAsStream("/db/migration/V30__ai_usage_monitoring.sql")) {
            jdbc.execute(new String(Objects.requireNonNull(migration).readAllBytes(),StandardCharsets.UTF_8));
        }
        try(var migration=getClass().getResourceAsStream("/db/migration/V47__monitoring_call_trace.sql")) {
            jdbc.execute(new String(Objects.requireNonNull(migration).readAllBytes(),StandardCharsets.UTF_8));
        }
        jdbc.execute("create table chat_conversations(id uuid primary key)");
        try(var migration=getClass().getResourceAsStream("/db/migration/V31__durable_chat_queue.sql")) {
            jdbc.execute(new String(Objects.requireNonNull(migration).readAllBytes(),StandardCharsets.UTF_8));
        }
        jdbc.update("insert into auth_accounts(user_id,username,display_name) values ('alice','alice@example.org','Alice')");
        jdbc.update("insert into chat_conversations values (?)",UUID.fromString("00000000-0000-4000-8000-0000000000aa"));
        service=new MonitoringService(new NamedParameterJdbcTemplate(jdbc));
    }
    private static final UUID JOB=UUID.fromString("00000000-0000-4000-8000-000000000001");
    private void seed(JdbcTemplate jdbc) {
        Instant now=Instant.now();
        jdbc.update("""
                insert into chat_jobs(id,user_id,conversation_id,request_key,fingerprint,source,status,user_message,
                created_at,started_at,finished_at,delivered_at)
                values(?, 'alice', ?, ?, ?, 'WHATSAPP', 'COMPLETED', 'conteudo que nunca deve aparecer',
                ?, ?, ?, ?)
                """,JOB,UUID.fromString("00000000-0000-4000-8000-0000000000aa"),"sha-request","f".repeat(64),
                java.sql.Timestamp.from(now.minusSeconds(30)),java.sql.Timestamp.from(now.minusSeconds(29)),
                java.sql.Timestamp.from(now.minusSeconds(3)),java.sql.Timestamp.from(now.minusSeconds(2)));
        jdbc.update("""
                insert into application_events(id,occurred_at,user_id,source,method,route,action,status,duration_ms,request_key)
                values(?, ?, 'alice','WHATSAPP','POST','/api/v1/chat','Enviar uma mensagem à Ayla',202,150,'sha-request')
                """,UUID.randomUUID(),java.sql.Timestamp.from(now.minusSeconds(30)));
        jdbc.update("""
                insert into ai_usage_events(id,occurred_at,user_id,source,provider,model,purpose,status,duration_ms,
                input_tokens,output_tokens,total_tokens,request_id)
                values(?, ?, 'alice','WHATSAPP','nous','nous/welcome','CHAT',200,4200,1200,800,2000,?)
                """,UUID.randomUUID(),java.sql.Timestamp.from(now.minusSeconds(28)),JOB.toString());
        jdbc.update("""
                insert into ai_usage_events(id,occurred_at,user_id,source,provider,model,purpose,status,duration_ms,
                input_tokens,output_tokens,total_tokens,request_id)
                values(?, ?, 'alice','WEB','nous','nous/welcome','CHAT',200,1000,100,50,150,null)
                """,UUID.randomUUID(),java.sql.Timestamp.from(now.minusSeconds(20)));
    }
    @Test void feedMergesApiAndAiRowsAndTraceExplainsEachStepWithoutExposingMessages() throws Exception {
        seed(jdbc);
        var feed=service.feed(new MonitoringService.Query(null,null,null,null,"ALL",null,0,10));
        assertThat((Long)feed.get("total")).isEqualTo(3L);
        var items=(List<?>)feed.get("items");
        assertThat(items).hasSize(3);
        var kinds=new java.util.ArrayList<Object>();
        for(var row:items) kinds.add(((Map<?,?>)row).get("kind"));
        assertThat(kinds).containsExactlyInAnyOrder("api","ia","ia");
        var aiRow=items.stream().map(row->(Map<?,?>)row).filter(row->"ia".equals(row.get("kind"))&&row.get("request_key")!=null).findFirst().orElseThrow();
        assertThat(aiRow.get("request_key")).isEqualTo(JOB.toString());
        assertThat(aiRow.get("action").toString()).contains("Conversar com a Ayla");
        var searched=service.feed(new MonitoringService.Query(null,null,null,null,"ALL","nous",0,10));
        assertThat((Long)searched.get("total")).isEqualTo(2L);
        var errors=service.feed(new MonitoringService.Query(null,null,null,null,"ERROR",null,0,10));
        assertThat((Long)errors.get("total")).isEqualTo(0L);
        assertThatThrownBy(()->service.feed(new MonitoringService.Query(null,Instant.now(),null,null,"ALL","x".repeat(121),0,10)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var trace=service.trace(JOB.toString());
        assertThat(trace.get("status")).isEqualTo("COMPLETED");
        assertThat(trace.get("request_key")).isEqualTo("sha-request");
        assertThat((List<?>)trace.get("ai_calls")).hasSize(1);
        assertThat((List<?>)trace.get("related")).hasSize(1);
        assertThat(trace.get("duration_ms")).isEqualTo(28000L);
        var byKey=service.trace("sha-request");
        assertThat(byKey.get("id")).isEqualTo(JOB);
        assertThatThrownBy(()->service.trace("sem-correspondencia")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->service.trace("chave inválida com espaço")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(trace.toString()).doesNotContain("conteudo que nunca deve aparecer");
        var traceList=service.traces(new MonitoringService.Query(null,null,null,null,"ALL",null,0,10));
        assertThat((Long)traceList.get("total")).isEqualTo(1L);
        var listed=(Map<?,?>)((List<?>)traceList.get("items")).getFirst();
        assertThat(listed.get("ai_calls")).isEqualTo(1L);
        assertThat(((Number)listed.get("total_tokens")).intValue()).isEqualTo(2000);
        assertThatThrownBy(()->service.traces(new MonitoringService.Query(null,null,null,"HERMES","ALL",null,0,10)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->service.traces(new MonitoringService.Query(Instant.now().plus(Duration.ofDays(1)),Instant.now(),null,null,"ALL",null,0,10)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void recordingKeepsTheRequestKeyAndDropsInvalidCorrelationIds() throws Exception {
        service.record(new MonitoringService.Event(UUID.randomUUID(),Instant.now(),"alice","WHATSAPP","POST","/api/v1/chat","Enviar uma mensagem à Ayla",202,10,null,null,null,"sha-ok"));
        service.record(new MonitoringService.Event(UUID.randomUUID(),Instant.now(),"alice","WHATSAPP","POST","/api/v1/chat","Enviar uma mensagem à Ayla",202,10,null,null,null,"chave inválida"));
        assertThat(jdbc.queryForObject("select count(*) from application_events where request_key='sha-ok'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from application_events where request_key is not null",Long.class)).isEqualTo(1);
        assertThat(MonitoringService.text("a%b_c")).isEqualTo("%a\\%b\\_c%");
        assertThatThrownBy(()->MonitoringService.text("x".repeat(121))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(AiUsageService.correlation()).isNull();
        org.slf4j.MDC.put("requestId","00000000-0000-4000-8000-000000000001");
        assertThat(AiUsageService.correlation()).isEqualTo("00000000-0000-4000-8000-000000000001");
        org.slf4j.MDC.put("requestId","chave com espaço");
        assertThat(AiUsageService.correlation()).isNull();
        org.slf4j.MDC.remove("requestId");
    }
}
