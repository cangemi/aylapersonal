package ayla.monitoring;

import java.time.*;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Planning reference for ONE constantly running A1 VM, never an OCI billing meter. */
@Component
@ConditionalOnProperty(name="ayla.oracle.metrics.enabled",havingValue="true")
public class OracleFreeTierMetrics {
    private final Clock clock;
    private final ZoneId zone;
    @Autowired
    public OracleFreeTierMetrics(MeterRegistry registry,Environment environment,
            @Value("${ayla.oracle.ocpus:2}") double cpus,
            @Value("${ayla.oracle.memory-gb:12}") double memory,
            @Value("${ayla.oracle.cpu-monthly-hours:1500}") double cpuAllowance,
            @Value("${ayla.oracle.memory-monthly-hours:9000}") double memoryAllowance,
            @Value("${ayla.oracle.time-zone:America/Sao_Paulo}") String timeZone){
        this(registry,Clock.systemUTC(),timeZone,environment.matchesProfiles("vps")?"vps":"local",cpus,memory,cpuAllowance,memoryAllowance);
    }
    OracleFreeTierMetrics(MeterRegistry registry,Clock clock,String timeZone,String environment,
            double cpus,double memory,double cpuAllowance,double memoryAllowance){
        this.clock=clock;this.zone=ZoneId.of(timeZone);
        for(double value:new double[]{cpus,memory,cpuAllowance,memoryAllowance})
            if(!Double.isFinite(value)||value<=0)throw new IllegalArgumentException("Oracle planning limits must be positive");
        Gauge.builder("ayla.oracle.environment.info",()->1).tags("environment",environment,"source","planned_24x7","time_zone",timeZone).register(registry);
        register(registry,"cpu",cpus,cpuAllowance);
        register(registry,"memory",memory,memoryAllowance);
    }
    private ZonedDateTime now(){return ZonedDateTime.now(clock.withZone(zone));}
    private ZonedDateTime start(ZonedDateTime now,String period){return (period.equals("day")?now.toLocalDate():now.toLocalDate().withDayOfMonth(1)).atStartOfDay(zone);}
    double elapsedHours(String period){var now=now();return Duration.between(start(now,period),now).toSeconds()/3600d;}
    double fullHours(String period){var now=now();var from=start(now,period);return Duration.between(from,period.equals("day")?from.plusDays(1):from.plusMonths(1)).toSeconds()/3600d;}
    private void register(MeterRegistry registry,String resource,double allocated,double allowance){
        Gauge.builder("ayla.oracle.allocated",()->allocated).tag("resource",resource).register(registry);
        Gauge.builder("ayla.oracle.allowance.hours",()->allowance).tag("resource",resource).register(registry);
        Gauge.builder("ayla.oracle.daily.budget.hours",()->allowance/now().toLocalDate().lengthOfMonth()).tag("resource",resource).register(registry);
        for(String period:new String[]{"day","month"}){
            Gauge.builder("ayla.oracle.planned.hours",()->allocated*elapsedHours(period)).tags("resource",resource,"period",period).register(registry);
            Gauge.builder("ayla.oracle.projection.hours",()->allocated*fullHours(period)).tags("resource",resource,"period",period).register(registry);
        }
    }
}
