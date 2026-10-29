package ayla.investment;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Mantém no PostgreSQL o histórico do CDI diário; a internet só é usada para completar o que falta. */
@Service
public class CdiService {
    private static final Logger log = LoggerFactory.getLogger(CdiService.class);
    private static final Duration RETRY = Duration.ofMinutes(15);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** Taxas como fração diária (0,0005 = 0,05% ao dia), ordenadas por data. */
    public record Snapshot(Map<LocalDate, BigDecimal> fractions, LocalDate latestDate, BigDecimal latestFraction, boolean stale) {}

    private final JdbcTemplate jdbc;
    private final CdiSource source;
    private Instant lastAttempt = Instant.EPOCH;

    public CdiService(JdbcTemplate jdbc, CdiSource source) { this.jdbc = jdbc; this.source = source; }

    public synchronized Snapshot load(LocalDate from, LocalDate today) {
        LocalDate expected = InvestmentCalendar.previousBusinessDay(today);
        LocalDate min = jdbc.queryForObject("select min(rate_date) from cdi_rates", LocalDate.class);
        LocalDate max = jdbc.queryForObject("select max(rate_date) from cdi_rates", LocalDate.class);
        boolean missingHistory = min == null || min.isAfter(from.plusDays(7));
        boolean outdated = max == null || max.isBefore(expected);
        if ((missingHistory || outdated) && Duration.between(lastAttempt, Instant.now()).compareTo(RETRY) > 0) {
            lastAttempt = Instant.now();
            LocalDate start = missingHistory ? from : max.plusDays(1);
            try {
                int saved = 0;
                for (var rate : source.fetch(start, today)) {
                    saved += jdbc.update("insert into cdi_rates(rate_date,daily_percent) values (?,?) on conflict (rate_date) do nothing",
                            rate.date(), rate.dailyPercent());
                }
                log.info("CDI atualizado: {} novas taxas a partir de {}", saved, start);
            } catch (RuntimeException e) {
                log.warn("Não foi possível atualizar o CDI agora: {}", e.getMessage());
            }
            max = jdbc.queryForObject("select max(rate_date) from cdi_rates", LocalDate.class);
        }
        Map<LocalDate, BigDecimal> fractions = new TreeMap<>();
        jdbc.query("select rate_date,daily_percent from cdi_rates where rate_date>=? order by rate_date", rs -> {
            fractions.put(rs.getObject("rate_date", LocalDate.class), rs.getBigDecimal("daily_percent").divide(HUNDRED));
        }, from);
        if (fractions.isEmpty() && max != null) {
            jdbc.query("select rate_date,daily_percent from cdi_rates order by rate_date desc limit 1", rs -> {
                fractions.put(rs.getObject("rate_date", LocalDate.class), rs.getBigDecimal("daily_percent").divide(HUNDRED));
            });
        }
        if (max == null) throw new ayla.finance.FinanceException(503, "Não consegui consultar o CDI agora. Tente novamente em instantes.");
        BigDecimal latest = jdbc.queryForObject("select daily_percent from cdi_rates where rate_date=?", BigDecimal.class, max).divide(HUNDRED);
        return new Snapshot(fractions, max, latest, max.isBefore(expected));
    }
}
