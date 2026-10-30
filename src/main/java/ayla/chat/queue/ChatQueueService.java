package ayla.chat.queue;

import ayla.chat.dto.*;
import ayla.chat.service.ChatService;
import ayla.chat.session.ConversationService;
import ayla.hermes.HermesException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ChatQueueService {
    private final ChatQueueStore store;
    private final ConversationService conversations;
    private final ChatService chat;
    private final Semaphore slots;
    private final java.util.concurrent.atomic.AtomicBoolean wakeQueued=new java.util.concurrent.atomic.AtomicBoolean();
    private final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentMap<UUID,ChatQueueStore.Work> active=new ConcurrentHashMap<>();
    private ayla.hermes.HermesClient hermes;
    private ayla.monitoring.MonitoringService monitoring;
    @org.springframework.beans.factory.annotation.Autowired
    public void setMonitoring(ayla.monitoring.MonitoringService monitoring){this.monitoring=monitoring;}
    @org.springframework.beans.factory.annotation.Autowired
    public void setHermes(ayla.hermes.HermesClient hermes){this.hermes=hermes;}
    public ChatQueueService(ChatQueueStore store,ConversationService conversations,ChatService chat,
            @Value("${ayla.cluster.max-concurrent-chats:3}") int limit){
        this.store=store;this.conversations=conversations;this.chat=chat;this.slots=new Semaphore(limit);
    }
    @Transactional
    public ChatQueueStore.Job submit(String user,ChatRequest request,String key,UUID whatsappChat){
        String requestKey=key==null||key.isBlank()?UUID.randomUUID().toString():key;
        if(!requestKey.matches("[A-Za-z0-9_-]{1,128}"))throw new HermesException(400,"Chave de pedido inválida.");
        String fingerprint;
        try {fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JsonMapper.builder().build().writeValueAsString(request).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);}
        String source=whatsappChat==null?"WEB":"WHATSAPP";
        UUID existing=store.existing(user,requestKey,fingerprint,source,whatsappChat);
        if(existing!=null)return store.get(user,existing,whatsappChat);
        var selection=conversations.resolve(user,request.conversationId(),request.sessionId());
        var bound=new ChatRequest(request.message(),null,selection.conversationId().toString(),request.searchRequired(),request.images(),request.audios(),request.audioReply(),request.documents(),request.whatsappContext());
        UUID id=store.insert(user,selection.conversationId(),requestKey,fingerprint,source,whatsappChat,bound);
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()){
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
                @Override public void afterCommit(){wake();}
            });
        }else wake();
        return store.get(user,id,whatsappChat);
    }
    private void wake(){
        if(!wakeQueued.compareAndSet(false,true))return;
        try{executor.execute(()->{wakeQueued.set(false);dispatch();});}
        catch(RejectedExecutionException stopped){wakeQueued.set(false);}
    }
    @Scheduled(fixedDelay=500,initialDelay=1000)
    public void dispatch(){
        while(slots.tryAcquire()){
            ChatQueueStore.Work work;
            try {work=store.claim();}
            catch(RuntimeException error){slots.release();return;}
            if(work==null){slots.release();return;}
            active.put(work.id(),work);
            try {executor.submit(()->run(work));}
            catch(RejectedExecutionException error){active.remove(work.id());slots.release();return;}
        }
    }
    private void run(ChatQueueStore.Work work){
        long started=System.nanoTime();
        org.slf4j.MDC.put("requestId",work.id().toString());
        var security=SecurityContextHolder.createEmptyContext();
        security.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(work.userId(),null,
                List.of(new SimpleGrantedAuthority(work.source().equals("WHATSAPP")?"ROLE_WHATSAPP":"ROLE_USER"))));
        SecurityContextHolder.setContext(security);
        try(var context=new ChatJobContext(()->store.dispatched(work),()->store.admissionRejected(work))){
            try {
                if(!store.allowed(work)){store.finish(work,"CANCELLED",null,"Este pedido foi cancelado porque o acesso à conversa foi desativado.");return;}
                store.finish(work,"COMPLETED",chat.chat(store.request(work),work.userId()),null);
                event(work,started,200,null);
            } catch(RuntimeException error){
                boolean temporary=error instanceof ayla.cluster.OperationBusyException
                        || error instanceof HermesException h && Set.of(429,503).contains(h.status());
                if(!context.dispatched()&&temporary){
                    if(error instanceof ayla.cluster.OperationBusyException)store.waitAgain(work,1+Math.floorMod(work.id().hashCode(),3));
                    else store.waitAgain(work);
                }
                else {store.finish(work,context.dispatched()?"NEEDS_REVIEW":"FAILED",null,
                        context.dispatched()?"Não consegui confirmar a conclusão. Não repeti sua solicitação; confira se a alteração já ocorreu antes de reenviar."
                        :"Não consegui processar esse pedido. Confira os dados enviados e a configuração da Ayla.");
                    event(work,started,error instanceof HermesException h?h.status():502,error);
                }
            }
        } catch(RuntimeException error){
            // A failed database write leaves the lease for recovery, never repeats an uncertain inference.
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Fila de conversa aguardando recuperação. tipo={}",error.getClass().getSimpleName());
        } finally {SecurityContextHolder.clearContext();org.slf4j.MDC.remove("requestId");active.remove(work.id());slots.release();wake();}
    }
    private void event(ChatQueueStore.Work work,long started,int status,RuntimeException error){
        if(monitoring==null)return;
        try {monitoring.record(new ayla.monitoring.MonitoringService.Event(work.id(),java.time.Instant.now(),work.userId(),work.source(),"JOB",
                "/api/v1/chat/jobs/{id}","Processar pedido na fila da Ayla",status,(System.nanoTime()-started)/1_000_000,
                error==null?null:error.getClass().getSimpleName(),error==null?null:"Não foi possível confirmar a conclusão do pedido na fila.",null));}
        catch(RuntimeException unavailable){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Monitoramento da fila indisponível. tipo={}",unavailable.getClass().getSimpleName());}
    }
    @Scheduled(fixedDelay=15000,initialDelay=15000)
    public void maintain(){
        try {
            for(var work:active.values())store.heartbeat(work);store.recover();
            // A timed-out HTTP call may still be executing tools remotely. Do not start a new turn
            // in that conversation until the gateway confirms that all agent calls are finished.
            for(var profile:store.blockedProfiles()){
                try {
                    var health=hermes.get((String)profile.get("hermes_profile"),"/health/detailed");
                    if(health.get("api_server") instanceof Map<?,?> api && api.get("active_runs") instanceof Number runs && runs.longValue()==0)
                        store.releaseReviewed((String)profile.get("user_id"));
                } catch(RuntimeException unavailable){ /* Keep the ordering barrier while the gateway is unavailable. */ }
            }
        }
        catch(RuntimeException error){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Manutenção da fila indisponível. tipo={}",error.getClass().getSimpleName());}
    }
    @jakarta.annotation.PreDestroy void shutdown(){executor.shutdownNow();}
}
