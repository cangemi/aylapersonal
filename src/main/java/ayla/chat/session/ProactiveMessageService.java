package ayla.chat.session;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ayla.push.PushService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.UUID;

@Service
public class ProactiveMessageService {
    private final JdbcTemplate jdbc;
    private ayla.push.PushService push;
    public ProactiveMessageService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @org.springframework.beans.factory.annotation.Autowired
    public void setPush(PushService push) { this.push = push; }

    public java.util.Optional<UUID> currentConversation(String user) {
        try {
            List<UUID> primary = jdbc.query("""
                select c.conversation_id from whatsapp_chats c join whatsapp_instances i on i.id=c.instance_id
                where i.owner_user_id=? and i.connection_role='user' and c.enabled and not c.mention_only
                and c.conversation_id is not null order by c.updated_at desc limit 1
                """, (r, n) -> r.getObject("conversation_id", UUID.class), user);
            if (!primary.isEmpty()) return Optional.of(primary.getFirst());
            List<UUID> ids = jdbc.query("select current_conversation_id from chat_users where user_id=?", (r, n) -> r.getObject("current_conversation_id", UUID.class), user);
            if (!ids.isEmpty() && ids.get(0) != null) return Optional.of(ids.get(0));
            List<UUID> fallback = jdbc.query("select id from chat_conversations where user_id=? order by created_at desc limit 1",
                    (r, n) -> r.getObject("id", UUID.class), user);
            return fallback.isEmpty() ? Optional.empty() : Optional.of(fallback.get(0));
        } catch (RuntimeException error) {
            return Optional.empty();
        }
    }

    public void record(String user, UUID conversation, String text, String source, UUID sourceId) {
        if (user == null || text == null || text.isBlank()) return;
        UUID id = UUID.randomUUID();
        try {
            int inserted = jdbc.update("insert into proactive_messages(id,user_id,conversation_id,role,text,source,source_id,created_at) values (?,?,?,?,?,?,?,?) on conflict do nothing",
                    id, user, conversation, "assistant", text, source, sourceId, Timestamp.from(Instant.now()));
            if (inserted > 0 && push != null && Set.of("alarm-notification", "calendar-reminder").contains(source)) {
                String eventId = ("alarm-notification".equals(source) ? "alarm:" : "calendar:") + (sourceId == null ? id : sourceId);
                push.notifyAsync(user, conversation, text, eventId);
            }
        } catch (RuntimeException ignored) {
            // Proactive delivery must never block the primary flow.
        }
    }

    public List<ConversationHistory.Message> listByUser(String user, UUID conversationId, int limit) {
        var messages = listSince(user, conversationId, null);
        return messages.subList(Math.max(0, messages.size() - Math.max(0, limit)), messages.size());
    }

    public List<ConversationHistory.Message> listSince(String user, UUID conversationId, Instant since) {
        List<ConversationHistory.Message> result = new ArrayList<>();
        if (user == null) return result;
        try {
            String sql = "select p.*,j.provider_notice_json,(j.status='FAILED' and j.dispatched_at is null and j.request_json is not null and j.source='WEB') as retryable from proactive_messages p left join chat_jobs j on p.source='chat-job-notice' and j.id=p.source_id and j.user_id=p.user_id where p.user_id=?";
            List<Object> args = new ArrayList<>();
            args.add(user);
            if (conversationId != null) {
                sql += " and p.conversation_id=?";
                args.add(conversationId);
            }
            if (since != null) {
                sql += " and p.created_at>=?";
                args.add(Timestamp.from(since));
            }
            sql += " order by p.created_at asc,p.id";
            jdbc.query(sql,
                    (r, n) -> {
                        Map<String,Object> meta=null;
                        if(r.getString("provider_notice_json") instanceof String raw){meta=new LinkedHashMap<>(tools.jackson.databind.json.JsonMapper.builder().build().readValue(raw,Map.class));meta.put("serverNow",Instant.now());}
                        result.add(new ConversationHistory.Message("proactive:" + r.getObject("id", UUID.class),
                                r.getString("role"), r.getString("text"), List.of(), r.getTimestamp("created_at").toInstant(),
                                "chat-job-notice".equals(r.getString("source"))?r.getObject("source_id",UUID.class):null,meta,r.getBoolean("retryable")));
                        return null;
                    }, args.toArray());
        } catch (RuntimeException ignored) {
        }
        return result;
    }
}
