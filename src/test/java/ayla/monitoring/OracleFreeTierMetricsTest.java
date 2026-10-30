package ayla.monitoring;
import static org.assertj.core.api.Assertions.*;
import java.time.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
class OracleFreeTierMetricsTest {
 @Test void octoberPlanUsesCalendarHoursNotCpuUtilization(){
  try(var registry=new SimpleMeterRegistry()){
   new OracleFreeTierMetrics(registry,Clock.fixed(Instant.parse("2026-10-30T15:00:00Z"),ZoneOffset.UTC),"America/Sao_Paulo","local",2,12,1500,9000);
   assertThat(registry.get("ayla.oracle.planned.hours").tags("resource","cpu","period","day").gauge().value()).isEqualTo(24);
   assertThat(registry.get("ayla.oracle.planned.hours").tags("resource","cpu","period","month").gauge().value()).isEqualTo(1416);
   assertThat(registry.get("ayla.oracle.projection.hours").tags("resource","cpu","period","month").gauge().value()).isEqualTo(1488);
   assertThat(registry.get("ayla.oracle.projection.hours").tags("resource","memory","period","month").gauge().value()).isEqualTo(8928);
   assertThat(registry.get("ayla.oracle.daily.budget.hours").tag("resource","cpu").gauge().value()).isCloseTo(1500d/31,within(0.00001));
  }
 }
 @Test void februaryStartsAtZeroInConfiguredTimeZone(){
  try(var registry=new SimpleMeterRegistry()){
   new OracleFreeTierMetrics(registry,Clock.fixed(Instant.parse("2026-02-01T03:00:00Z"),ZoneOffset.UTC),"America/Sao_Paulo","vps",2,12,1500,9000);
   assertThat(registry.get("ayla.oracle.planned.hours").tags("resource","cpu","period","month").gauge().value()).isZero();
   assertThat(registry.get("ayla.oracle.projection.hours").tags("resource","cpu","period","month").gauge().value()).isEqualTo(1344);
   assertThat(registry.get("ayla.oracle.daily.budget.hours").tag("resource","cpu").gauge().value()).isCloseTo(1500d/28,within(0.00001));
  }
 }
}
