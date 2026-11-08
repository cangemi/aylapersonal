package ayla.chat.service;

import ayla.alarm.AlarmBridge;
import ayla.alarm.AlarmService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AgentActionReceiptAdviceTest {
    @Test void shoppingPriceRedirectDoesNotProveAnExpenseAndCheckoutRequiresARealFinancialEntry(){
        var receipts=mock(AgentActionReceipts.class);var advice=new AgentActionReceiptAdvice(receipts);
        var request=mock(org.springframework.http.server.ServerHttpRequest.class);
        var headers=new org.springframework.http.HttpHeaders();headers.set("X-Ayla-Session","session-a");
        when(request.getHeaders()).thenReturn(headers);
        when(request.getURI()).thenReturn(java.net.URI.create("http://localhost/internal/hermes/finance/record"));
        advice.beforeBodyWrite(java.util.Map.of("destination","SHOPPING"),null,MediaType.APPLICATION_JSON,null,request,null);
        verify(receipts).record("session-a","household","apply");
        verify(receipts,never()).record("session-a","finance","record");
        clearInvocations(receipts);
        when(request.getURI()).thenReturn(java.net.URI.create("http://localhost/internal/hermes/household/checkout"));
        var checkout=mock(ayla.household.HouseholdData.CheckoutResult.class);
        advice.beforeBodyWrite(checkout,null,MediaType.APPLICATION_JSON,null,request,null);
        verify(receipts,never()).record("session-a","finance","record");
        when(checkout.financialEntry()).thenReturn(mock(ayla.finance.FinanceData.Entry.class));
        advice.beforeBodyWrite(checkout,null,MediaType.APPLICATION_JSON,null,request,null);
        verify(receipts).record("session-a","finance","record");
    }
    @Test void successfulMutationCreatesReceiptButReadAndRejectedWriteDoNot() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var alarms=mock(AlarmService.class);var receipts=mock(AgentActionReceipts.class);
        when(jdbc.queryForList(anyString(),eq(String.class),eq("session-a"))).thenReturn(List.of("alice"));
        var mvc=MockMvcBuilders.standaloneSetup(new AlarmBridge(alarms,jdbc))
            .setControllerAdvice(new AgentActionReceiptAdvice(receipts)).build();
        UUID id=UUID.randomUUID();
        mvc.perform(post("/internal/hermes/alarm/confirm").header("X-Ayla-Session","session-a")
            .contentType(MediaType.APPLICATION_JSON).content("{\"id\":\""+id+"\"}")).andExpect(status().isOk());
        verify(alarms).confirm("alice",id);
        verify(receipts).record("session-a","alarm","confirm");
        clearInvocations(receipts);
        mvc.perform(post("/internal/hermes/alarm/list").header("X-Ayla-Session","session-a")
            .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());
        mvc.perform(post("/internal/hermes/alarm/save").header("X-Ayla-Session","session-a")
            .contentType(MediaType.APPLICATION_JSON).content("{\"dayOfWeek\":9}")).andExpect(status().isBadRequest());
        verifyNoInteractions(receipts);
    }
}
