package ayla.investment;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InvestmentEngineTest {
    static final BigDecimal DAILY = new BigDecimal("0.00050788");

    @Test void calendarKnowsWeekendsAndBrazilianHolidays() {
        assertFalse(InvestmentCalendar.businessDay(LocalDate.of(2026, 10, 10)));
        assertFalse(InvestmentCalendar.businessDay(LocalDate.of(2026, 10, 12)));
        assertFalse(InvestmentCalendar.businessDay(LocalDate.of(2026, 4, 3)));
        assertFalse(InvestmentCalendar.businessDay(LocalDate.of(2026, 2, 17)));
        assertFalse(InvestmentCalendar.businessDay(LocalDate.of(2026, 6, 4)));
        assertTrue(InvestmentCalendar.businessDay(LocalDate.of(2026, 10, 13)));
        assertEquals(LocalDate.of(2026, 10, 13), InvestmentCalendar.nextBusinessDay(LocalDate.of(2026, 10, 9)));
        assertEquals(LocalDate.of(2026, 10, 9), InvestmentCalendar.previousBusinessDay(LocalDate.of(2026, 10, 13)));
        assertEquals(4, InvestmentCalendar.businessDaysAfter(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 9)));
    }

    @Test void annualRateMatchesPublishedCdi() {
        double annual = InvestmentEngine.annualPercentFromDaily(DAILY).doubleValue();
        assertEquals(13.65, annual, 0.02);
        assertEquals(DAILY.doubleValue(), InvestmentEngine.dailyFromAnnualPercent(new BigDecimal("13.65")).doubleValue(), 0.0000005);
    }

    @Test void depositOnlyYieldsFromTheNextBusinessDay() {
        LocalDate monday = LocalDate.of(2026, 10, 5);
        Map<LocalDate, BigDecimal> rates = new java.util.HashMap<>();
        for (int i = 0; i < 5; i++) rates.put(monday.plusDays(i), DAILY);
        var days = InvestmentEngine.replay(List.of(new InvestmentEngine.Move(monday, 1_000_000)), rates, BigDecimal.ONE, monday.plusDays(4));
        assertEquals(0, days.getFirst().yield().signum());
        long expected = Math.round(1_000_000 * Math.pow(1.00050788, 4));
        assertEquals(expected, InvestmentEngine.cents(days.getLast().balance()), 1);
        assertEquals(1_000_000, InvestmentEngine.cents(days.getLast().net()));
    }

    @Test void percentOfCdiScalesTheYieldAndWithdrawalsReduceTheBase() {
        LocalDate monday = LocalDate.of(2026, 10, 5);
        Map<LocalDate, BigDecimal> rates = Map.of(monday.plusDays(1), DAILY, monday.plusDays(2), DAILY);
        var half = InvestmentEngine.replay(List.of(new InvestmentEngine.Move(monday, 1_000_000)), rates, new BigDecimal("0.5"), monday.plusDays(2));
        var full = InvestmentEngine.replay(List.of(new InvestmentEngine.Move(monday, 1_000_000)), rates, BigDecimal.ONE, monday.plusDays(2));
        assertTrue(half.getLast().balance().subtract(BigDecimal.valueOf(1_000_000)).doubleValue()
                < full.getLast().balance().subtract(BigDecimal.valueOf(1_000_000)).doubleValue() * 0.51);
        var withdrawn = InvestmentEngine.replay(List.of(new InvestmentEngine.Move(monday, 1_000_000),
                new InvestmentEngine.Move(monday.plusDays(1), -500_000)), rates, BigDecimal.ONE, monday.plusDays(2));
        assertEquals(500_000, InvestmentEngine.cents(withdrawn.getLast().net()));
        assertTrue(withdrawn.getLast().balance().doubleValue() < 520_000);
    }

    @Test void simulationCompoundsAndAddsMonthlyDeposits() {
        var factor = InvestmentEngine.factor(DAILY, BigDecimal.ONE);
        LocalDate start = LocalDate.of(2026, 10, 25);
        var lump = InvestmentEngine.simulate(BigDecimal.valueOf(1_000_000), BigDecimal.ZERO, 12, start, factor);
        assertEquals(13, lump.size());
        double gain = lump.getLast().balance().doubleValue() / 1_000_000 - 1;
        assertTrue(gain > 0.125 && gain < 0.142, "ganho anual " + gain);
        var monthly = InvestmentEngine.simulate(BigDecimal.ZERO, BigDecimal.valueOf(100_000), 12, start, factor);
        assertEquals(1_200_000, InvestmentEngine.cents(monthly.getLast().invested()));
        assertTrue(monthly.getLast().balance().doubleValue() > 1_200_000);
    }

    @Test void growIsIdentityWithoutBusinessDays() {
        assertEquals(0, BigDecimal.TEN.compareTo(InvestmentEngine.grow(BigDecimal.TEN, InvestmentEngine.factor(DAILY, BigDecimal.ONE), 0)));
    }
}
