package ayla.investment;

import static ayla.investment.PortfolioData.*;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InvestmentAnalyticsTest {
    private Position position(String from,RateType type,long balance,long capital){
        return new Position(UUID.randomUUID(),"Reserva","Banco",AssetType.CDB,"",LocalDate.parse(from),balance,capital,
            new Rate(type,type==RateType.CDI?new BigDecimal("100"):BigDecimal.ZERO,DayBasis.BUSINESS),null,"",0);
    }
    private Event event(EventType type,String date,long amount){return new Event(UUID.randomUUID(),type,LocalDate.parse(date),amount,"",null);}
    private InvestmentAnalytics.Performance analyze(Position p,List<Event> events,CdiService.Snapshot cdi,String today,int year){
        return PortfolioEngine.analyze("test",p,events,cdi,LocalDate.parse(today),year).performance();
    }

    @Test void dailyMonthlyAndAnnualReturnsExcludeCashFlowsAndKeepSelectedYearSeparateFromToday(){
        var p=position("2025-12-30",RateType.CDI,100_000,100_000);
        var cdi=new CdiService.Snapshot(Map.of(LocalDate.parse("2025-12-31"),new BigDecimal("0.001"),
            LocalDate.parse("2026-01-02"),new BigDecimal("0.002"),LocalDate.parse("2026-01-05"),new BigDecimal("0.003")),
            LocalDate.parse("2026-01-05"),new BigDecimal("0.003"),false);
        var result=analyze(p,List.of(event(EventType.DEPOSIT,"2026-01-02",50_000),event(EventType.WITHDRAWAL,"2026-01-05",10_000)),cdi,"2026-01-05",2025);
        assertEquals(451L,result.day().gainCents());
        assertEquals(651L,result.month().gainCents());
        assertEquals(100L,result.year().gainCents());
        assertEquals(50_000,result.month().depositsCents());
        assertEquals(10_000,result.month().withdrawalsCents());
        assertEquals(751,result.years().stream().mapToLong(period->period.gainCents()).sum());
        assertEquals(2,result.days().size());
        assertTrue(result.days().stream().allMatch(day->day.key().startsWith("2025-")));
    }

    @Test void aManualMonthlyReturnCanBeKnownWithoutInventingDailyPrices(){
        var result=analyze(position("2026-10-31",RateType.MANUAL,100_000,100_000),List.of(
            event(EventType.DEPOSIT,"2026-11-02",50_000),event(EventType.VALUATION,"2026-11-04",165_000)),null,"2026-11-04",2026);
        assertEquals(15_000L,result.month().gainCents());
        assertNull(result.day().gainCents());
        assertEquals(15_000,result.day().observedGainCents());
        assertFalse(result.month().estimated());
    }

    @Test void anOutdatedManualBalanceIsUnknownRatherThanZeroReturn(){
        var result=analyze(position("2026-10-31",RateType.MANUAL,100_000,100_000),List.of(),null,"2026-11-04",2026);
        assertNull(result.day().gainCents());
        assertNull(result.month().gainCents());
        assertFalse(result.month().complete());
    }

    @Test void annualHistorySurvivesThe365DayLimitAndIncludesLeapYears(){
        var result=analyze(position("2023-12-31",RateType.MANUAL,100_000,100_000),List.of(
            event(EventType.VALUATION,"2024-12-31",110_000),event(EventType.VALUATION,"2025-12-31",121_000),
            event(EventType.VALUATION,"2026-11-04",130_000)),null,"2026-11-04",2024);
        assertEquals(366,result.days().size());
        assertEquals(10_000L,result.year().gainCents());
        assertEquals(11_000L,result.years().stream().filter(p->p.key().equals("2025")).findFirst().orElseThrow().gainCents());
        assertEquals(9_000L,result.years().stream().filter(p->p.key().equals("2026")).findFirst().orElseThrow().gainCents());
    }

    @Test void initialAccumulatedGainIsNotAttributedToTheCurrentDayMonthOrYear(){
        var result=analyze(position("2026-11-01",RateType.ANNUAL,120_000,100_000),List.of(),null,"2026-11-04",2026);
        assertEquals(20_000,result.day().accumulatedGainCents());
        assertEquals(0L,result.day().gainCents());
        assertEquals(0L,result.month().gainCents());
        assertEquals(0L,result.year().gainCents());
        assertTrue(result.month().complete());
        assertTrue(result.year().partial());
    }

    @Test void missingCdiMakesTheIntervalIncompleteAndAnActualValuationCanReconcileIt(){
        var p=position("2026-11-02",RateType.CDI,100_000,100_000);
        var cdi=new CdiService.Snapshot(Map.of(LocalDate.parse("2026-11-03"),new BigDecimal("0.001")),
            LocalDate.parse("2026-11-03"),new BigDecimal("0.001"),false);
        var missing=analyze(p,List.of(),cdi,"2026-11-04",2026);
        assertNull(missing.day().gainCents());
        assertNull(missing.month().gainCents());
        assertEquals(100,missing.month().observedGainCents());
        var reconciled=analyze(p,List.of(event(EventType.VALUATION,"2026-11-04",100_110)),cdi,"2026-11-04",2026);
        assertEquals(110L,reconciled.month().gainCents());
        assertEquals(10L,reconciled.day().gainCents());
    }
}
