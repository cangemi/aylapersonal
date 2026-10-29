package ayla.investment;

import static ayla.investment.InvestmentData.*;
import ayla.chat.session.ChatIdentity;
import ayla.chat.session.ChatUserRepository;
import ayla.finance.FinanceException;
import ayla.finance.FinanceService;
import ayla.finance.InvestmentCategories;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Investimentos derivados dos lançamentos da categoria Investimento: despesa = aporte, receita = resgate.
 * O rendimento é calculado sobre o CDI diário do Banco Central e nunca vira lançamento financeiro.
 */
@Service
public class InvestmentService {
    public static final String NAME = "Caixinha Nubank";
    static final String SOURCE = "Banco Central do Brasil (SGS 12)";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private final JdbcTemplate jdbc;
    private final CdiService cdi;
    private final ChatUserRepository users;
    private final ChatIdentity identity;

    public InvestmentService(JdbcTemplate jdbc, CdiService cdi, ChatUserRepository users, ChatIdentity identity) {
        this.jdbc = jdbc; this.cdi = cdi; this.users = users; this.identity = identity;
    }

    private record Entry(UUID id, String type, long cents, String description, LocalDate date) {}

    public BigDecimal percent(String user) {
        return jdbc.query("select cdi_percent from investment_settings where user_id=?", (r, n) -> r.getBigDecimal(1), user)
                .stream().findFirst().orElse(HUNDRED).setScale(2, RoundingMode.HALF_UP);
    }

    @Transactional
    public Settings saveSettings(String user, BigDecimal percent) {
        users.ensureUser(user, user.equals(identity.ownerId()) ? "default" : null);
        jdbc.update("insert into investment_settings(user_id,cdi_percent) values (?,?) on conflict (user_id) do update set cdi_percent=excluded.cdi_percent, updated_at=now()",
                user, percent);
        return new Settings(percent(user));
    }

    public Overview overview(String user) { return overview(user, LocalDate.now(FinanceService.ZONE)); }

    public Optional<Baseline> baseline(String user) {
        return jdbc.query("select baseline_date,baseline_balance_cents,                baseline_invested_cents,baseline_month_yield_cents from investment_settings where user_id=? and baseline_date is not null",
                                (r, n) -> new Baseline(r.getDate(1).toLocalDate(), r.getLong(2), r.getLong(3), r.getObject(4, Long.class)), user).stream().findFirst();
    }

    @Transactional
    public Baseline saveBaseline(String user, BaselineInput input) {
        LocalDate today = LocalDate.now(FinanceService.ZONE), date = input.date() == null ? today : input.date();
        if (date.isAfter(today)) throw new FinanceException(400, "A data do saldo não pode ser futura.");
        long invested = input.investedCents() == null ? input.balanceCents() : input.investedCents();
        users.ensureUser(user, user.equals(identity.ownerId()) ? "default" : null);
        Long monthYield = input.monthYieldCents();
        jdbc.update("insert into investment_settings(user_id,baseline_date,baseline_balance_cents,baseline_invested_cents,baseline_month_yield_cents) values (?,?,?,?,?)"
                + " on conflict (user_id) do update set baseline_date=excluded.baseline_date, baseline_balance_cents=excluded.baseline_balance_cents,"
                + " baseline_invested_cents=excluded.baseline_invested_cents, baseline_month_yield_cents=excluded.baseline_month_yield_cents, updated_at=now()",
                user, date, input.balanceCents(), invested, monthYield);
        return new Baseline(date, input.balanceCents(), invested, monthYield);
    }

    @Transactional
    public void clearBaseline(String user) {
        jdbc.update("update investment_settings set baseline_date=null, baseline_balance_cents=null, baseline_invested_cents=null, baseline_month_yield_cents=null, updated_at=now() where user_id=?", user);
    }

