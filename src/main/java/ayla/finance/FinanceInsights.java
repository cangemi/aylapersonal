package ayla.finance;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Análises para entender para onde o dinheiro vai. Gastos excluem aportes em investimento, que aparecem à parte. */
@Service
@Transactional(readOnly = true)
public class FinanceInsights {
    private static final String INVESTMENT = "lower(category) in (" + InvestmentCategories.SQL_LIST + ")";
    private static final String TZ = "occurred_at AT TIME ZONE 'America/Sao_Paulo'";

    public record Month(String label, long incomeCents, long expenseCents, long investedCents, long netCents) {}
    public record Current(String month, int daysElapsed, int daysInMonth, long expenseCents, long incomeCents, long investedCents,
                          long dailyAverageCents, long projectedExpenseCents, long previousMonthExpenseCents,
                          long previousSamePeriodExpenseCents, Double changePercent, Double savingsRatePercent,
                          long largestExpenseCents, String largestExpenseDescription) {}
    public record CategoryChange(String category, long currentCents, long previousCents) {}
    public record Cumulative(List<Long> currentCents, List<Long> previousCents) {}
    public record Trend(String direction, long slopeCentsPerMonth, List<Long> lineCents) {}
    public record Insights(String timezone, LocalDate today, List<Month> months, Current current, List<CategoryChange> categories,
                           List<Long> weekdayExpenseCents, Cumulative cumulative, Trend trend) {}

