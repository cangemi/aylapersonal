package ayla.investment;

import static ayla.investment.PortfolioData.*;
import static org.junit.jupiter.api.Assertions.*;
import ayla.finance.FinanceException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PortfolioEngineTest {
    private static final LocalDate START=LocalDate.of(2026,11,3);
    private static Position position(Rate rate,Long balance,Long invested,LocalDate maturity){
        return new Position(UUID.randomUUID(),"Reserva","Outro banco",AssetType.CDB,"",START,balance,invested,rate,maturity,"",0);
    }
    private static Event event(EventType type,LocalDate date,long amount){return new Event(UUID.randomUUID(),type,date,amount,"",null);}
    private static Rate rate(RateType type,String percent,DayBasis basis){return new Rate(type,new BigDecimal(percent),basis);}

    @Test void manualValuationsPreserveCapitalAndIncludeLossesAndDistributedIncome(){
        var p=position(rate(RateType.MANUAL,"0",DayBasis.CALENDAR),110_000L,100_000L,null);
        var result=PortfolioEngine.replay(p,List.of(event(EventType.DEPOSIT,START.plusDays(1),20_000),
            event(EventType.VALUATION,START.plusDays(2),90_000),event(EventType.INCOME,START.plusDays(2),3_000),
            event(EventType.WITHDRAWAL,START.plusDays(3),10_000)),null,START.plusDays(3));
        assertEquals(80_000,result.balanceCents());
        assertEquals(110_000,result.netInvestedCents());
        assertEquals(-27_000,result.gainCents());
        assertEquals(-37_000,result.monthGainCents()); // Initial R$100 gain predates this tracking period.
        assertFalse(result.estimated());
        assertNull(result.next30DaysGainCents());
    }

    @Test void cdiAccruesOnPriorBalanceAndNeverInventsMissingRates(){
        var p=position(rate(RateType.CDI,"110",DayBasis.BUSINESS),100_000L,100_000L,null);
        var cdi=new CdiService.Snapshot(Map.of(START.plusDays(1),new BigDecimal("0.001")),START.plusDays(1),new BigDecimal("0.001"),false);
        var result=PortfolioEngine.replay(p,List.of(event(EventType.DEPOSIT,START.plusDays(1),100_000)),cdi,START.plusDays(2));
        assertEquals(200_110,result.balanceCents());
        assertEquals(110,result.gainCents());
        assertTrue(result.warnings().stream().anyMatch(w->w.contains("dias sem CDI")));
    }

    @Test void fixedMonthlyRateCompoundsAndStopsAtMaturity(){
        var p=position(rate(RateType.MONTHLY,"1",DayBasis.CALENDAR),1_000_000L,1_000_000L,START.plusDays(30));
        var result=PortfolioEngine.replay(p,List.of(),null,START.plusDays(40));
        assertEquals(1_010_000,result.balanceCents());
        assertEquals(0,result.next30DaysGainCents());
    }

    @Test void rateChangeAppliesOnlyToTheFollowingDaysAndValuationReconcilesBalance(){
        var p=position(rate(RateType.MONTHLY,"0",DayBasis.CALENDAR),1_000_000L,1_000_000L,null);
        var change=new Event(UUID.randomUUID(),EventType.RATE_CHANGE,START.plusDays(5),0,"",rate(RateType.MONTHLY,"1",DayBasis.CALENDAR));
        var result=PortfolioEngine.replay(p,List.of(change),null,START.plusDays(35));
        assertEquals(1_010_000,result.balanceCents());
        var reconciled=PortfolioEngine.replay(p,List.of(change,event(EventType.VALUATION,START.plusDays(35),1_005_000)),null,START.plusDays(35));
        assertEquals(1_005_000,reconciled.balanceCents());
        assertEquals(5_000,reconciled.gainCents());
    }

    @Test void backdatedWithdrawalCannotMakeAnyHistoricalBalanceNegative(){
        var p=position(rate(RateType.MANUAL,"0",DayBasis.CALENDAR),10_000L,10_000L,null);
        assertThrows(FinanceException.class,()->PortfolioEngine.replay(p,List.of(event(EventType.WITHDRAWAL,START,11_000),
            event(EventType.DEPOSIT,START.plusDays(1),20_000)),null,START.plusDays(1)));
    }

    @Test void distributingEstimatedInterestDoesNotCountItTwice(){
        var p=position(rate(RateType.MONTHLY,"1",DayBasis.CALENDAR),1_000_000L,1_000_000L,null);
        var result=PortfolioEngine.replay(p,List.of(event(EventType.INCOME,START.plusDays(30),10_000)),null,START.plusDays(30));
        assertEquals(1_000_000,result.balanceCents());
        assertEquals(10_000,result.incomeCents());
        assertEquals(10_000,result.gainCents());
    }
}
