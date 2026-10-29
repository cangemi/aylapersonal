package ayla.investment;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Cálculo puro de rendimento por CDI: juros compostos diários nos dias úteis. */
final class InvestmentEngine {
    static final MathContext MC = MathContext.DECIMAL128;
    static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    static final int BUSINESS_DAYS_PER_YEAR = 252;
    private InvestmentEngine() {}

    /** cents positivo é aporte; negativo é resgate. */
    record Move(LocalDate date, long cents) {}
    record Day(LocalDate date, BigDecimal balance, BigDecimal net, BigDecimal yield, boolean accrued) {}
    record SimPoint(int month, LocalDate date, BigDecimal balance, BigDecimal invested) {}

    /** O rendimento do dia incide sobre o saldo do dia anterior; aportes e resgates do dia só rendem a partir do seguinte. */
    static List<Day> replay(List<Move> moves, Map<LocalDate, BigDecimal> dailyFraction, BigDecimal percentFraction, LocalDate end) {
        List<Day> days = new ArrayList<>();
        if (moves.isEmpty()) return days;
        var sorted = moves.stream().sorted(java.util.Comparator.comparing(Move::date)).toList();
        BigDecimal balance = BigDecimal.ZERO, net = BigDecimal.ZERO;
        int next = 0;
        for (LocalDate d = sorted.getFirst().date(); !d.isAfter(end); d = d.plusDays(1)) {
            BigDecimal rate = dailyFraction.get(d), yield = BigDecimal.ZERO;
            if (rate != null) {
                yield = balance.multiply(rate, MC).multiply(percentFraction, MC);
                balance = balance.add(yield);
            }
            while (next < sorted.size() && sorted.get(next).date().equals(d)) {
                BigDecimal cents = BigDecimal.valueOf(sorted.get(next++).cents());
                balance = balance.add(cents);
                net = net.add(cents);
            }
            days.add(new Day(d, balance, net, yield, rate != null));
        }
        return days;
    }

    static BigDecimal factor(BigDecimal dailyFraction, BigDecimal percentFraction) {
        return BigDecimal.ONE.add(dailyFraction.multiply(percentFraction, MC), MC);
    }

    static BigDecimal grow(BigDecimal balance, BigDecimal factor, int businessDays) {
        return businessDays <= 0 ? balance : balance.multiply(factor.pow(businessDays, MC), MC);
    }

    /** Taxa diária equivalente a um CDI anual (base 252). */
    static BigDecimal dailyFromAnnualPercent(BigDecimal annualPercent) {
        double annual = annualPercent.doubleValue() / 100.0;
        return BigDecimal.valueOf(Math.pow(1.0 + annual, 1.0 / BUSINESS_DAYS_PER_YEAR) - 1.0);
    }

    static BigDecimal annualPercentFromDaily(BigDecimal dailyFraction) {
        return BigDecimal.ONE.add(dailyFraction, MC).pow(BUSINESS_DAYS_PER_YEAR, MC).subtract(BigDecimal.ONE).multiply(HUNDRED);
    }

    /** O aporte mensal é feito ao fim de cada mês simulado e começa a render no dia seguinte. */
    static List<SimPoint> simulate(BigDecimal initial, BigDecimal monthly, int months, LocalDate start, BigDecimal factor) {
        List<SimPoint> points = new ArrayList<>();
        BigDecimal balance = initial, invested = initial;
        points.add(new SimPoint(0, start, balance, invested));
        LocalDate previous = start;
        for (int month = 1; month <= months; month++) {
            LocalDate current = start.plusMonths(month);
            balance = grow(balance, factor, InvestmentCalendar.businessDaysAfter(previous, current)).add(monthly);
            invested = invested.add(monthly);
            points.add(new SimPoint(month, current, balance, invested));
            previous = current;
        }
        return points;
    }

    static long cents(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_EVEN).longValueExact();
    }
}
