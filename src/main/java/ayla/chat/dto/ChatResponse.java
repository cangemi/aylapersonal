package ayla.chat.dto;


public record ChatResponse(
        @io.swagger.v3.oas.annotations.media.Schema(description = "Resposta final da Ayla, com links quando usar fontes.") String response,
        @io.swagger.v3.oas.annotations.media.Schema(description = "Sessão do Hermes; pode mudar se o Hermes rotacionar a sessão.") String sessionId,
        @io.swagger.v3.oas.annotations.media.Schema(description = "Identificador estável da conversa do usuário.", format = "uuid") String conversationId,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        @io.swagger.v3.oas.annotations.media.Schema(description="Resposta falada opcional em base64; texto continua disponível.") ChatAudio audio,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        @io.swagger.v3.oas.annotations.media.Schema(description="Complemento escrito após o áudio: links ou dados necessários para copiar.") String textSupplement) {
    public ChatResponse(String response,String sessionId,String conversationId,ChatAudio audio) {this(response,sessionId,conversationId,audio,null);}
    public ChatResponse(String response,String sessionId,String conversationId) {this(response,sessionId,conversationId,null);}
    public ChatResponse(String response, String sessionId) { this(response, sessionId, null); }
}
