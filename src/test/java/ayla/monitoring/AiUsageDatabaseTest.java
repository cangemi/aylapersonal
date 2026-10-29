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
class AiUsageDatabaseTest {
    @Container static final PostgreSQLContainer<?> database=new PostgreSQLContainer<>("postgres:17-alpine");
    @Test void aggregatesSeparateRequestsByUserAndModelWithoutInventingMissingUsage() throws Exception {
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(database.getJdbcUrl(),database.getUsername(),database.getPassword()));
        jdbc.execute("create table chat_users(user_id varchar(255),hermes_profile varchar(64))");
        jdbc.execute("create table auth_accounts(user_id varchar(255),username varchar(120),display_name varchar(120))");
        try(var migration=getClass().getResourceAsStream("/db/migration/V30__ai_usage_monitoring.sql")) {
            jdbc.execute(new String(Objects.requireNonNull(migration).readAllBytes(),StandardCharsets.UTF_8));
        }
        jdbc.update("insert into chat_users values ('alice','profile-alice'),('bob','profile-bob')");
        jdbc.update("insert into auth_accounts values ('alice','alice@example.org','Alice'),('bob','bob@example.org','Bob')");
        var service=new AiUsageService(new NamedParameterJdbcTemplate(jdbc));
        var runtime=Map.of("provider","nous","model","nous/welcome");
        service.record("profile-alice",Map.of("message","not stored"),Map.of("usage",Map.of("input_tokens",10,"output_tokens",4,"total_tokens",14),"runtime",runtime),200,20);
        service.record("profile-alice",Map.of("message","not stored"),null,502,30);
        service.record("profile-bob",Map.of("message",List.of()),Map.of("usage",Map.of("prompt_tokens",3,"completion_tokens",2,"runtime",runtime)),200,40);
        var all=service.overview(null,null,null,null);
        var totals=(Map<?,?>)all.get("totals");
        assertThat(((Number)totals.get("calls")).longValue()).isEqualTo(3);
        assertThat(((Number)totals.get("total_tokens")).longValue()).isEqualTo(19);
        assertThat(((Number)totals.get("input_tokens")).longValue()).isEqualTo(13);
        assertThat(((Number)totals.get("output_tokens")).longValue()).isEqualTo(6);
        assertThat(((Number)totals.get("unreported_calls")).longValue()).isEqualTo(1);
        assertThat(((Number)totals.get("failures")).longValue()).isEqualTo(1);
        var alice=(Map<?,?>)service.overview(null,null,"alice","WEB").get("totals");
        assertThat(((Number)alice.get("total_tokens")).longValue()).isEqualTo(14);
        assertThat((List<?>)all.get("byUser")).hasSize(2);
        assertThat((List<?>)all.get("daily")).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from ai_usage_events where total_tokens is null",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from ai_usage_events where purpose='VISION'",Long.class)).isEqualTo(1);
        var aliceCalls=service.calls(null,null,"alice","WEB",0,1);
        assertThat(((Number)aliceCalls.get("total")).longValue()).isEqualTo(2);
        assertThat((List<?>)aliceCalls.get("items")).hasSize(1);
        assertThat(((Map<?,?>)((List<?>)aliceCalls.get("items")).getFirst()).get("user_id")).isEqualTo("alice");
        assertThat((List<?>)service.calls(null,null,"alice","WEB",1,1).get("items")).hasSize(1);
        assertThatThrownBy(()->service.calls(null,null,null,null,-1,25)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->service.overview(Instant.now().minus(Duration.ofDays(70)),Instant.now(),null,null)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        Instant now=Instant.now(),cutoff=AiUsageService.retentionStart(now);
        for(Instant time:List.of(cutoff.minusSeconds(1),cutoff,now.minus(Duration.ofDays(40)))) {
            jdbc.update("insert into ai_usage_events select ?,?,user_id,source,provider,model,purpose,status,duration_ms,input_tokens,output_tokens,total_tokens from ai_usage_events order by occurred_at desc limit 1",
                    UUID.randomUUID(),java.sql.Timestamp.from(time));
        }
        var extended=(Map<?,?>)service.overview(now.minus(Duration.ofDays(50)),now,"bob",null).get("totals");
        assertThat(((Number)extended.get("calls")).longValue()).isGreaterThan(0);
        assertThat(service.deleteBefore(cutoff)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from ai_usage_events where occurred_at=?",Long.class,java.sql.Timestamp.from(cutoff))).isEqualTo(1);
        assertThat(AiUsageService.retentionStart(Instant.parse("2026-04-30T12:00:00Z"))).isEqualTo(Instant.parse("2026-02-28T12:00:00Z"));
        assertThat(AiUsageService.tokens(-1)).isNull();
        assertThat(AiUsageService.tokens(1.5)).isNull();
        assertThat(AiUsageService.tokens("100")).isNull();
    }
}
