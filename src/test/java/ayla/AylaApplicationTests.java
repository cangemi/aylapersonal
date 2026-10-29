package ayla;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ayla.chat.session.*;
import ayla.hermes.HermesException;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AylaApplicationTests {
    @Autowired ConversationService conversations;
    @Autowired JdbcTemplate jdbc;

	@Test
	void contextLoads() {
	}

    @Test void defaultConversationIsPersistedAndReused() {
        var first = conversations.resolve("local-owner", null, null);
        var second = conversations.resolve("local-owner", null, null);
        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("select current_conversation_id from chat_users where user_id='local-owner'", UUID.class))
                .isEqualTo(first.conversationId());
    }

    @Test void usersAndConversationsAreIsolatedAndCurrentCanBeSwitched() {
        String alice = provisionUser();
        String bob = provisionUser();
        var aliceFirst = conversations.resolve(alice, null, null);
        var bobFirst = conversations.resolve(bob, null, null);
        assertThat(aliceFirst.sessionId()).isNotEqualTo(bobFirst.sessionId());
        var aliceNew = conversations.newConversation(alice, "Jogos");
        assertThat(conversations.resolve(alice, null, null).conversationId()).isEqualTo(aliceNew.conversationId());
        assertThat(conversations.resolve(alice, aliceFirst.conversationId().toString(), null).conversationId()).isEqualTo(aliceFirst.conversationId());
        assertThat(conversations.resolve(alice, null, null).conversationId()).isEqualTo(aliceNew.conversationId());
        conversations.activate(alice, aliceFirst.conversationId());
        assertThat(conversations.resolve(alice, null, null)).isEqualTo(aliceFirst);
        assertThat(conversations.list(alice)).hasSize(2);
        assertThat(conversations.list(bob)).hasSize(1);
        assert404(() -> conversations.resolve(bob, aliceFirst.conversationId().toString(), null));
        assert404(() -> conversations.resolve(bob, null, aliceFirst.sessionId()));
        assert404(() -> conversations.activate(bob, aliceFirst.conversationId()));
        assertThat(conversations.resolve(bob, null, null)).isEqualTo(bobFirst);
    }

    @Test void concurrentFirstMessagesCreateOnlyOneCurrentConversation() throws Exception {
        String user = provisionUser();
        try (var pool = Executors.newFixedThreadPool(6)) {
            var start = new CountDownLatch(1);
            List<Future<ConversationService.Selection>> pending = new ArrayList<>();
            for (int i=0; i<6; i++) pending.add(pool.submit(() -> {
                start.await(); return conversations.resolve(user, null, null);
            }));
            start.countDown();
            var expected = pending.getFirst().get(20, TimeUnit.SECONDS);
            for (var result : pending) assertThat(result.get(20, TimeUnit.SECONDS)).isEqualTo(expected);
        }
        assertThat(conversations.list(user)).hasSize(1);
    }

    @Test void unprovisionedUserCannotUseOwnersHermesMemory() {
        String user = "future-user-" + UUID.randomUUID();
        assertThatThrownBy(() -> conversations.resolve(user, null, null)).isInstanceOfSatisfying(HermesException.class,
                e -> assertThat(e.status()).isEqualTo(503));
    }

    @Test void hermesSessionRotationKeepsStableConversationAndOwnership() {
        String user = provisionUser();
        var initial = conversations.resolve(user, null, null);
        String rotated = UUID.randomUUID().toString();
        conversations.updateHermesSession(user, initial.conversationId(), initial.sessionId(), rotated);
        var continued = conversations.resolve(user, null, null);
        assertThat(continued.conversationId()).isEqualTo(initial.conversationId());
        assertThat(continued.sessionId()).isEqualTo(rotated);
        assert404(() -> conversations.resolve(user, null, initial.sessionId()));
        assertThatThrownBy(() -> conversations.updateHermesSession(user, initial.conversationId(), initial.sessionId(), "stale"))
                .isInstanceOfSatisfying(HermesException.class, e -> assertThat(e.status()).isEqualTo(409));
    }

    private String provisionUser() {
        String user = "user-" + UUID.randomUUID();
        jdbc.update("insert into chat_users(user_id,hermes_profile,created_at) values (?, ?, current_timestamp)", user, "profile-" + UUID.randomUUID());
        return user;
    }
    private void assert404(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(HermesException.class, e -> assertThat(e.status()).isEqualTo(404));
    }

}
