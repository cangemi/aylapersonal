package ayla.chat.session;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import ayla.hermes.*;
import ayla.whatsapp.WhatsappGateway;

/** Deletion is exposed only to authenticated frontend clients, never to agent tools. */
@Service
public class ConversationDeletion {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final WhatsappGateway whatsapp;
    private final ayla.cluster.ClusterOperations cluster;private final HermesClient hermes;
    private final AtomicBoolean cleaning=new AtomicBoolean();
    public ConversationDeletion(JdbcTemplate jdbc,PlatformTransactionManager manager,WhatsappGateway whatsapp,ayla.cluster.ClusterOperations cluster,HermesClient hermes){this.jdbc=jdbc;tx=new TransactionTemplate(manager);this.whatsapp=whatsapp;this.cluster=cluster;this.hermes=hermes;}
    private Map<String,Object> owned(String user,UUID id){var rows=jdbc.queryForList("select c.*,u.hermes_profile from chat_conversations c join chat_users u on u.user_id=c.user_id where c.user_id=? and c.id=?",user,id);if(rows.isEmpty())throw new HermesException(404,"Conversa não encontrada.");return rows.getFirst();}
    private boolean personalCandidate(String user,UUID id){return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from whatsapp_chats c join whatsapp_instances i on i.id=c.instance_id where i.owner_user_id=? and c.conversation_id=? and c.enabled and not c.mention_only and c.is_group)",Boolean.class,user,id));}
    public Object plan(String user,UUID id){
        if(owned(user,id).get("deleted_at")!=null)throw new HermesException(404,"Conversa já apagada.");
        if(personalCandidate(user,id)){
            try{return whatsapp.write("POST","/internal/conversations/"+id+"/removal-check",Map.of(),user);}
            catch(RuntimeException unavailable){return Map.of("removeWhatsappGroup",false,"description","A conversa será apagada somente na Ayla. Não foi possível verificar o grupo; ele será preservado no WhatsApp.");}
        }
        return Map.of("removeWhatsappGroup",false,"description","A conversa e seu histórico serão apagados somente na Ayla. A conversa ou grupo do WhatsApp será preservado.");
    }
    public Object delete(String user,UUID id,boolean removeWhatsappGroup){
        return cluster.exclusive("chat:"+id,()->{
            var current=owned(user,id);if(current.get("deleted_at")!=null)return Map.of("deleted",true,"whatsappGroupDeleted",false);
            ensureIdle(user,id);
            boolean groupDeleted=false;
            if(removeWhatsappGroup){
                if(!personalCandidate(user,id))throw new HermesException(409,"Esta conversa não é mais o grupo principal. Atualize antes de apagar.");
                Object result;
                try{result=whatsapp.write("POST","/internal/conversations/"+id+"/remove-personal-group",Map.of(),user);}
                catch(ayla.whatsapp.WhatsappException error){throw new HermesException(error.status(),"Não consegui confirmar a remoção do grupo. A conversa foi preservada na Ayla; atualize o WhatsApp antes de tentar novamente.");}
                if(!(result instanceof Map<?,?> response)||!Boolean.TRUE.equals(response.get("deleted")))throw new HermesException(503,"Não consegui confirmar a exclusão do grupo. A conversa foi preservada aqui.");
                groupDeleted=true;
            }
            tx.execute(status->{
                jdbc.queryForList("select user_id from chat_users where user_id=? for update",user);
                ensureIdle(user,id);
                jdbc.update("update chat_conversations set deleted_at=now() where user_id=? and id=? and deleted_at is null",user,id);
                jdbc.update("update chat_users set current_conversation_id=null where user_id=? and current_conversation_id=?",user,id);
                jdbc.update("update whatsapp_chats c set conversation_id=null,history_reset_at=now(),updated_at=now() from whatsapp_instances i where c.instance_id=i.id and i.owner_user_id=? and c.conversation_id=?",user,id);
                jdbc.update("delete from conversation_context_turns where user_id=? and conversation_id=?",user,id);
                jdbc.update("delete from calendar_conversation_plans where user_id=? and conversation_id=?",user,id);
                jdbc.update("delete from finance_chat_drafts where user_id=? and conversation_id=?",user,id);
                jdbc.update("delete from finance_chat_messages where user_id=? and conversation_id=?",user,id);
                jdbc.update("update finance_agent_turns set user_message='' where user_id=? and conversation_id=?",user,id);
                jdbc.update("update chat_jobs set status=case when status='QUEUED' then 'CANCELLED' else status end,request_json=null,result_json=null,user_message='',notice='Conversa apagada.',delivered_at=now() where user_id=? and conversation_id=?",user,id);
                String profile=(String)current.get("hermes_profile"),session=(String)current.get("hermes_session_id");
                if(profile!=null&&session!=null)jdbc.update("insert into conversation_session_cleanup(conversation_id,user_id,profile,session_id) values (?,?,?,?) on conflict do nothing",id,user,profile,session);
                return true;
            });
            return Map.of("deleted",true,"whatsappGroupDeleted",groupDeleted);
        });
    }
    private void ensureIdle(String user,UUID id){if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from chat_jobs where user_id=? and conversation_id=? and (status='RUNNING' or (status='NEEDS_REVIEW' and review_released_at is null)))",Boolean.class,user,id)))throw new HermesException(409,"Aguarde a Ayla terminar o pedido desta conversa antes de apagar.");}
    @Scheduled(fixedDelay=15000,initialDelay=30000)
    public void cleanup(){
        if(!cleaning.compareAndSet(false,true))return;
        Thread.startVirtualThread(()->{try{
            for(var row:jdbc.queryForList("select * from conversation_session_cleanup where completed_at is null and next_attempt_at<=now() order by next_attempt_at limit 10")){
                int claimed=jdbc.update("update conversation_session_cleanup set next_attempt_at=now()+interval '5 minutes' where conversation_id=? and completed_at is null and next_attempt_at<=now()",row.get("conversation_id"));if(claimed==0)continue;
                String session=(String)row.get("session_id");if(!session.matches("[A-Za-z0-9_-]{1,128}"))continue;
                try{hermes.delete((String)row.get("profile"),"/api/sessions/"+session);complete(row);}catch(HermesException error){if(error.status()==404)complete(row);}
            }
        }catch(RuntimeException error){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Limpeza de conversa pendente. tipo={}",error.getClass().getSimpleName());}finally{cleaning.set(false);}});
    }
    private void complete(Map<String,Object> row){jdbc.update("update conversation_session_cleanup set completed_at=now() where conversation_id=?",row.get("conversation_id"));}
}
