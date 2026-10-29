package ayla.hermes;

import java.util.List;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import ayla.chat.dto.ChatAudio;

@RestController
@RequestMapping("/api/v1/hermes/voice")
@Tag(name="Voz", description="Configuração global da voz gratuita, persistida no PostgreSQL.")
public class VoiceController {
    private final VoiceSettingsService settings;
    public VoiceController(VoiceSettingsService settings) { this.settings=settings; }
    public record Voice(String id,String name,String gender,String locale) {}
    @GetMapping
    @Operation(summary="Consultar voz, velocidade e tom atuais")
    public VoiceSettings get() { return settings.get(); }
    @PutMapping
    @Operation(summary="Salvar voz, velocidade e tom", description="Substitui os três parâmetros. Aplica nas próximas sínteses, inclusive após reiniciar; não altera áudios já gerados. Não muda as regras de quando enviar áudio.")
    public VoiceSettings update(@Valid @RequestBody VoiceSettings request) { return settings.update(request); }
    @GetMapping("/voices")
    @Operation(summary="Listar vozes brasileiras suportadas", description="Opções verificadas no catálogo gratuito Edge. Não inclui vozes pagas do Azure.")
    public List<Voice> voices() {
        return List.of(new Voice("pt-BR-FranciscaNeural","Francisca","Female","pt-BR"),
                new Voice("pt-BR-ThalitaMultilingualNeural","Thalita","Female","pt-BR"),
                new Voice("pt-BR-AntonioNeural","Antônio","Male","pt-BR"));
    }
    @PostMapping("/preview")
    @Operation(summary="Ouvir voz sem salvar", description="Envie voice, speed e pitchHz para testar ajustes temporários. Sem corpo, usa a configuração salva. Não altera o PostgreSQL nem a configuração do Hermes; não envia ao WhatsApp nem chama a IA. Retorna mimeType e data em base64 com uma frase fixa.")
    public ChatAudio preview(@Valid @RequestBody(required=false) VoiceSettings request) {
        return settings.preview(request);
    }
}
