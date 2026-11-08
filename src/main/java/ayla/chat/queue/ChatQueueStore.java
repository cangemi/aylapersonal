package ayla.chat.queue;

import ayla.chat.dto.*;
import ayla.hermes.HermesException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class ChatQueueStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper json=JsonMapper.builder().findAndAddModules().build();
    public ChatQueueStore(JdbcTemplate jdbc,PlatformTransactionManager manager){this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);}
    public record Job(UUID id,String status,UUID conversationId,String message,int position,ChatResponse result,String userMessage,Map<String,Object> availability,boolean retryable) {
        public Job(UUID id,String status,UUID conversationId,String message,int position,ChatResponse result,String userMessage){this(id,status,conversationId,message,position,result,userMessage,null,false);}
    }
    public record Work(UUID id,String userId,UUID conversationId,String source,UUID whatsappChat,UUID token,String request,int attempts) {}

    public UUID insert(String user,UUID conversation,String key,String fingerprint,String source,UUID whatsappChat,ChatRequest request){
        UUID id=UUID.randomUUID();
        jdbc.update("""
                insert into chat_jobs(id,user_id,conversation_id,request_key,fingerprint,source,whatsapp_chat_id,request_json,user_message)
                values(?,?,?,?,?,?,?,?,?) on conflict(user_id,request_key) do nothing
                """,id,user,conversation,key,fingerprint,source,whatsappChat,json.writeValueAsString(request),request.message());
        return existing(user,key,fingerprint,source,whatsappChat);
    }
    public UUID existing(String user,String key,String fingerprint,String source,UUID whatsappChat){
        var rows=jdbc.queryForList("select id,fingerprint,source,whatsapp_chat_id from chat_jobs where user_id=? and request_key=?",user,key);
        if(rows.isEmpty())return null;
        var row=rows.getFirst();
        if(!fingerprint.equals(row.get("fingerprint"))||!source.equals(row.get("source"))||!Objects.equals(whatsappChat,row.get("whatsapp_chat_id")))
            throw new HermesException(409,"Essa chave já foi usada para outra mensagem.");
        return (UUID)row.get("id");
    }
    public Job get(String user,UUID id,UUID whatsappChat){
        var rows=jdbc.queryForList("select * from chat_jobs where id=? and user_id=?"+(whatsappChat==null?"":" and whatsapp_chat_id=?"),
                whatsappChat==null?new Object[]{id,user}:new Object[]{id,user,whatsappChat});
        if(rows.isEmpty())throw new HermesException(404,"Pedido não encontrado.");
        var row=rows.getFirst();String status=(String)row.get("status");
        int position=0;
        if(Set.of("QUEUED","RUNNING").contains(status))position=jdbc.queryForObject("select count(*) from chat_jobs where conversation_id=? and sequence<=? and status in ('QUEUED','RUNNING')",Integer.class,row.get("conversation_id"),row.get("sequence"));
        String message=row.get("notice") instanceof String text?text:status.equals("QUEUED")?"Seu pedido está na fila. Vou responder assim que houver capacidade.":status.equals("RUNNING")?"A Ayla está preparando sua resposta.":"";
        var result=row.get("result_json") instanceof String text?json.readValue(text,ChatResponse.class):null;
        Map<String,Object> meta=null;
        if(row.get("provider_notice_json") instanceof String raw){meta=new LinkedHashMap<>(json.readValue(raw,Map.class));meta.put("serverNow",Instant.now());}
        return new Job(id,status,(UUID)row.get("conversation_id"),message,position,result,(String)row.get("user_message"),meta,
            meta!=null&&"FAILED".equals(status)&&row.get("dispatched_at")==null&&row.get("request_json")!=null&&"WEB".equals(row.get("source")));
    }
    public List<Job> pending(String user,UUID conversation,UUID whatsappChat){
        String scope=whatsappChat!=null?" and whatsapp_chat_id=?":" and source='WEB'";
        var ids=jdbc.queryForList("select id from chat_jobs where user_id=? and conversation_id=? and delivered_at is null"+scope+" order by sequence limit 50",UUID.class,
                whatsappChat==null?new Object[]{user,conversation}:new Object[]{user,conversation,whatsappChat});
        return ids.stream().map(id->get(user,id,whatsappChat)).toList();
    }
    public void acknowledge(String user,UUID id,UUID whatsappChat){
        get(user,id,whatsappChat);
        jdbc.update("update chat_jobs set delivered_at=now() where id=? and status not in ('QUEUED','RUNNING')",id);
    }
    public Work claim(){
        return tx.execute(ignored->{
            var rows=jdbc.queryForList("""
                    select j.* from chat_jobs j where j.status='QUEUED' and j.available_at<=now()
                    and not exists(select 1 from chat_jobs older where older.conversation_id=j.conversation_id
                      and older.sequence<j.sequence and (older.status in ('QUEUED','RUNNING') or (older.status='NEEDS_REVIEW' and older.review_released_at is null)))
                    order by j.sequence for update of j skip locked limit 1
                    """);
            if(rows.isEmpty())return null;
            var row=rows.getFirst();UUID token=UUID.randomUUID(),id=(UUID)row.get("id");
            jdbc.update("update chat_jobs set status='RUNNING',lease_token=?,lease_until=now()+interval '2 minutes',started_at=now(),attempts=attempts+1,notice=null where id=?",token,id);
            return new Work(id,(String)row.get("user_id"),(UUID)row.get("conversation_id"),(String)row.get("source"),(UUID)row.get("whatsapp_chat_id"),token,(String)row.get("request_json"),((Number)row.get("attempts")).intValue()+1);
        });
    }
    public boolean allowed(Work work){
        boolean active=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from auth_accounts where user_id=? and enabled)",Boolean.class,work.userId()));
        return active && (work.whatsappChat()==null || Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from whatsapp_chats c join whatsapp_instances i on i.id=c.instance_id
                  where c.id=? and c.conversation_id=? and c.enabled and i.owner_user_id=?)
                """,Boolean.class,work.whatsappChat(),work.conversationId(),work.userId())));
    }
    public void dispatched(Work work){
        if(jdbc.update("update chat_jobs set dispatched_at=coalesce(dispatched_at,now()) where id=? and lease_token=? and status='RUNNING'",work.id(),work.token())!=1)
            throw new HermesException(409,"O processamento deste pedido foi interrompido.");
        jdbc.update("delete from proactive_messages where source='chat-job-input' and source_id=?",work.id());
    }
    public void admissionRejected(Work work){
        jdbc.update("update chat_jobs set dispatched_at=null where id=? and lease_token=? and status='RUNNING'",work.id(),work.token());
    }
    public ChatRequest request(Work work){return json.readValue(work.request(),ChatRequest.class);}
    public void providerNotice(Work work,Map<String,Object> meta){jdbc.update("update chat_jobs set provider_notice_json=? where id=? and lease_token=?",json.writeValueAsString(meta),work.id(),work.token());persistInput(work.id());}
    public void limitedBeforeDispatch(String user,UUID id,ayla.hermes.ProviderUsageData.Limited error){
        jdbc.update("update chat_jobs set status='FAILED',notice=?,provider_notice_json=?,finished_at=now() where user_id=? and id=? and status='QUEUED' and dispatched_at is null",error.getMessage(),json.writeValueAsString(error.availability()),user,id);
        persistNotices();
        persistInput(id);
    }
    private void persistInput(UUID id){
        jdbc.update("""
            insert into proactive_messages(id,user_id,conversation_id,role,text,source,source_id,created_at)
            select ?,user_id,conversation_id,'user',user_message,'chat-job-input',id,created_at from chat_jobs where id=? and dispatched_at is null and provider_notice_json is not null
            on conflict do nothing
            """,UUID.nameUUIDFromBytes(("chat-job-input:"+id).getBytes(java.nio.charset.StandardCharsets.UTF_8)),id);
    }
    public void retry(String user,UUID id){
        // Same durable request, explicit user action, and proof that no remote inference was dispatched.
        if(jdbc.update("""
            update chat_jobs j set status='QUEUED',available_at=now(),finished_at=null,delivered_at=null,lease_token=null,lease_until=null,notice=null
            where j.id=? and j.user_id=? and j.source='WEB' and j.status='FAILED' and j.dispatched_at is null
              and j.request_json is not null and j.provider_notice_json is not null
              and not exists(select 1 from chat_jobs other where other.conversation_id=j.conversation_id and other.id<>j.id
                and (other.status in ('QUEUED','RUNNING') or other.status='NEEDS_REVIEW' and other.review_released_at is null))
            """,id,user)!=1)throw new HermesException(409,"Não é seguro repetir este pedido. Confira o resultado da execução anterior.");
        jdbc.update("delete from proactive_messages where source='chat-job-notice' and source_id=?",id);
    }
    public void finish(Work work,String status,ChatResponse result,String notice){
        tx.executeWithoutResult(ignored->{
        int changed=jdbc.update("""
                update chat_jobs set delivered_at=case when status='NEEDS_REVIEW' and ?='COMPLETED' then null else delivered_at end,
                  status=?,result_json=?,notice=case when provider_notice_json is not null and ?<>'COMPLETED' then coalesce(notice,?) else ? end,
                  finished_at=now(),request_json=case when provider_notice_json is not null and dispatched_at is null and ?='FAILED' then request_json else null end,
                  provider_notice_json=case when ?='COMPLETED' then null else provider_notice_json end,lease_until=null
                where id=? and lease_token=? and status in ('RUNNING','NEEDS_REVIEW')
                """,status,status,result==null?null:json.writeValueAsString(result),status,notice,notice,status,status,work.id(),work.token());
        if(changed>0){
            if("COMPLETED".equals(status))jdbc.update("delete from proactive_messages where source='chat-job-notice' and source_id=?",work.id());
            else persistNotices();
        }
        });
    }
    private void persistNotices(){
        jdbc.update("""
            insert into proactive_messages(id,user_id,conversation_id,role,text,source,source_id,created_at)
            select j.id,j.user_id,j.conversation_id,'assistant',j.notice,'chat-job-notice',j.id,coalesce(j.finished_at,j.created_at)
            from chat_jobs j where j.status in ('FAILED','NEEDS_REVIEW','CANCELLED') and j.notice is not null
            and not exists(select 1 from proactive_messages p where p.source='chat-job-notice' and p.source_id=j.id)
            on conflict do nothing
            """);
    }
    public void waitAgain(Work work){waitAgain(work,(int)Math.min(60,2L*work.attempts()));}
    public void waitAgain(Work work,int delaySeconds){
        if(delaySeconds<1||delaySeconds>60)throw new IllegalArgumentException("Invalid retry delay");
        jdbc.update("""
                update chat_jobs set status='QUEUED',lease_until=null,lease_token=null,dispatched_at=null,
                  available_at=now()+(? * interval '1 second'),notice='Aguardando disponibilidade da Ayla. Seu pedido continua salvo na fila.'
                where id=? and lease_token=? and status='RUNNING' and dispatched_at is null
                """,delaySeconds,work.id(),work.token());
    }
    public void heartbeat(Work work){jdbc.update("update chat_jobs set lease_until=now()+interval '2 minutes' where id=? and lease_token=? and status='RUNNING'",work.id(),work.token());}
    public void recover(){
        jdbc.update("""
                update chat_jobs set status='QUEUED',lease_token=null,lease_until=null,available_at=now()
                where status='RUNNING' and lease_until<now() and dispatched_at is null
                """);
        jdbc.update("""
                update chat_jobs set status='NEEDS_REVIEW',request_json=null,finished_at=now(),lease_until=null,
                  notice='O processamento foi interrompido após começar. Não repeti o pedido para evitar alterações duplicadas. Confira o resultado antes de reenviar.'
                where status='RUNNING' and lease_until<now() and dispatched_at is not null
                """);
        persistNotices();
        jdbc.update("delete from chat_jobs where delivered_at is not null and finished_at<now()-interval '7 days'");
    }
    public List<Map<String,Object>> blockedProfiles(){
        return jdbc.queryForList("""
                select distinct j.user_id,u.hermes_profile,j.conversation_id,c.hermes_session_id
                from chat_jobs j join chat_users u on u.user_id=j.user_id join chat_conversations c on c.id=j.conversation_id
                where j.status='NEEDS_REVIEW' and j.review_released_at is null limit 10
                """);
    }
    public void releaseReviewed(String user,UUID conversation){
        jdbc.update("update chat_jobs set review_released_at=now() where user_id=? and conversation_id=? and status='NEEDS_REVIEW' and review_released_at is null",user,conversation);
    }
}
