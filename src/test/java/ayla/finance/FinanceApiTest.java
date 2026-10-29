package ayla.finance;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import ayla.chat.session.ChatIdentity;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FinanceApiTest {
    FinanceService service;
    JdbcTemplate jdbc;
    FinanceAgentLedger ledger;
    MockMvc api;
    String key="finance-test-key-0123456789-0123456789";
    @BeforeEach void setup() {
        service=mock(FinanceService.class); jdbc=mock(JdbcTemplate.class); ledger=mock(FinanceAgentLedger.class);
        when(service.create(anyString(),any(),any())).thenReturn(new FinanceData.Entry(UUID.randomUUID(),FinanceData.Type.EXPENSE,3590L,"BRL","Almoço","Alimentação",java.time.OffsetDateTime.now(),java.time.Instant.now(),java.time.Instant.now(),0));
        var entry=new FinanceData.Entry(UUID.randomUUID(),FinanceData.Type.EXPENSE,12L,"BRL","Bala","Outros",java.time.OffsetDateTime.now(),java.time.Instant.now(),java.time.Instant.now(),0);
        when(ledger.record(anyString(),any(),any())).thenReturn(entry);
        when(ledger.update(anyString(),any(),any(),any())).thenReturn(entry);
        var summary=new FinanceData.Summary("BRL","America/Sao_Paulo",java.time.LocalDate.now(),java.time.LocalDate.now(),java.time.Instant.now(),-12L,new FinanceData.Totals(0,12,-12),FinanceData.Group.DAY,List.of());
        when(service.summary(anyString(),any(),any(),any(),any())).thenReturn(summary);
        when(service.summary(anyString(),any(),any(),any(),any(),any())).thenReturn(summary);
        api=MockMvcBuilders.standaloneSetup(new FinanceController(service,new FinanceInsights(jdbc),new ChatIdentity("owner")),
                new FinanceBridgeController(service,new FinanceInsights(jdbc),jdbc,ledger,key)).setControllerAdvice(new FinanceExceptionHandler()).defaultRequest(get("/").principal(()->"owner")).build();
    }
    @Test void principalOwnsFinanceEvenIfBodyTriesToSelectSomeoneElse() throws Exception {
        api.perform(post("/api/v1/finance/entries").principal(()->"alice").contentType("application/json")
                .content(""" 
                    {"type":"EXPENSE","amountCents":3590,"description":"Almoço","category":"Alimentação","userId":"bob"}
                    """)).andExpect(status().isCreated());
        verify(service).create(eq("alice"),any(),isNull());
    }
    @Test void rejectsInvalidAmountsRequiredFieldsAndDates() throws Exception {
        for(String body:List.of("{}", "{\"type\":\"INCOME\",\"amountCents\":3590.0,\"description\":\"a\",\"category\":\"b\"}", "{\"type\":\"INCOME\",\"amountCents\":\"3590\",\"description\":\"a\",\"category\":\"b\"}", "{\"type\":\"EXPENSE\",\"amountCents\":-1}",
                "{\"type\":\"INCOME\",\"amountCents\":0.001,\"description\":\"a\",\"category\":\"b\"}",
                "{\"type\":\"INCOME\",\"amountCents\":10,\"description\":\"a\",\"category\":\"b\",\"occurredAt\":\"ontem\"}"))
            api.perform(post("/api/v1/finance/entries").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void bridgeRejectsBadCredentialsAndUnknownSessions() throws Exception {
        api.perform(post("/internal/hermes/finance/summary").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(jdbc,service);
        when(jdbc.queryForList(anyString(),eq(String.class),eq("unknown"))).thenReturn(List.of());
        api.perform(post("/internal/hermes/finance/summary").header("Authorization","Bearer "+key)
                .header("X-Ayla-Session","unknown").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void bridgeUsesRegisteredSessionOwnerForAgentRecordingAndReads() throws Exception {
        when(jdbc.queryForList(anyString(),eq(String.class),eq("registered-session"))).thenReturn(List.of("alice"));
        api.perform(post("/internal/hermes/finance/summary").header("Authorization","Bearer "+key)
                .header("X-Ayla-Session","registered-session").contentType("application/json").content("{\"userId\":\"bob\"}"))
                .andExpect(status().isOk());
        verify(service).summary(eq("alice"),isNull(),isNull(),isNull(),isNull(),isNull());
        UUID conversation=UUID.randomUUID();
        when(jdbc.queryForObject(anyString(),eq(UUID.class),eq("alice"),eq("registered-session"))).thenReturn(conversation);
        api.perform(post("/internal/hermes/finance/record").header("Authorization","Bearer "+key)
                .header("X-Ayla-Session","registered-session").contentType("application/json").content("{\"type\":\"EXPENSE\",\"amount\":\"12\",\"unit\":\"CENTAVOS\",\"description\":\"Bala\"}"))
                .andExpect(status().isOk());
        verify(ledger).record(eq("alice"),eq(conversation),argThat(input->input.amount().equals("12") && input.unit()==FinanceAgentLedger.Unit.CENTAVOS));
        verify(service,never()).create(any(),any(),any());
    }
    @Test void updateDeleteAndSummaryBindParametersAndReturnConflicts() throws Exception {
        UUID id=UUID.randomUUID();
        doThrow(new FinanceException(409,"Versão alterada.")).when(service).delete("owner",id,0);
        api.perform(delete("/api/v1/finance/entries/"+id).param("version","0"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("Versão alterada."));
        api.perform(get("/api/v1/finance/summary").param("from","2026-01-01").param("to","2026-01-31").param("groupBy","MONTH"))
                .andExpect(status().isOk());
        verify(service).summary("owner",java.time.LocalDate.parse("2026-01-01"),java.time.LocalDate.parse("2026-01-31"),null,FinanceData.Group.MONTH,null);
        when(jdbc.queryForList(anyString(),eq(String.class),eq("registered-session"))).thenReturn(List.of("alice"));
        api.perform(post("/internal/hermes/finance/update").header("Authorization","Bearer "+key)
                .header("X-Ayla-Session","registered-session").contentType("application/json")
                .content("{\"id\":\""+id+"\",\"version\":2,\"amount\":\"15\",\"unit\":\"REAIS\"}"))
                .andExpect(status().isOk());
        verify(ledger).update(eq("alice"),eq(id),eq(2L),argThat(input->input.amount().equals("15") && input.unit()==FinanceAgentLedger.Unit.REAIS && input.description()==null));
        api.perform(post("/internal/hermes/finance/delete").header("Authorization","Bearer "+key)
                .header("X-Ayla-Session","registered-session").contentType("application/json")
                .content("{\"id\":\""+id+"\",\"version\":3}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.summary.balanceCents").value(-12));
        verify(service).delete("alice",id,3L);
    }
}
