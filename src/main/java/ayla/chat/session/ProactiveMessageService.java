package ayla.chat.session;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.UUID;

@Service
public class ProactiveMessageService {
    private final JdbcTemplate jdbc;
    public ProactiveMessageService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public java.util.Optional<UUID> currentConversation(String user) {
        try {
            List<UUID> ids = jdbc.query("select current_conversation_id from chat_users where user_id=?", (r, n) -> r.getObject("current_conversation_id", UUID.class), user);
            if (!ids.isEmpty() && ids.get(0) != null) return Optional.of(ids.get(0));
            List<UUID> fallback = jdbc.query("select id from chat_conversations where user_id=? order by updated_at desc, created_at desc limit 1",
                    (r, n) -> r.getObject("id", UUID.class), user);
            return fallback.isEmpty() ? Optional.empty() : Optional.of(fallback.get(0));
        } catch (RuntimeException error) {
            return Optional.empty();
        }
    }

    public void record(String user, UUID conversation, String text, String source, UUID sourceId) {
        if (user == null || conversation == null || text == null || text.isBlank()) return;
        try {
            jdbc.update("insert into proactive_messages(id,user_id,conversation_id,role,text,source,source_id,created_at) values (?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), user, conversation, "assistant", text, source, sourceId, Timestamp.from(Instant.now()));
        } catch (RuntimeException ignored) {
            // Proactive delivery must never block the primary flow.
        }
    }

    public List<ConversationHistory.Message> listByUser(String user, int limit) {
        List<ConversationHistory.Message> result = new ArrayList<>();
        if (user == null || limit < 1) return result;
        try {
            jdbc.query("select id,text from proactive_messages where user_id=? order by created_at asc limit ?",
                    (r, n) -> {
                        result.add(new ConversationHistory.Message("proactive:" + r.getObject("id", UUID.class),
                                "assistant", r.getString("text"), List.of()));
                        return null;
                    }, user, limit);
        } catch (RuntimeException ignored) {
        }
        return result;
    }
}