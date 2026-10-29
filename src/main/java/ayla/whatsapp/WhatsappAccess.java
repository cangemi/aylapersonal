package ayla.whatsapp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ayla.chat.session.ConversationService;

@Service
public class WhatsappAccess {
    public record Access(String userId,UUID conversationId) {}
    private final JdbcTemplate jdbc;
    private final ConversationService conversations;
    private final byte[] key;
    public WhatsappAccess(JdbcTemplate jdbc,ConversationService conversations,@Value("${ayla.whatsapp.api-key:}") String key) {
        this.jdbc=jdbc;this.conversations=conversations;this.key=key.getBytes(StandardCharsets.UTF_8);
    }
    @Transactional
    public Access resolve(String authorization,String contactId) {return lookup(authorization,contactId,true,null);}
    @Transactional
    public Access resolve(String authorization,String contactId,String generation){return lookup(authorization,contactId,true,generation);}
    @Transactional
    public Access inspect(String authorization,String contactId) {return lookup(authorization,contactId,false,null);}
    private Access lookup(String authorization,String contactId,boolean create,String generation) {
        if (key.length<32 || authorization==null || !authorization.startsWith("Bearer ")
                || !MessageDigest.isEqual(key,authorization.substring(7).getBytes(StandardCharsets.UTF_8)))
            throw new WhatsappException(401,"Integração WhatsApp não autorizada.");
        UUID id;
        try {id=UUID.fromString(contactId);} catch(IllegalArgumentException e) {throw new WhatsappException(400,"Conversa WhatsApp inválida.");}
        var rows=jdbc.queryForList("select c.conversation_id,c.display_name,c.enabled,c.history_reset_at,i.owner_user_id from whatsapp_chats c join whatsapp_instances i on i.id=c.instance_id join auth_accounts a on a.user_id=i.owner_user_id where c.id=? and a.enabled",id);
        if (rows.isEmpty() || !Boolean.TRUE.equals(rows.getFirst().get("enabled")))
            throw new WhatsappException(403,"Esta conversa não está autorizada.");
        var row=rows.getFirst();String owner=(String)row.get("owner_user_id");
        if(create){
            jdbc.queryForList("select user_id from chat_users where user_id=? for update",owner);
            rows=jdbc.queryForList("select c.conversation_id,c.display_name,c.enabled,c.history_reset_at,i.owner_user_id from whatsapp_chats c join whatsapp_instances i on i.id=c.instance_id join auth_accounts a on a.user_id=i.owner_user_id where c.id=? and i.owner_user_id=? and a.enabled for update of c",id,owner);
            if(rows.isEmpty()||!Boolean.TRUE.equals(rows.getFirst().get("enabled")))throw new WhatsappException(403,"Esta conversa não está autorizada.");
            row=rows.getFirst();
            if(generation!=null){
                var reset=(java.sql.Timestamp)row.get("history_reset_at");
                String expected=reset==null?"none":reset.toInstant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString();
                boolean matches=expected.equals(generation);
                if(!matches&&reset!=null)try{matches=reset.toInstant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).equals(java.time.Instant.parse(generation));}catch(java.time.format.DateTimeParseException ignored){}
                if(!matches)throw new WhatsappException(409,"A conversa foi apagada. Mensagens anteriores à exclusão não serão processadas.");
            }
        }
        UUID conversation=(UUID)row.get("conversation_id");
        if(conversation!=null&&!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from chat_conversations where user_id=? and id=? and deleted_at is null)",Boolean.class,owner,conversation)))conversation=null;
        if (conversation==null&&create) {
            String title="WhatsApp: "+row.get("display_name");
            conversation=conversations.newDetachedConversation(owner,title.substring(0,Math.min(120,title.length()))).conversationId();
            jdbc.update("update whatsapp_chats set conversation_id=?,updated_at=CURRENT_TIMESTAMP where id=?",conversation,id);
        }
        return new Access(owner,conversation);
    }
}
