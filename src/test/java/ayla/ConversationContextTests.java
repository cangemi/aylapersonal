package ayla;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import ayla.chat.session.*;
import ayla.memory.*;
import ayla.hermes.HermesException;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ConversationContextTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired ConversationService conversations;
    @Autowired ConversationContext context;
    @Autowired PersonalMemoryService memory;
    @Test void onlyPrimaryCanReadOtherChatsAndAccessPersonalMemoryAndSwitchingRevokesTheOldScope() {
        String user="scope-"+UUID.randomUUID(),other="scope-other-"+UUID.randomUUID(),instance="scope-"+UUID.randomUUID();
        var main=conversations.newConversation(user,"Principal");
        var films=conversations.newDetachedConversation(user,"Filmes");
        var work=conversations.newDetachedConversation(user,"Trabalho");
        var stranger=conversations.newConversation(other,"Outro proprietário");
        jdbc.update("insert into whatsapp_instances(id,owner_user_id) values (?,?)",instance,user);
        UUID contact=UUID.randomUUID();
        jdbc.update("insert into whatsapp_chats(id,instance_id,chat_id,display_name,conversation_id,mention_only) values (?,?,?,?,?,false)",contact,instance,"5511888888888@c.us","Principal",main.conversationId());
        context.rememberTurn(user,films.conversationId(),"Quero assistir um filme de ficção.","Podemos conversar sobre cinema.");
        context.rememberTurn(other,stranger.conversationId(),"Meu filme é secreto de outro usuário.","Referência privada.");
        var allowed=context.requirePrimary(main.sessionId());
        var hits=context.search(allowed,"filme");
        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().get("conversation_id")).isEqualTo(films.conversationId());
        assertThatThrownBy(()->context.requirePrimary(work.sessionId())).isInstanceOfSatisfying(HermesException.class,e->assertThat(e.status()).isEqualTo(403));
        assertThatThrownBy(()->context.requirePrimary(films.sessionId())).isInstanceOf(HermesException.class);
        var bridge=new PersonalMemoryBridge(memory,jdbc,"a".repeat(32));
        var command=new PersonalMemoryBridge.Command(null,"filme",null);
        for(String operation:List.of("search","delete")) {
            assertThatThrownBy(()->bridge.execute("Bearer "+"a".repeat(32),films.sessionId(),operation,command))
                    .isInstanceOfSatisfying(HermesException.class,e->assertThat(e.status()).isEqualTo(403));
        }
        var fact=new PersonalMemory.Fact("usuario","cidade",PersonalMemory.Kind.PROFILE,"Mora em Curitiba.",PersonalMemory.Source.USER,"Disse que mora em Curitiba.",4,null);
        assertThatThrownBy(()->bridge.execute("Bearer "+"a".repeat(32),films.sessionId(),"save",new PersonalMemoryBridge.Command(List.of(fact),null,null)))
                .hasMessageContaining("contexto recente");
        UUID calls=UUID.randomUUID();
        var whatsappOnly=conversations.newDetachedConversation(user,"Chamada");
        jdbc.update("insert into whatsapp_chats(id,instance_id,chat_id,display_name,conversation_id,mention_only) values (?,?,?,?,?,true)",calls,instance,"5511777777777@c.us","Chamada",whatsappOnly.conversationId());
        for(String operation:List.of("search","delete")) {
            assertThatThrownBy(()->bridge.execute("Bearer "+"a".repeat(32),whatsappOnly.sessionId(),operation,command))
                    .isInstanceOfSatisfying(HermesException.class,e->assertThat(e.status()).isEqualTo(403));
        }
        assertThat(bridge.execute("Bearer "+"a".repeat(32),main.sessionId(),"search",command)).isEqualTo(List.of());
        jdbc.update("update whatsapp_chats set mention_only=true where id=?",contact);
        assertThatThrownBy(()->context.search(allowed,"filme")).isInstanceOf(HermesException.class);
        assertThatThrownBy(()->context.requirePrimary(main.sessionId())).isInstanceOf(HermesException.class);
    }
}
