package ayla.chat.controller;

import ayla.chat.dto.ChatRequest;
import ayla.chat.dto.ChatResponse;
import ayla.chat.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat")
@Tag(name = "Chat", description = "Converse com a Ayla e solicite pesquisas na internet.")
public class ChatController {

    
    private final ChatService chatService;
    private final ayla.chat.session.ChatIdentity identity;

    private final ayla.whatsapp.WhatsappAccess whatsapp;
    private ayla.chat.queue.ChatQueueService queue;
    private ayla.chat.queue.ChatQueueStore jobs;
    @org.springframework.beans.factory.annotation.Autowired
    public void setQueue(ayla.chat.queue.ChatQueueService queue,ayla.chat.queue.ChatQueueStore jobs){this.queue=queue;this.jobs=jobs;}
    @org.springframework.beans.factory.annotation.Autowired
    public ChatController(ChatService chatService, ayla.chat.session.ChatIdentity identity, ayla.whatsapp.WhatsappAccess whatsapp) {
        this.chatService=chatService;this.identity=identity;this.whatsapp=whatsapp;
    }
    public ChatController(ChatService chatService, ayla.chat.session.ChatIdentity identity) {this(chatService,identity,null);}
    @PostMapping
    @Operation(summary = "Enviar uma mensagem à Ayla", description = "Sem IDs, continua a conversa atual do usuário. conversationId escolhe uma conversa existente para este turno; searchRequired força a tentativa de pesquisa no Hermes.",
        requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ChatRequest.class), examples = {
                @ExampleObject(name = "Conversa normal", value = "{\"message\":\"Oi, Ayla! Como você tá?\"}"),
                @ExampleObject(name = "Pesquisa obrigatória", value = "{\"message\":\"Calendário oficial da FNCS 2026\",\"searchRequired\":true}"),
                @ExampleObject(name = "Continuar conversa específica", value = "{\"message\":\"Vamos continuar\",\"conversationId\":\"ee64a34f-2c12-4b13-8661-ae361dfde15d\"}")
            })),
        responses = {
            @ApiResponse(responseCode = "202", description = "Pedido salvo na fila; consulte Location até a resposta ficar pronta", content = @Content(schema = @Schema(implementation = ayla.chat.queue.ChatQueueStore.Job.class))),
            @ApiResponse(responseCode = "200", description = "Resposta e identificadores da conversa e sessão"),
            @ApiResponse(responseCode = "400", description = "Campos inválidos ou identificadores incompatíveis", content = @Content(schema = @Schema(implementation = ayla.chat.dto.ChatErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Autenticação necessária quando o modo pessoal estiver desativado", content = @Content(schema = @Schema(implementation = ayla.chat.dto.ChatErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Conversa não encontrada para o usuário"),
            @ApiResponse(responseCode = "409", description = "Modelo deixou de ser gratuito ou sessão foi atualizada"),
            @ApiResponse(responseCode = "429", description = "Limite de uso do Hermes atingido"),
            @ApiResponse(responseCode = "502", description = "Hermes não concluiu a resposta"),
            @ApiResponse(responseCode = "503", description = "Hermes indisponível, sem modelo gratuito ou perfil não configurado")
        })
    public Object chat(@jakarta.validation.Valid @RequestBody ChatRequest request,
                             @Parameter(hidden = true) java.security.Principal principal,
                             @Parameter(hidden = true) @org.springframework.web.bind.annotation.RequestHeader(value="Authorization",defaultValue="") String authorization,
                             @Parameter(hidden = true) @org.springframework.web.bind.annotation.RequestHeader(value="X-Ayla-WhatsApp-Chat",required=false) String whatsappChat,
                             @org.springframework.web.bind.annotation.RequestHeader(value="Idempotency-Key",required=false) String requestKey,
                             @org.springframework.web.bind.annotation.RequestHeader(value="Prefer",defaultValue="") String prefer,
                             @Parameter(hidden=true) @org.springframework.web.bind.annotation.RequestHeader(value="X-Ayla-WhatsApp-Generation",required=false) String generation) {
        if (whatsappChat!=null) {
            if (whatsapp==null) throw new ayla.whatsapp.WhatsappException(503,"Integração WhatsApp não configurada.");
            var access=whatsapp.resolve(authorization,whatsappChat,generation);
            if(queue!=null)return queued(access.userId(),new ChatRequest(request.message(),null,access.conversationId().toString(),request.searchRequired(),request.images(),request.audios(),request.audioReply(),request.documents(),request.whatsappContext()),requestKey,java.util.UUID.fromString(whatsappChat),prefer);
            return chatService.chat(new ChatRequest(request.message(),null,access.conversationId().toString(),request.searchRequired(),request.images(),request.audios(),request.audioReply(),request.documents(),request.whatsappContext()),access.userId());
        }
        if(queue!=null)return queued(identity.resolve(principal),request,requestKey,null,prefer);
        return chatService.chat(request, identity.resolve(principal));
    }
    public Object chat(ChatRequest request,java.security.Principal principal,String authorization,String whatsappChat,String requestKey,String prefer){return chat(request,principal,authorization,whatsappChat,requestKey,prefer,null);}
    private Object queued(String user,ChatRequest request,String key,java.util.UUID whatsappChat,String prefer){
        ayla.monitoring.MonitoringContext.user(user);
        var job=queue.submit(user,request,key,whatsappChat);
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(prefer.contains("respond-async")?0:20);
        while(java.util.Set.of("QUEUED","RUNNING").contains(job.status())&&System.nanoTime()<until){
            try {Thread.sleep(200);}catch(InterruptedException error){Thread.currentThread().interrupt();break;}
            job=jobs.get(user,job.id(),whatsappChat);
        }
        if(job.status().equals("COMPLETED")&&!prefer.contains("respond-async")){jobs.acknowledge(user,job.id(),whatsappChat);return job.result();}
        return org.springframework.http.ResponseEntity.accepted().cacheControl(org.springframework.http.CacheControl.noStore())
                .header("Location","/api/v1/chat/jobs/"+job.id()).header("Retry-After","2").body(job);
    }
    private String jobUser(java.security.Principal principal,String authorization,String whatsappChat){
        return whatsappChat==null?identity.resolve(principal):whatsapp.inspect(authorization,whatsappChat).userId();
    }
    @org.springframework.web.bind.annotation.GetMapping("/jobs/{id}")
    @Operation(summary="Consultar status e resposta de um pedido na fila")
    public org.springframework.http.ResponseEntity<?> job(@org.springframework.web.bind.annotation.PathVariable java.util.UUID id,java.security.Principal principal,
            @org.springframework.web.bind.annotation.RequestHeader(value="Authorization",defaultValue="") String authorization,
            @org.springframework.web.bind.annotation.RequestHeader(value="X-Ayla-WhatsApp-Chat",required=false) String whatsappChat){
        return org.springframework.http.ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(jobs.get(jobUser(principal,authorization,whatsappChat),id,whatsappChat==null?null:java.util.UUID.fromString(whatsappChat)));
    }
    @org.springframework.web.bind.annotation.GetMapping("/jobs")
    @Operation(summary="Recuperar pedidos pendentes ou respostas ainda não recebidas de uma conversa")
    public org.springframework.http.ResponseEntity<?> pending(@org.springframework.web.bind.annotation.RequestParam(required=false) java.util.UUID conversationId,java.security.Principal principal,
            @org.springframework.web.bind.annotation.RequestHeader(value="Authorization",defaultValue="") String authorization,
            @org.springframework.web.bind.annotation.RequestHeader(value="X-Ayla-WhatsApp-Chat",required=false) String whatsappChat){
        String user=jobUser(principal,authorization,whatsappChat);
        java.util.UUID conversation=whatsappChat==null?conversationId:whatsapp.inspect(authorization,whatsappChat).conversationId();
        if(conversation==null&&whatsappChat!=null)return org.springframework.http.ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(java.util.List.of());
        if(conversation==null)throw new ayla.hermes.HermesException(400,"Informe a conversa.");
        return org.springframework.http.ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(jobs.pending(user,conversation,whatsappChat==null?null:java.util.UUID.fromString(whatsappChat)));
    }
    @org.springframework.web.bind.annotation.PostMapping("/jobs/{id}/ack")
    @Operation(summary="Confirmar o recebimento da resposta, sem repetir o processamento")
    public void acknowledge(@org.springframework.web.bind.annotation.PathVariable java.util.UUID id,java.security.Principal principal,
            @org.springframework.web.bind.annotation.RequestHeader(value="Authorization",defaultValue="") String authorization,
            @org.springframework.web.bind.annotation.RequestHeader(value="X-Ayla-WhatsApp-Chat",required=false) String whatsappChat){
        jobs.acknowledge(jobUser(principal,authorization,whatsappChat),id,whatsappChat==null?null:java.util.UUID.fromString(whatsappChat));
    }

}

