package ayla.monitoring;

import java.time.Instant;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Hidden;

@Hidden
@RestController
public class WhatsappMonitoringBridge {
    private final MonitoringService service;
    private final String ownerId;
    public WhatsappMonitoringBridge(MonitoringService service,@Value("${ayla.chat.owner-id:local-owner}") String ownerId){this.service=service;this.ownerId=ownerId;}
    public enum Stage { AUDIO_DOWNLOAD,IMAGE_DOWNLOAD,DOCUMENT_DOWNLOAD,DELIVERY,AGENT,AUTHORIZATION,CONTEXT,OTHER }
    public record Failure(@NotNull Stage stage) {}
    @PostMapping("/internal/monitoring/whatsapp") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void failure(@Valid @RequestBody Failure input){
        String action=switch(input.stage()){
            case AUDIO_DOWNLOAD->"Baixar áudio no WhatsApp";case IMAGE_DOWNLOAD->"Baixar imagem no WhatsApp";
            case DOCUMENT_DOWNLOAD->"Baixar documento no WhatsApp";case DELIVERY->"Enviar resposta no WhatsApp";
            case AGENT->"Consultar a Ayla pelo WhatsApp";case AUTHORIZATION->"Configurar autorização no WhatsApp";
            case CONTEXT->"Ler contexto solicitado no WhatsApp";case OTHER->"Processar mensagem no WhatsApp";};
        service.record(new MonitoringService.Event(UUID.randomUUID(),Instant.now(),ownerId,"WHATSAPP","EVENT","whatsapp/"+input.stage().name().toLowerCase(java.util.Locale.ROOT),
                action,503,0,"WhatsappProcessingError","Falha no processamento do WhatsApp antes da conclusão da mensagem. Consulte os logs do agentNode nesse horário.",null));
    }
}