    private final JdbcTemplate jdbc;
    public FinanceInsights(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static Timestamp start(LocalDate date) { return Timestamp.from(date.atStartOfDay(FinanceService.ZONE).toInstant()); }

    public Insights insights(String user, int monthCount) {
        if (monthCount < 2 || monthCount > 24) throw new FinanceException(400, "Use de 2 a 24 meses.");
        LocalDate today = LocalDate.now(FinanceService.ZONE);
        YearMonth thisMonth = YearMonth.from(today), previousMonth = thisMonth.minusMonths(1);
        Timestamp now = Timestamp.from(java.time.Instant.now());

        Map<String, long[]> byMonth = new HashMap<>();
        jdbc.query("select to_char(" + TZ + ",'YYYY-MM') m,"
                + " coalesce(sum(amount_cents) filter(where type='INCOME' and not (" + INVESTMENT + ")),0) income,"
                + " coalesce(sum(amount_cents) filter(where type='EXPENSE' and not (" + INVESTMENT + ")),0) expense,"
                + " coalesce(sum(amount_cents) filter(where type='EXPENSE' and (" + INVESTMENT + ")),0) invested"
                + " from financial_entries where user_id=? and occurred_at>=? and occurred_at<=? group by 1",
                rs -> { byMonth.put(rs.getString("m"), new long[]{rs.getLong("income"), rs.getLong("expense"), rs.getLong("invested")}); },
                user, start(thisMonth.minusMonths(monthCount - 1L).atDay(1)), now);
        List<Month> months = new ArrayList<>();
        for (int i = monthCount - 1; i >= 0; i--) {
            String label = thisMonth.minusMonths(i).toString();
            long[] v = byMonth.getOrDefault(label, new long[3]);
            months.add(new Month(label, v[0], v[1], v[2], v[0] - v[1]));
        }

        Map<LocalDate, Long> daily = new HashMap<>();
        jdbc.query("select to_char(" + TZ + ",'YYYY-MM-DD') d, sum(amount_cents) total from financial_entries"
                + " where user_id=? and type='EXPENSE' and not (" + INVESTMENT + ") and occurred_at>=? and occurred_at<=? group by 1",
                rs -> { daily.put(LocalDate.parse(rs.getString("d")), rs.getLong("total")); },
                user, start(previousMonth.atDay(1)), now);
        int elapsed = today.getDayOfMonth(), previousLength = previousMonth.lengthOfMonth();
        List<Long> currentCumulative = new ArrayList<>(), previousCumulative = new ArrayList<>();
        long running = 0, previousSame = 0, previousTotal = 0;
        for (int d = 1; d <= elapsed; d++) { running += daily.getOrDefault(thisMonth.atDay(d), 0L); currentCumulative.add(running); }
        running = 0;
        for (int d = 1; d <= previousLength; d++) {
            running += daily.getOrDefault(previousMonth.atDay(d), 0L);
            previousCumulative.add(running);
            if (d == Math.min(elapsed, previousLength)) previousSame = running;
        }
        previousTotal = running;

        long[] month = byMonth.getOrDefault(thisMonth.toString(), new long[3]);
        long spent = month[1], income = month[0];
        long average = elapsed == 0 ? 0 : Math.round((double) spent / elapsed);
        long projected = Math.round((double) spent / elapsed * thisMonth.lengthOfMonth());
        Double change = previousSame > 0 ? (spent - previousSame) * 100.0 / previousSame : null;
        Double savings = income > 0 ? (income - spent) * 100.0 / income : null;
        var largest = jdbc.query("select amount_cents,description from financial_entries where user_id=? and type='EXPENSE' and not (" + INVESTMENT
                + ") and occurred_at>=? and occurred_at<=? order by amount_cents desc limit 1",
                (r, n) -> Map.entry(r.getLong(1), r.getString(2)), user, start(thisMonth.atDay(1)), now).stream().findFirst();
        var current = new Current(thisMonth.toString(), elapsed, thisMonth.lengthOfMonth(), spent, income, month[2], average, projected,
                previousTotal, previousSame, change, savings, largest.map(Map.Entry::getKey).orElse(0L), largest.map(Map.Entry::getValue).orElse(null));

        Map<String, long[]> categories = new HashMap<>();
        String categorySql = "select lower(category) c, sum(amount_cents) total from financial_entries where user_id=? and type='EXPENSE' and not ("
                + INVESTMENT + ") and occurred_at>=? and occurred_at<? group by 1";
        jdbc.query(categorySql, rs -> { categories.computeIfAbsent(rs.getString("c"), k -> new long[2])[0] = rs.getLong("total"); },
                user, start(thisMonth.atDay(1)), now);
        LocalDate previousEnd = previousMonth.atDay(Math.min(elapsed, previousLength)).plusDays(1);
        jdbc.query(categorySql, rs -> { categories.computeIfAbsent(rs.getString("c"), k -> new long[2])[1] = rs.getLong("total"); },
                user, start(previousMonth.atDay(1)), start(previousEnd));
        List<CategoryChange> changes = categories.entrySet().stream()
                .map(e -> new CategoryChange(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .sorted(Comparator.comparingLong(CategoryChange::currentCents).reversed().thenComparing(Comparator.comparingLong(CategoryChange::previousCents).reversed()))
                .limit(15).toList();

        long[] weekdays = new long[7];
        jdbc.query("select extract(isodow from " + TZ + ")::int dow, sum(amount_cents) total from financial_entries where user_id=? and type='EXPENSE' and not ("
                + INVESTMENT + ") and occurred_at>=? and occurred_at<=? group by 1",
                rs -> { weekdays[rs.getInt("dow") - 1] = rs.getLong("total"); }, user, start(today.minusDays(89)), now);

        return new Insights(FinanceService.ZONE.getId(), today, months, current, changes,
                Arrays.stream(weekdays).boxed().toList(), new Cumulative(currentCumulative, previousCumulative), trend(months, projected));
    }

    /** Regressão linear dos gastos mensais; o mês em andamento entra pela projeção para não parecer queda. */
    static Trend trend(List<Month> months, long projectedCurrent) {
        int n = months.size();
        double[] y = new double[n];
        for (int i = 0; i < n; i++) y[i] = i == n - 1 ? projectedCurrent : months.get(i).expenseCents();
        double sx = 0, sy = 0, sxy = 0, sxx = 0;
        for (int i = 0; i < n; i++) { sx += i; sy += y[i]; sxy += i * y[i]; sxx += (double) i * i; }
        double denominator = n * sxx - sx * sx, slope = denominator == 0 ? 0 : (n * sxy - sx * sy) / denominator;
        double intercept = (sy - slope * sx) / n, mean = sy / n;
        List<Long> line = new ArrayList<>();
        for (int i = 0; i < n; i++) line.add(Math.max(0, Math.round(intercept + slope * i)));
        String direction = mean <= 0 || Math.abs(slope) < mean * 0.03 ? "STABLE" : slope > 0 ? "UP" : "DOWN";
        return new Trend(direction, Math.round(slope), line);
    }
}
