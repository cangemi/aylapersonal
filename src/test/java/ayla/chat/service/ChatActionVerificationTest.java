package ayla.chat.service;

import ayla.chat.dto.ChatRequest;
import ayla.chat.session.ConversationService;
import ayla.finance.FinanceAgentLedger;
import ayla.hermes.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatActionVerificationTest {
    HermesClient client=mock(HermesClient.class);
    AgentActionReceipts receipts=mock(AgentActionReceipts.class);
    UUID turn=UUID.randomUUID(),conversation=UUID.randomUUID();
    ChatService service;
    @BeforeEach void setup(){
        var models=mock(HermesModels.class);var settings=mock(HermesSettingsService.class);
        var conversations=mock(ConversationService.class);var ledger=mock(FinanceAgentLedger.class);
        when(models.select(null,"profile-a")).thenReturn(new HermesModels.Model("nous","free-model",true));
        when(settings.get("alice")).thenReturn(new HermesConfiguration());
        when(conversations.resolve(eq("alice"),any(),any())).thenReturn(new ConversationService.Selection(conversation,"session-a","profile-a"));
        when(ledger.begin(eq("alice"),eq(conversation),anyString())).thenReturn(turn);
        service=new ChatService(client,models,settings,conversations,ledger);service.setActionReceipts(receipts);
    }
    Map<String,Object> answer(String text){return Map.of("status","completed","session_id","session-a","message",Map.of("content",text));}
    @Test void falseSuccessGetsOnlyOneCorrectionAndCanAskForMissingRequiredData(){
        String falseSuccess="Registro de despesa concluído: R$ 10,00 gasto hoje.";
        when(client.post(eq("profile-a"),eq("/api/sessions/session-a/chat"),anyMap())).thenReturn(answer(falseSuccess),answer("Com o que você gastou os R$ 10?"));
        when(receipts.missingDomains("alice",turn,"gastei 10 reais hj",falseSuccess)).thenReturn(Set.of("finance"));
        assertThat(service.chat(new ChatRequest("gastei 10 reais hj",null),"alice").response()).contains("Com o que");
        verify(client,times(2)).post(eq("profile-a"),eq("/api/sessions/session-a/chat"),anyMap());
    }
    @Test void twoInventedInvestmentConfirmationsAreBlockedWithoutAnInferenceLoop(){
        String falseSuccess="Resgate concluído: R$ 200,00 entrou na caixinha.";
        when(client.post(eq("profile-a"),eq("/api/sessions/session-a/chat"),anyMap())).thenReturn(answer(falseSuccess));
        when(receipts.missingDomains("alice",turn,"tirei 200 reais da caixinha",falseSuccess)).thenReturn(Set.of("investment"));
        assertThatThrownBy(()->service.chat(new ChatRequest("tirei 200 reais da caixinha",null),"alice")).isInstanceOf(HermesException.class);
        verify(client,times(2)).post(eq("profile-a"),eq("/api/sessions/session-a/chat"),anyMap());
    }
    @Test void verifiedActionAndOrdinaryConversationNeedOnlyTheOriginalInference(){
        when(client.post(eq("profile-a"),eq("/api/sessions/session-a/chat"),anyMap())).thenReturn(answer("Registro de despesa concluído: R$ 10,00."));
        assertThat(service.chat(new ChatRequest("gastei 10 reais com café",null),"alice").response()).contains("concluído");
        verify(client,times(1)).post(eq("profile-a"),eq("/api/sessions/session-a/chat"),anyMap());
    }
}
