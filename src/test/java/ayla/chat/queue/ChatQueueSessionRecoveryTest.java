package ayla.chat.queue;

import ayla.chat.service.ChatService;
import ayla.chat.session.ConversationService;
import ayla.hermes.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class ChatQueueSessionRecoveryTest {
    @Test void zeroActiveRunsDoesNotReleasePausedConversationsOrOtherConversationsOfTheSameUser(){
        var store=mock(ChatQueueStore.class);var hermes=mock(HermesClient.class);
        UUID paused=UUID.randomUUID(),ready=UUID.randomUUID();
        when(store.blockedProfiles()).thenReturn(List.of(
            Map.of("user_id","alice","hermes_profile","default","conversation_id",paused,"hermes_session_id","paused-session"),
            Map.of("user_id","alice","hermes_profile","default","conversation_id",ready,"hermes_session_id","ready-session")));
        when(hermes.get("default","/health/detailed")).thenReturn(Map.of("api_server",Map.of("active_runs",0)));
        doThrow(new HermesSessionPausedException()).when(hermes).requireSessionReady("default","paused-session");
        var service=new ChatQueueService(store,mock(ConversationService.class),mock(ChatService.class),1);service.setHermes(hermes);
        try {
            service.maintain();
            verify(store,never()).releaseReviewed("alice",paused);
            verify(store).releaseReviewed("alice",ready);
        } finally {service.shutdown();}
    }
}
