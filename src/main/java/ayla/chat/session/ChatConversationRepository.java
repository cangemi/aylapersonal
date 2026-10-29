package ayla.chat.session;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, UUID> {
    Optional<ChatConversation> findByIdAndUserId(UUID id, String userId);
    Optional<ChatConversation> findByHermesSessionIdAndUserId(String sessionId, String userId);
    List<ChatConversation> findByUserIdOrderByCreatedAtDesc(String userId);
}
