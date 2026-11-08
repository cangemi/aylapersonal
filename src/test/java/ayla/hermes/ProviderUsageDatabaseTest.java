package ayla.hermes;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker=true)
class ProviderUsageDatabaseTest {
    @Container static final PostgreSQLContainer<?> database=new PostgreSQLContainer<>("postgres:17-alpine");
    @Test void consumptionIsUserScopedAndIncompleteCallsAreNeverConvertedToZeroTokens(){
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(database.getJdbcUrl(),database.getUsername(),database.getPassword()));
        jdbc.execute("create table ai_usage_events(user_id text,occurred_at timestamptz,provider text,model text,status integer,input_tokens bigint,output_tokens bigint,total_tokens bigint)");
        var hourAgo=Timestamp.from(Instant.now().minusSeconds(3600));
        jdbc.update("insert into ai_usage_events values (?,?,?,?,?,?,?,?)","alice",hourAgo,"nous","model-a",200,100,10,110);
        jdbc.update("insert into ai_usage_events values (?,?,?,?,?,?,?,?)","alice",hourAgo,null,null,502,null,null,null);
        jdbc.update("insert into ai_usage_events values (?,?,?,?,?,?,?,?)","bob",hourAgo,"nous","model-a",200,900,100,1000);
        jdbc.update("insert into ai_usage_events values (?,?,?,?,?,?,?,?)","alice",Timestamp.from(Instant.now().minusSeconds(26*3600)),"nous","model-a",200,500,100,600);
        var service=new ProviderUsageService(jdbc,mock(HermesUserScope.class),mock(HermesSettingsService.class),mock(HermesClient.class),mock(ModelAvailabilityService.class));
        var view=service.view("alice","24H");
        var total=(Map<?,?>)view.get("recordedUsage");
        assertThat(((Number)total.get("calls")).longValue()).isEqualTo(2);
        assertThat(((Number)total.get("total_tokens")).longValue()).isEqualTo(110);
        assertThat(((Number)total.get("unreported_calls")).longValue()).isEqualTo(1);
        var rows=(List<?>)view.get("recordedUsageByModel");
        assertThat(rows).hasSize(2);
        assertThat(rows.stream().map(row->(Map<?,?>)row).filter(row->row.get("model")==null).findFirst().orElseThrow().get("total_tokens")).isNull();
        var empty=(Map<?,?>)service.view("no-usage","24H").get("recordedUsage");
        assertThat(((Number)empty.get("calls")).longValue()).isZero();
        assertThat(empty.get("total_tokens")).isNull();
    }
}
