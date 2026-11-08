package ayla.investment;

import static ayla.investment.PortfolioData.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InvestmentBridgeCrudTest {
    final UUID id=UUID.randomUUID(),eventId=UUID.randomUUID();
    final String key="test-investment-bridge-key-123456789";
    PortfolioService portfolio;
    MockMvc api;
    View view;
    @BeforeEach void setup(){
        portfolio=mock(PortfolioService.class);var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(),eq(String.class),eq("owned-session"))).thenReturn(List.of("owner"));
        var bridge=new InvestmentBridge(mock(InvestmentService.class),jdbc,key);bridge.setPortfolio(portfolio);
        api=MockMvcBuilders.standaloneSetup(bridge).setControllerAdvice(new ayla.finance.FinanceExceptionHandler()).build();
        var rate=new Rate(RateType.CDI,new BigDecimal("100"),DayBasis.BUSINESS);
        var p=new Position(id,"Reserva","Banco",AssetType.CDB,"",LocalDate.parse("2026-10-31"),100_000,95_000,rate,null,"Preservar",3);
        var event=new Event(eventId,EventType.WITHDRAWAL,LocalDate.parse("2026-11-01"),20_000,"Resgate caixinha",null);
        view=new View(p,rate,80_000,95_000,20_000,0,75_000,5_000,BigDecimal.ONE,0,null,p.openedOn(),true,List.of(),List.of(),List.of(event));
        when(portfolio.get("owner",id)).thenReturn(view);
        when(portfolio.update(eq("owner"),eq(id),any())).thenReturn(view);
        when(portfolio.updateEvent(eq("owner"),eq(id),eq(eventId),any())).thenReturn(view);
        when(portfolio.deleteEvent("owner",id,eventId,3)).thenReturn(view);
    }
    void call(String operation,String body)throws Exception {
        api.perform(post("/internal/hermes/investment/"+operation).header("Authorization","Bearer "+key)
            .header("X-Ayla-Session","owned-session").contentType("application/json").content(body)).andExpect(status().isOk());
    }
    @Test void partialEditPreservesTheOriginalBalanceRateAndOtherFields()throws Exception{
        call("save_position","{\"id\":\""+id+"\",\"version\":3,\"name\":\"Reserva nova\"}");
        var input=ArgumentCaptor.forClass(Input.class);verify(portfolio).update(eq("owner"),eq(id),input.capture());
        assertThat(input.getValue().name()).isEqualTo("Reserva nova");
        assertThat(input.getValue().openingBalanceCents()).isEqualTo(100_000L);
        assertThat(input.getValue().openingInvestedCents()).isEqualTo(95_000L);
        assertThat(input.getValue().ratePercent()).isEqualByComparingTo("100");
        assertThat(input.getValue().notes()).isEqualTo("Preservar");
    }
    @Test void updatingAndDeletingAWithdrawalDoNotDeleteOrCreateTheHolding()throws Exception{
        call("update_event","{\"id\":\""+id+"\",\"eventId\":\""+eventId+"\",\"version\":3,\"amount\":\"210.00\"}");
        var input=ArgumentCaptor.forClass(EventInput.class);verify(portfolio).updateEvent(eq("owner"),eq(id),eq(eventId),input.capture());
        assertThat(input.getValue().date()).isEqualTo(LocalDate.parse("2026-11-01"));
        assertThat(input.getValue().type()).isEqualTo(EventType.WITHDRAWAL);
        assertThat(input.getValue().amountCents()).isEqualTo(21_000L);
        call("delete_event","{\"id\":\""+id+"\",\"eventId\":\""+eventId+"\",\"version\":3}");
        verify(portfolio).deleteEvent("owner",id,eventId,3);
        verify(portfolio,never()).delete(anyString(),any(),anyLong());
        verify(portfolio,never()).addEvent(anyString(),any(),any());
    }
    @Test void deletingTheHoldingRequiresItsVersionAndOwnedSession()throws Exception{
        call("delete_position","{\"id\":\""+id+"\",\"version\":3}");
        verify(portfolio).delete("owner",id,3);
        api.perform(post("/internal/hermes/investment/delete_position").header("Authorization","Bearer "+key)
            .header("X-Ayla-Session","another-session").contentType("application/json").content("{\"id\":\""+id+"\",\"version\":3}"))
            .andExpect(status().isForbidden());
        verify(portfolio,times(1)).delete(anyString(),any(),anyLong());
    }
}
