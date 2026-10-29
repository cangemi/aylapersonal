package ayla.chat.session;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "chat_users")
public class ChatUser {
    @Id @Column(name = "user_id", length = 255)
    private String userId;
    @Column(name = "hermes_profile", length = 64)
    private String hermesProfile;
    @Column(name = "current_conversation_id")
    private UUID currentConversationId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    public String getUserId() { return userId; }
    public String getHermesProfile() { return hermesProfile; }
    public UUID getCurrentConversationId() { return currentConversationId; }
    public void setCurrentConversationId(UUID id) { currentConversationId = id; }
}
