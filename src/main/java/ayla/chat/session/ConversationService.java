package ayla.chat.session;

import ayla.hermes.HermesException;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationService {
    public record Selection(UUID conversationId, String sessionId, String profile) {}
    public record View(UUID conversationId, String sessionId, String title, java.time.Instant createdAt, boolean current) {}
    private final ChatUserRepository users;
    private final ChatConversationRepository conversations;
    private final ChatIdentity identity;
    private ayla.hermes.HermesUserScope scope;
    @org.springframework.beans.factory.annotation.Autowired
    public void setScope(ayla.hermes.HermesUserScope scope){this.scope=scope;}
    public ConversationService(ChatUserRepository users, ChatConversationRepository conversations, ChatIdentity identity) {
        this.users = users; this.conversations = conversations; this.identity = identity;
    }
    @Transactional
    public Selection resolve(String userId, String conversationId, String sessionId) {
        ChatUser user = lock(userId);
        ChatConversation conversation;
        if (conversationId != null) {
            conversation = owned(userId, parseId(conversationId));
            if (sessionId != null && !sessionId.equals(conversation.getHermesSessionId()))
                throw new HermesException(400, "conversationId e sessionId pertencem a conversas diferentes.");
        } else if (sessionId != null) {
            conversation = conversations.findByHermesSessionIdAndUserId(sessionId, userId)
                    .orElseThrow(ConversationService::notFound);
        } else if (user.getCurrentConversationId() != null) {
            conversation = owned(userId, user.getCurrentConversationId());
        } else {
            conversation = create(user, "Conversa principal");
        }
        return selection(user, conversation);
    }
    @Transactional
    public View newConversation(String userId, String title) {
        var user = lock(userId);
        return view(create(user, title == null || title.isBlank() ? "Nova conversa" : title.trim()), user);
    }
    @Transactional
    public View newDetachedConversation(String userId, String title) {
        var user=lock(userId);
        var conversation=conversations.saveAndFlush(new ChatConversation(user.getUserId(),title));
        return view(conversation,user);
    }
    @Transactional
    public View activate(String userId, UUID id) {
        var user = lock(userId);
        var conversation = owned(userId, id);
        user.setCurrentConversationId(id);
        return view(conversation, user);
    }
    @Transactional
    public List<View> list(String userId) {
        var user = lock(userId);
        return conversations.findByUserIdOrderByCreatedAtDesc(userId).stream().map(c -> view(c, user)).toList();
    }
    @Transactional
    public void updateHermesSession(String userId, UUID conversationId, String expectedSession, String effectiveSession) {
        lock(userId);
        var conversation = owned(userId, conversationId);
        if (!conversation.getHermesSessionId().equals(expectedSession))
            throw new HermesException(409, "A sessão desta conversa foi atualizada. Reenvie usando conversationId.");
        conversation.setHermesSessionId(effectiveSession);
    }
    private ChatUser lock(String userId) {
        users.ensureUser(userId, userId.equals(identity.ownerId()) ? "default" : null);
        return users.lockUser(userId).orElseThrow(() -> new IllegalStateException("Usuário não encontrado após criação."));
    }
    private ChatConversation create(ChatUser user, String title) {
        var conversation = conversations.saveAndFlush(new ChatConversation(user.getUserId(), title));
        user.setCurrentConversationId(conversation.getId());
        return conversation;
    }
    private ChatConversation owned(String userId, UUID id) {
        return conversations.findByIdAndUserId(id, userId).orElseThrow(ConversationService::notFound);
    }
    private Selection selection(ChatUser user, ChatConversation conversation) {
        if(scope!=null&&!java.util.Objects.equals(scope.profile(user.getUserId(),true),user.getHermesProfile()))
            throw new HermesException(409,"Perfil da conversa inconsistente.");
        if (user.getHermesProfile() == null || user.getHermesProfile().isBlank()
                || ("default".equals(user.getHermesProfile()) && !user.getUserId().equals(identity.ownerId())))
            throw new HermesException(503, "Este usuário precisa de um perfil Hermes isolado antes de conversar.");
        return new Selection(conversation.getId(), conversation.getHermesSessionId(), user.getHermesProfile());
    }
    private View view(ChatConversation conversation, ChatUser user) {
        return new View(conversation.getId(), conversation.getHermesSessionId(), conversation.getTitle(),
                conversation.getCreatedAt(), conversation.getId().equals(user.getCurrentConversationId()));
    }
    private static UUID parseId(String id) {
        try { return UUID.fromString(id); }
        catch (IllegalArgumentException e) { throw new HermesException(400, "conversationId inválido."); }
    }
    private static HermesException notFound() { return new HermesException(404, "Conversa não encontrada para este usuário."); }
}
