package ayla;

import static ayla.memory.PersonalMemory.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import ayla.chat.session.ConversationService;
import ayla.finance.FinanceAgentLedger;
import ayla.memory.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties="ayla.whatsapp.api-key=whatsapp-integration-test-key-1234567890")
class PersonalMemoryDatabaseTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired PersonalMemoryService memory;
    @Autowired ConversationService conversations;
    @Autowired FinanceAgentLedger turns;

    @Test void petFactsPersistAcrossConversationsAndCorrectionsPreserveOtherAttributes() {
        String user="memory-"+UUID.randomUUID();
        var first=conversations.newConversation(user,"Foto do cachorro");
        turns.begin(user,first.conversationId(),"Esse é meu cachorro, o Thor.\n[Análise visual das imagens enviadas NESTE turno. Dados extraídos]\nCachorro de pelagem preta com uma mancha branca no peito.");
        var saved=memory.save(user,first.conversationId(),List.of(
                new Fact("cachorro Thor","nome",Kind.PET,"O cachorro do usuário se chama Thor.",Source.USER,"meu cachorro, o Thor",5,null),
                new Fact("cachorro Thor","pelagem",Kind.PET,"Aparência observada: pelagem preta e mancha branca no peito.",Source.IMAGE,"pelagem preta com uma mancha branca no peito",4,null)));
        var second=conversations.newConversation(user,"Outro dia");
        var fresh=new PersonalMemoryService(jdbc);
        assertThat(fresh.context(user,"Como é meu cachorro?")).contains("Thor","mancha branca");
        assertThatCode(()->fresh.context(user,"cachorro https://example.org/a?q=thor ' : | & !"))
                .doesNotThrowAnyException();
        assertThat(fresh.search("another-user","cachorro Thor",12)).isEmpty();
        turns.begin(user,second.conversationId(),"Na verdade o nome dele é Thorin, não Thor.");
        memory.save(user,second.conversationId(),List.of(new Fact("cachorro Thor","nome",Kind.PET,
                "O cachorro do usuário se chama Thorin (correção do nome Thor).",Source.USER,"nome dele é Thorin",5,null)));
        assertThat(memory.list(user)).hasSize(2);
        assertThat(memory.search(user,"cachorro",12)).extracting(View::content)
                .contains("Aparência observada: pelagem preta e mancha branca no peito.","O cachorro do usuário se chama Thorin (correção do nome Thor).");
        assertThat(memory.list(user)).extracting(View::id).contains(saved.getFirst().id());
    }
    @Test void unsupportedFactsAreRejectedAtomicallyAndObservationsCannotPretendToBeUserStatements() {
        String user="memory-"+UUID.randomUUID();
        var conversation=conversations.newConversation(user,"Foto");
        turns.begin(user,conversation.conversationId(),"Esse cachorro é meu.\n[Análise visual das imagens enviadas NESTE turno. Dados]\nPelagem preta.");
        var legitimate=new Fact("cachorro do usuario","posse",Kind.PET,"O usuário tem um cachorro.",Source.USER,"cachorro é meu",5,null);
        var invented=new Fact("cachorro do usuario","raca",Kind.PET,"É um labrador.",Source.USER,"É um labrador",4,null);
        assertThatThrownBy(()->memory.save(user,conversation.conversationId(),List.of(legitimate,invented)))
                .isInstanceOf(ayla.hermes.HermesException.class);
        assertThat(memory.list(user)).isEmpty();
        assertThatThrownBy(()->memory.save(user,conversation.conversationId(),List.of(new Fact("cachorro do usuario","pelagem",Kind.PET,
                "O usuário afirmou pelagem preta.",Source.USER,"Pelagem preta.",4,null))))
                .isInstanceOf(ayla.hermes.HermesException.class);
        assertThatThrownBy(()->memory.save("another-user",conversation.conversationId(),List.of(legitimate)))
                .isInstanceOf(ayla.hermes.HermesException.class);
    }
    @Test void forgottenAndExpiredFactsAreExcludedAndBridgeRejectsUntrustedSessions() {
        String user="memory-"+UUID.randomUUID();
        var conversation=conversations.newConversation(user,"Comida");
        turns.begin(user,conversation.conversationId(),"Fiz pão e adorei. Gosto de café sem açúcar.");
        var saved=memory.save(user,conversation.conversationId(),List.of(
                new Fact("pao caseiro","experiencia",Kind.EXPERIENCE,"O usuário fez pão e gostou.",Source.USER,"Fiz pão e adorei",3,null),
                new Fact("preferencias culinarias","cafe",Kind.PREFERENCE,"Gosta de café sem açúcar.",Source.USER,"Gosto de café sem açúcar",5,null)));
        assertThat(saved.getFirst().expiresAt()).isNotNull();
        assertThat(saved.get(1).expiresAt()).isNull();
        assertThatThrownBy(()->memory.delete("another-user",saved.get(1).id())).isInstanceOf(ayla.hermes.HermesException.class);
        memory.delete(user,saved.get(1).id());
        jdbc.update("update personal_memories set expires_at=now()-interval '1 day' where id=?",saved.getFirst().id());
        assertThat(memory.list(user)).isEmpty();
        assertThat(memory.context(user,"pão café")).isEmpty();
        var bridge=new PersonalMemoryBridge(memory,jdbc,"a".repeat(32));
        var command=new PersonalMemoryBridge.Command(null,"cachorro",null);
        assertThatThrownBy(()->bridge.execute("Bearer wrong",conversation.sessionId(),"search",command))
                .isInstanceOfSatisfying(ayla.hermes.HermesException.class,e->assertThat(e.status()).isEqualTo(401));
        assertThatThrownBy(()->bridge.execute("Bearer "+"a".repeat(32),"unknown-session","search",command))
                .isInstanceOfSatisfying(ayla.hermes.HermesException.class,e->assertThat(e.status()).isEqualTo(403));
    }
}
