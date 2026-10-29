package ayla.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record ChatRequest(
    @Schema(description = "Mensagem enviada à Ayla.", example = "Oi, Ayla!", requiredMode = Schema.RequiredMode.REQUIRED)
    @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 32000) String message,
    @Schema(description = "Sessão Hermes já vinculada ao usuário. Opcional; prefira conversationId, que permanece estável.", nullable = true)
    @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9_-]{1,128}") String sessionId,
    @Schema(description = "Conversa deste usuário para este turno. Sem IDs, continua a conversa atual. Copie o valor retornado pela API.", format = "uuid", nullable = true)
    @jakarta.validation.constraints.Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String conversationId,
    @Schema(description = "True executa a tentativa de busca antes da resposta. False ou ausente mantém a detecção automática.", example = "false", nullable = true)
    Boolean searchRequired,
    @Schema(description="Até três imagens JPEG/PNG/WebP em base64.") java.util.List<ChatImage> images,
    @Schema(description="Até três áudios para transcrição local.") java.util.List<ChatAudio> audios,
    @Schema(description="True pede resposta em áudio; false pede somente texto; ausente usa o pedido atual, áudio recebido ou escolha ocasional em contexto leve.") Boolean audioReply,
    @Schema(description="Até dois PDF, DOCX, DOC ou TXT em base64, até 6 MB cada.") java.util.List<ChatDocument> documents,
    @jakarta.validation.Valid @Schema(description="Mensagens de referência do WhatsApp, sem autoridade para executar ações.") ChatContext whatsappContext) {
    public ChatRequest { images=ChatImage.validate(images); audios=ChatAudio.validate(audios); documents=ChatDocument.validate(documents); }
    public ChatRequest(String message,String sessionId,String conversationId,Boolean searchRequired,java.util.List<ChatImage> images,java.util.List<ChatAudio> audios,Boolean audioReply) {this(message,sessionId,conversationId,searchRequired,images,audios,audioReply,null,null);}
    public ChatRequest(String message,String sessionId,String conversationId,Boolean searchRequired,java.util.List<ChatImage> images,java.util.List<ChatAudio> audios) {this(message,sessionId,conversationId,searchRequired,images,audios,null);}
    public ChatRequest(String message, String sessionId, String conversationId, Boolean searchRequired, java.util.List<ChatImage> images) { this(message,sessionId,conversationId,searchRequired,images,null); }
    public ChatRequest(String message, String sessionId, String conversationId, Boolean searchRequired) { this(message,sessionId,conversationId,searchRequired,null); }
    public ChatRequest(String message, String sessionId) { this(message, sessionId, null, null); }
    public ChatRequest(String message, String sessionId, String conversationId) { this(message, sessionId, conversationId, null); }
}