    Overview overview(String user, LocalDate today) {
        BigDecimal percent = percent(user), fraction = percent.divide(HUNDRED);
        var baseline = baseline(user).orElse(null);
        var allEntries = jdbc.query("select id,type,amount_cents,description,occurred_at from financial_entries where user_id=? and lower(category) in ("
                + InvestmentCategories.SQL_LIST + ") and occurred_at<=? order by occurred_at,id",
                (r, n) -> new Entry(r.getObject("id", UUID.class), r.getString("type"), r.getLong("amount_cents"), r.getString("description"),
                        r.getTimestamp("occurred_at").toInstant().atZone(FinanceService.ZONE).toLocalDate()),
                user, java.sql.Timestamp.from(java.time.Instant.now()));
        var entries = baseline == null ? allEntries : allEntries.stream().filter(e -> e.date().isAfter(baseline.date())).toList();
        LocalDate first = baseline != null ? baseline.date() : entries.isEmpty() ? today : entries.getFirst().date();
        var snapshot = cdi.load(first.isBefore(today.minusDays(130)) ? first : today.minusDays(130), today);
        BigDecimal factor = InvestmentEngine.factor(snapshot.latestFraction(), fraction);
        var cdiInfo = cdiInfo(snapshot);
        BigDecimal productAnnual = InvestmentEngine.annualPercentFromDaily(snapshot.latestFraction().multiply(fraction)).setScale(2, RoundingMode.HALF_UP);
        if (entries.isEmpty() && baseline == null)
            return new Overview(NAME, percent, false, today, cdiInfo, productAnnual, 0, 0, 0, 0, 0, BigDecimal.ZERO,
                    0, null, 0, 0, 0, null, 0, YearMonth.from(today.plusMonths(1)).toString(), List.of(), List.of(), List.of(), List.of(), null);

        var moves = new ArrayList<InvestmentEngine.Move>();
        if (baseline != null) moves.add(new InvestmentEngine.Move(baseline.date(), baseline.balanceCents()));
        entries.forEach(e -> moves.add(new InvestmentEngine.Move(e.date(), "EXPENSE".equals(e.type()) ? e.cents() : -e.cents())));
        var days = InvestmentEngine.replay(moves, snapshot.fractions(), fraction, today);
        var last = days.getLast();
        long offset = baseline == null ? 0 : baseline.investedCents() - baseline.balanceCents();
        long deposited = (baseline == null ? 0 : baseline.investedCents()) + entries.stream().filter(e -> "EXPENSE".equals(e.type())).mapToLong(Entry::cents).sum();
        long withdrawn = entries.stream().filter(e -> !"EXPENSE".equals(e.type())).mapToLong(Entry::cents).sum();
        long balance = InvestmentEngine.cents(last.balance()), net = InvestmentEngine.cents(last.net()) + offset;
        long gain = balance - net;
        BigDecimal gainPercent = deposited > 0 ? BigDecimal.valueOf(gain).multiply(HUNDRED).divide(BigDecimal.valueOf(deposited), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        InvestmentEngine.Day lastAccrued = null;
        for (int i = days.size() - 1; i >= 0 && lastAccrued == null; i--) if (days.get(i).accrued()) lastAccrued = days.get(i);
        LocalDate lastMove = entries.isEmpty() ? baseline.date() : entries.getLast().date();
        LocalDate base = lastAccrued == null || lastMove.isAfter(lastAccrued.date()) ? lastMove : lastAccrued.date();
        long lastYield = lastAccrued == null ? 0 : InvestmentEngine.cents(lastAccrued.yield());
        long nextDay = InvestmentEngine.cents(last.balance().multiply(snapshot.latestFraction(), InvestmentEngine.MC).multiply(fraction, InvestmentEngine.MC));

        List<Point> history = new ArrayList<>();
        for (var d : days.subList(Math.max(0, days.size() - 365), days.size()))
            history.add(new Point(d.date(), InvestmentEngine.cents(d.balance()), InvestmentEngine.cents(d.net()) + offset, InvestmentEngine.cents(d.yield())));

        Map<YearMonth, long[]> grouped = new LinkedHashMap<>();
        BigDecimal previousNet = BigDecimal.ZERO;
        for (var d : days) {
            long[] bucket = grouped.computeIfAbsent(YearMonth.from(d.date()), k -> new long[3]);
            bucket[0] += InvestmentEngine.cents(d.yield());
            bucket[1] += InvestmentEngine.cents(d.net().subtract(previousNet));
            bucket[2] = InvestmentEngine.cents(d.balance());
            previousNet = d.net();
        }
        List<MonthYield> allMonths = new ArrayList<>();
        grouped.forEach((month, v) -> allMonths.add(new MonthYield(month.toString(), v[0], v[1], v[2])));
        List<MonthYield> months = allMonths.subList(Math.max(0, allMonths.size() - 12), allMonths.size());
        long monthYield = grouped.getOrDefault(YearMonth.from(today), new long[3])[0];
        if (baseline != null && YearMonth.from(baseline.date()).equals(YearMonth.from(today))) {
            if (baseline.monthYieldCents() != null) monthYield += baseline.monthYieldCents();
            else {
                // sem o dado do app: desfaz o CDI do início do mês até a data do saldo (ignora aportes/resgates no mês)
                BigDecimal growth = BigDecimal.ONE;
                for (var f : snapshot.fractions().entrySet())
                    if (!f.getKey().isBefore(today.withDayOfMonth(1)) && !f.getKey().isAfter(baseline.date()))
                        growth = growth.multiply(BigDecimal.ONE.add(f.getValue().multiply(fraction)), InvestmentEngine.MC);
                monthYield += baseline.balanceCents() - InvestmentEngine.cents(BigDecimal.valueOf(baseline.balanceCents()).divide(growth, InvestmentEngine.MC));
            }
        }
        long thirty = days.stream().filter(d -> d.date().isAfter(today.minusDays(30))).mapToLong(d -> InvestmentEngine.cents(d.yield())).sum();

        LocalDate endMonth = today.withDayOfMonth(today.lengthOfMonth()), endNext = today.plusMonths(1).withDayOfMonth(today.plusMonths(1).lengthOfMonth());
        var targets = new LinkedHashMap<String, LocalDate>();
        targets.put("Fim deste mês", endMonth);
        targets.put("Fim do próximo mês", endNext);
        targets.put("Em 3 meses", today.plusMonths(3));
        targets.put("Em 6 meses", today.plusMonths(6));
        targets.put("Em 1 ano", today.plusYears(1));
        List<Projection> projections = new ArrayList<>();
        targets.forEach((label, date) -> {
            long projected = InvestmentEngine.cents(InvestmentEngine.grow(last.balance(), factor, InvestmentCalendar.businessDaysAfter(base, date)));
            projections.add(new Projection(label, date, projected, projected - balance));
        });
        long nextMonthYield = projections.get(1).balanceCents() - projections.get(0).balanceCents();

        List<Movement> movements = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0 && movements.size() < 30; i--) {
            var e = entries.get(i);
            movements.add(new Movement(e.id(), e.date(), "EXPENSE".equals(e.type()) ? "DEPOSIT" : "WITHDRAWAL", e.cents(), e.description()));
        }
        return new Overview(NAME, percent, true, today, cdiInfo, productAnnual, balance, deposited, withdrawn, net, gain, gainPercent,
                lastYield, lastAccrued == null ? null : lastAccrued.date(), monthYield, thirty,
                nextDay, InvestmentCalendar.nextBusinessDay(base), nextMonthYield, YearMonth.from(today.plusMonths(1)).toString(),
                projections, history, months, movements, baseline);
    }

