package ayla.chat.service;

import ayla.chat.dto.ChatRequest;
import java.text.Normalizer;
import java.util.Locale;

/** A format preference for this turn; never inferred from earlier conversation turns. */
public final class AudioReplyPolicy {
    private AudioReplyPolicy() {}
    public static boolean wantsAudio(ChatRequest request,String currentMessage) {
        if(request.audioReply()!=null) return request.audioReply();
        String text=Normalizer.normalize(currentMessage,Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT);
        if(textOnly(text)) return false;
        if(!request.audios().isEmpty() && !(request.whatsappContext()!=null && request.whatsappContext().referenceAudio())) return true;
        if(text.matches("(?s).*\\bcomo\\s+(?:eu\\s+)?(?:enviar|envio|mandar|gravar)\\b.*")) return false;
        return text.matches("(?s).*\\b(?:manda|mande|mandar|envia|envie|enviar|responde|responda|responder|fala|fale|falar|quero|gostaria|grave|grava|gravar)\\b[^.!?\\n]{0,65}\\b(?:audio|voz)\\b.*");
    }
    public static boolean allowsSpontaneous(ChatRequest request,String message) {
        String text=Normalizer.normalize(message,Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT);
        return !Boolean.FALSE.equals(request.audioReply()) && !textOnly(text);
    }
    private static boolean textOnly(String text) {
        return text.matches("(?s).*\\b(?:nao|sem|evite)\\b[^.!?\\n]{0,60}\\b(?:audio|voz)\\b.*")
                || text.matches("(?s).*\\b(?:responda|responde|mande|manda|envie|prefiro|quero)\\b[^.!?\\n]{0,35}\\btexto\\b.*");
    }

}
