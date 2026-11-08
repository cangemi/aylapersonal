package ayla.chat.service;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentActionReceiptsTest {
    @Test void catchesTheActualFalseConfirmationsWithoutConfusingResgateWithANewExpense(){
        assertThat(AgentActionReceipts.claimedDomains("Registro de despesa concluído: R$ 10,00 gasto hoje.")).containsExactly("finance");
        assertThat(AgentActionReceipts.claimedDomains("Resgate concluído: R$ 200,00 entrou na caixinha, registrado na data completa. 😊")).containsExactly("investment");
        assertThat(AgentActionReceipts.claimedDomains("Criei o alarme das 8h.")).containsExactly("alarm");
        assertThat(AgentActionReceipts.claimedDomains("Atualizei o preço no carrinho: R$ 10,00.")).containsExactly("household");
        assertThat(AgentActionReceipts.claimedDomains("Não consegui registrar a despesa.")).isEmpty();
        assertThat(AgentActionReceipts.claimedDomains("Qual a descrição da despesa?")).isEmpty();
        assertThat(AgentActionReceipts.claimedDomains("Vou registrar sua despesa.")).isEmpty();
    }
    @Test void queryingOrWritingAnotherDomainCannotProveTheRequestedMutation(){
        var jdbc=mock(JdbcTemplate.class);var receipts=new AgentActionReceipts(jdbc);var turn=UUID.randomUUID();
        when(jdbc.queryForList(anyString(),eq(String.class),eq("alice"),eq(turn))).thenReturn(List.of("memory"));
        assertThat(receipts.missingDomains("alice",turn,"Gastei 10 reais", "Registro de despesa concluído: R$ 10,00.")).containsExactly("finance");
        assertThat(receipts.missingDomains("alice",turn,"Gastei 10 reais", "Feito!")).containsExactly("finance");
        when(jdbc.queryForList(anyString(),eq(String.class),eq("alice"),eq(turn))).thenReturn(List.of("finance"));
        assertThat(receipts.missingDomains("alice",turn,"Gastei 10 reais", "Registro de despesa concluído: R$ 10,00.")).isEmpty();
        assertThat(AgentActionReceipts.mutation("finance","summary")).isFalse();
        assertThat(AgentActionReceipts.mutation("investment","portfolio")).isFalse();
        assertThat(AgentActionReceipts.mutation("calendar","plan")).isFalse();
    }
    @Test void ordinaryConversationDoesNotQueryReceiptsOrConsumeExtraInference(){
        var jdbc=mock(JdbcTemplate.class);var receipts=new AgentActionReceipts(jdbc);
        assertThat(receipts.missingDomains("alice",UUID.randomUUID(),"Oi!", "Oi! Como você está?")).isEmpty();
        verifyNoInteractions(jdbc);
    }
}