    private static Cdi cdiInfo(CdiService.Snapshot snapshot) {
        List<CdiPoint> history = new ArrayList<>();
        var all = new ArrayList<>(snapshot.fractions().entrySet());
        for (var e : all.subList(Math.max(0, all.size() - 90), all.size()))
            history.add(new CdiPoint(e.getKey(), e.getValue().multiply(HUNDRED).setScale(6, RoundingMode.HALF_UP),
                    InvestmentEngine.annualPercentFromDaily(e.getValue()).setScale(2, RoundingMode.HALF_UP)));
        return new Cdi(snapshot.latestFraction().multiply(HUNDRED).setScale(6, RoundingMode.HALF_UP),
                InvestmentEngine.annualPercentFromDaily(snapshot.latestFraction()).setScale(2, RoundingMode.HALF_UP),
                snapshot.latestDate(), snapshot.stale(), SOURCE, history);
    }

    public Simulation simulate(String user, SimulationInput input) {
        if (input == null || input.months() == null || input.months() < 1 || input.months() > 600)
            throw new FinanceException(400, "Informe de 1 a 600 meses.");
        LocalDate today = LocalDate.now(FinanceService.ZONE);
        var snapshot = cdi.load(today.minusDays(10), today);
        BigDecimal percent = input.percentCdi() != null ? input.percentCdi() : percent(user);
        BigDecimal daily = input.cdiAnnualPercent() != null ? InvestmentEngine.dailyFromAnnualPercent(input.cdiAnnualPercent()) : snapshot.latestFraction();
        long initial = input.initialCents() != null ? input.initialCents() : overview(user, today).balanceCents();
        long monthly = input.monthlyCents() == null ? 0 : input.monthlyCents();
        BigDecimal factor = InvestmentEngine.factor(daily, percent.divide(HUNDRED));
        var points = InvestmentEngine.simulate(BigDecimal.valueOf(initial), BigDecimal.valueOf(monthly), input.months(), today, factor);
        List<SimulationPoint> out = points.stream().map(p -> {
            long balance = InvestmentEngine.cents(p.balance()), invested = InvestmentEngine.cents(p.invested());
            return new SimulationPoint(p.month(), p.date(), balance, invested, balance - invested);
        }).toList();
        var finalPoint = out.getLast();
        return new Simulation(InvestmentEngine.annualPercentFromDaily(daily).setScale(2, RoundingMode.HALF_UP), percent,
                InvestmentEngine.annualPercentFromDaily(daily.multiply(percent.divide(HUNDRED))).setScale(2, RoundingMode.HALF_UP),
                initial, monthly, input.months(), finalPoint.balanceCents(), finalPoint.investedCents(), finalPoint.gainCents(), out);
    }
}
