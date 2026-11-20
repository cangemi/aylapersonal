package ayla.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Resultado da tentativa de resposta local antes da fila: resposta direta ou espera contextual. */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TurnPreview(
    @Schema(description="True quando o modelo local respondeu sem ferramentas.") boolean replied,
    @Schema(description="Conversa resolvida para este turno.", format="uuid") String conversationId,
    @Schema(description="Transcrição do áudio no modo de voz.") String transcript,
    @Schema(description="Frase de espera contextual quando o turno precisa do Hermes.") String hold,
    @Schema(description="Resposta direta do modelo local.") String response,
    @Schema(description="Resposta falada em base64 no modo de voz.") ChatAudio audio,
    @Schema(description="Complemento escrito após a fala.") String textSupplement,
    @Schema(description="Fala transcrita e sintetizada no modo de voz.") ChatResponse.VoiceTurn voiceTurn) {}
