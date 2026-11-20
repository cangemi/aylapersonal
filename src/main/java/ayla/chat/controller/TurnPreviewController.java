package ayla.chat.controller;

import ayla.chat.dto.ChatAudio;
import ayla.chat.dto.ChatResponse;
import ayla.chat.dto.ChatRequest;
import ayla.chat.dto.TurnPreview;
import ayla.chat.service.LocalTurnRouter;
import ayla.chat.service.SpontaneousVoice;
import ayla.chat.service.VoiceReplyText;
import ayla.hermes.HermesMediaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat")
@Tag(name = "Chat", description = "Converse com a Ayla e solicite pesquisas na internet.")
public class TurnPreviewController {
    private final ayla.chat.session.ChatIdentity identity;
    private final ayla.chat.session.ConversationService conversations;
    private final HermesMediaService media;
    private final LocalTurnRouter router;
    private final ayla.chat.session.ConversationContext context;
    private final ayla.chat.session.ProactiveMessageService proactive;

    public TurnPreviewController(ayla.chat.session.ChatIdentity identity,
                                 ayla.chat.session.ConversationService conversations,
                                 HermesMediaService media,
                                 LocalTurnRouter router,
                                 ayla.chat.session.ConversationContext context,
                                 ayla.chat.session.ProactiveMessageService proactive) {
        this.identity = identity;
        this.conversations = conversations;
        this.media = media;
        this.router = router;
        this.context = context;
        this.proactive = proactive;
    }

    @PostMapping("/turn-preview")
    @Operation(summary = "Tenta responder sem ferramentas no modelo local",
            description = "Papo comum é respondido na hora pelo modelo local; pedidos com ferramentas devolvem uma frase de espera contextual e seguem pela fila normal.")
    public TurnPreview preview(@Valid @RequestBody ChatRequest request,
                               @Parameter(hidden = true) java.security.Principal principal) {
        String user = identity.resolve(principal);
        ayla.monitoring.MonitoringContext.user(user);
        boolean live = Boolean.TRUE.equals(request.liveVoice());
        var selected = conversations.resolve(user, request.conversationId(), request.sessionId());
        String transcript = null;
        if (live) {
            if (media == null || request.audios() == null || request.audios().size() != 1)
                return fallback(selected.conversationId(), null);
            transcript = media.transcribeVoiceTurn(selected.profile(), request.audios().getFirst());
        }
        boolean bypass = Boolean.TRUE.equals(request.searchRequired())
                || Boolean.TRUE.equals(request.audioReply())
                || request.images() != null && !request.images().isEmpty()
                || request.documents() != null && !request.documents().isEmpty()
                || !live && request.audios() != null && !request.audios().isEmpty();
        if (bypass) return fallback(selected.conversationId(), transcript);
        String message = live ? transcript : request.message();
        var decision = router.decide(user, selected.conversationId(), message, live,
                Boolean.TRUE.equals(request.interruptedVoice()));
        if (!decision.reply())
            return new TurnPreview(false, selected.conversationId().toString(), transcript, decision.hold(),
                    null, null, null, null);
        String content = decision.text().strip();
        boolean amused = live && content.startsWith(SpontaneousVoice.CHUCKLE)
                && SpontaneousVoice.lighthearted(message, SpontaneousVoice.clean(content));
        content = SpontaneousVoice.clean(content);
        if (content.isBlank())
            return new TurnPreview(false, selected.conversationId().toString(), transcript,
                    LocalTurnRouter.holdFor(message), null, null, null, null);
        var voiceText = VoiceReplyText.split(content);
        content = VoiceReplyText.clean(content);
        archive(user, selected.conversationId(), message, content);
        if (live) {
            String spoken = amused ? "Ha, ha! " + voiceText.speech() : voiceText.speech();
            ChatAudio audio = media == null ? null : media.speak(selected.profile(), spoken);
            return new TurnPreview(true, selected.conversationId().toString(), transcript, null, content,
                    audio, voiceText.supplement(), new ChatResponse.VoiceTurn(transcript, voiceText.speech(), amused));
        }
        String response = content + (voiceText.supplement() == null ? "" : "\n\n" + voiceText.supplement());
        return new TurnPreview(true, selected.conversationId().toString(), null, null, response, null, null, null);
    }

    private void archive(String user, UUID conversation, String message, String content) {
        if (context != null) try {
            context.rememberTurn(user, conversation, message, content);
        } catch (RuntimeException failure) {
            LoggerFactory.getLogger(getClass()).warn("Contexto do turno local não foi arquivado. tipo={}", failure.getClass().getSimpleName());
        }
        if (proactive != null) proactive.recordLocalTurn(user, conversation, message, content);
        router.rememberLocal(conversation, message, content);
    }

    private static TurnPreview fallback(UUID conversation, String transcript) {
        return new TurnPreview(false, conversation == null ? null : conversation.toString(), transcript,
                null, null, null, null, null);
    }
}
