package ayla.chat.session;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ChatUserRepository extends JpaRepository<ChatUser, String> {
    @Modifying
    @Query(value = "INSERT INTO chat_users(user_id, hermes_profile, created_at) VALUES (:id, :profile, CURRENT_TIMESTAMP) ON CONFLICT (user_id) DO NOTHING", nativeQuery = true)
    void ensureUser(@Param("id") String id, @Param("profile") String profile);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from ChatUser u where u.userId = :id")
    Optional<ChatUser> lockUser(@Param("id") String id);
}
