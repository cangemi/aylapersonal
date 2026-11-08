package ayla.investment;

import static ayla.investment.PortfolioData.*;
import ayla.finance.FinanceException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/** Values stay in cents throughout; valuations change market value, never contributed capital. */
final class PortfolioEngine {
    private PortfolioEngine() {}
    static View replay(Position position, List<Event> events, CdiService.Snapshot cdi, LocalDate today) {
        return evaluate(position,events,cdi,today,null).view();
    }
    static InvestmentAnalytics.Holding analyze(String id,Position position,List<Event> events,CdiService.Snapshot cdi,LocalDate today,int year){
        var result=evaluate(position,events,cdi,today,new InvestmentAnalytics.Collector(position.openedOn(),today,year));
        return new InvestmentAnalytics.Holding(id,position.name(),position.institution(),position.assetType(),result.view().balanceCents(),
            result.view().netInvestedCents(),result.view().gainCents(),result.performance());
    }
    private record Result(View view,InvestmentAnalytics.Performance performance) {}
    private static Result evaluate(Position position,List<Event> events,CdiService.Snapshot cdi,LocalDate today,InvestmentAnalytics.Collector analytics) {
        var ordered = events.stream().sorted(Comparator.comparing(Event::date)).toList();
        BigDecimal balance = BigDecimal.valueOf(position.openingBalanceCents());
        long deposited = position.openingInvestedCents(), withdrawn = 0, income = 0;
        long initialGain = position.openingBalanceCents() - deposited, previousGain = initialGain, monthGain = 0;
        var rate = position.rate();
        LocalDate lastValuation = position.openedOn();
        var history = new ArrayList<Point>();
        boolean missingCdi = false;
        boolean balanceKnown = true;
        int next = 0;
        for (LocalDate date = position.openedOn(); !date.isAfter(today); date = date.plusDays(1)) {
            long before=cents(balance),dayDeposits=0,dayWithdrawals=0,dayIncome=0;
            boolean startKnown=balanceKnown,dayEstimated=rate.type()!=RateType.MANUAL;
            if(date.isAfter(position.openedOn())&&rate.type()==RateType.MANUAL&&balance.signum()>0)balanceKnown=false;
            if (date.isAfter(position.openedOn()) && (position.maturityDate() == null || !date.isAfter(position.maturityDate()))) {
                BigDecimal daily = daily(rate, date, cdi, false);
                if (daily == null && balance.signum()>0 && rate.percent().signum()>0) { missingCdi = true; balanceKnown=false; }
                else if(daily!=null) balance = balance.multiply(BigDecimal.ONE.add(daily), InvestmentEngine.MC);
            }
            while (next < ordered.size() && ordered.get(next).date().equals(date)) {
                var event = ordered.get(next++);
                switch (event.type()) {
                    case DEPOSIT -> { balance = balance.add(BigDecimal.valueOf(event.amountCents())); deposited = Math.addExact(deposited,event.amountCents()); dayDeposits+=event.amountCents(); }
                    case WITHDRAWAL -> { balance = balance.subtract(BigDecimal.valueOf(event.amountCents())); withdrawn = Math.addExact(withdrawn,event.amountCents()); dayWithdrawals+=event.amountCents(); }
                    case INCOME -> {
                        income = Math.addExact(income,event.amountCents());
                        dayIncome+=event.amountCents();
                        // Estimated interest is already capitalized. A payout transfers it out of the holding.
                        if (rate.type() != RateType.MANUAL) balance = balance.subtract(BigDecimal.valueOf(event.amountCents()));
                    }
                    case VALUATION -> { balance = BigDecimal.valueOf(event.amountCents()); lastValuation = date; balanceKnown=true; }
                    case RATE_CHANGE -> rate = event.rate();
                }
                if (balance.signum() < 0) {
                    if (cents(balance) == 0) balance = BigDecimal.ZERO;
                    else throw new FinanceException(400,"A saída excede o saldo disponível na data. Confira o valor ou atualize o saldo pelo extrato.");
                }
            }
            long value = cents(balance), net = deposited - withdrawn, gain = value - net + income;
            if (date.getYear() == today.getYear() && date.getMonth() == today.getMonth()) monthGain += gain - previousGain;
            if(analytics!=null)analytics.add(date,before,value,net,gain,gain-previousGain,dayDeposits,dayWithdrawals,dayIncome,
                startKnown,balanceKnown,dayEstimated);
            previousGain = gain;
            if (!date.isBefore(today.minusDays(365))) history.add(new Point(date,value,net,gain));
        }
        var warnings = new ArrayList<String>();
        if (missingCdi) warnings.add("Há dias sem CDI publicado no histórico. O saldo não inclui rendimento nesses dias; confira com o extrato.");
        if (rate.type() == RateType.CDI && (cdi == null || cdi.stale())) warnings.add("CDI indisponível ou desatualizado. A estimativa usa somente as taxas disponíveis.");
        boolean manual = rate.type() == RateType.MANUAL;
        if (manual) warnings.add("Saldo informado manualmente. Atualize o valor de mercado para acompanhar ganhos e perdas.");
        Long projection = null;
        if (!manual && (rate.type() != RateType.CDI || cdi != null)) {
            BigDecimal projected = balance;
            for (LocalDate date = today.plusDays(1); !date.isAfter(today.plusDays(30)); date = date.plusDays(1)) {
                if (position.maturityDate() != null && date.isAfter(position.maturityDate())) break;
                BigDecimal daily = daily(rate,date,cdi,true);
                projected = projected.multiply(BigDecimal.ONE.add(daily),InvestmentEngine.MC);
            }
            projection = cents(projected) - cents(balance);
        }
        long value = cents(balance), net = deposited - withdrawn, gain = value - net + income;
        var percent = deposited == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(gain).multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(deposited),2,RoundingMode.HALF_UP);
        return new Result(new View(position,rate,value,deposited,withdrawn,income,net,gain,percent,monthGain,projection,
            lastValuation,!manual,List.copyOf(warnings),List.copyOf(history),List.copyOf(events)),analytics==null?null:analytics.finish(lastValuation,rate));
    }

    private static BigDecimal daily(Rate rate, LocalDate date, CdiService.Snapshot cdi, boolean projection) {
        if (rate.type() == RateType.MANUAL) return BigDecimal.ZERO;
        if ((rate.type() == RateType.CDI || rate.dayBasis() == DayBasis.BUSINESS) && !InvestmentCalendar.businessDay(date)) return BigDecimal.ZERO;
        if (rate.type() == RateType.CDI) {
            if (cdi == null) return null;
            BigDecimal daily = projection ? cdi.latestFraction() : cdi.fractions().get(date);
            return daily == null ? null : daily.multiply(rate.percent().movePointLeft(2),InvestmentEngine.MC);
        }
        double periods = rate.type() == RateType.ANNUAL
            ? (rate.dayBasis() == DayBasis.BUSINESS ? 252.0 : 365.0)
            : (rate.dayBasis() == DayBasis.BUSINESS ? 21.0 : 30.0);
        return BigDecimal.valueOf(Math.expm1(Math.log1p(rate.percent().doubleValue()/100.0)/periods));
    }

    private static long cents(BigDecimal value) {
        try {
            long cents = value.setScale(0,RoundingMode.HALF_UP).longValueExact();
            if (cents > 1_000_000_000_000_000L) throw new ArithmeticException();
            return cents;
        } catch (ArithmeticException error) { throw new FinanceException(400,"O saldo calculado excede o limite. Confira os valores e as taxas informadas."); }
    }
}
