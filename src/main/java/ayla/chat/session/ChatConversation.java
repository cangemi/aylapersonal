package ayla.chat.session;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "chat_conversations")
@org.hibernate.annotations.SQLRestriction("deleted_at is null")
public class ChatConversation {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false, length = 255)
    private String userId;
    @Column(name = "hermes_session_id", nullable = false, unique = true, length = 128)
    private String hermesSessionId;
    @Column(nullable = false, length = 120)
    private String title;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    protected ChatConversation() {}
    public ChatConversation(String userId, String title) {
        id = UUID.randomUUID(); this.userId = userId; this.title = title;
        hermesSessionId = id.toString(); createdAt = Instant.now();
    }
    public UUID getId() { return id; }
    public String getUserId() { return userId; }
    public String getHermesSessionId() { return hermesSessionId; }
    public void setHermesSessionId(String id) { hermesSessionId = id; }
    public String getTitle() { return title; }
    public Instant getCreatedAt() { return createdAt; }
}
