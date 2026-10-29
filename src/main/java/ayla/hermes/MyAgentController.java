package ayla.hermes;

import java.security.Principal;
import java.util.List;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import ayla.chat.dto.ChatAudio;
import ayla.chat.session.ChatIdentity;

@RestController
@RequestMapping("/api/v1/me/agent")
@Tag(name = "Hermes", description = "Inteligência, personalidade e voz do usuário autenticado.")
public class MyAgentController {
    private final HermesSettingsService settings;
    private final VoiceSettingsService voice;
    private final HermesModels models;
    private final ChatIdentity identity;
    public MyAgentController(HermesSettingsService settings, VoiceSettingsService voice, HermesModels models, ChatIdentity identity) {
        this.settings = settings; this.voice = voice; this.models = models; this.identity = identity;
    }
    @GetMapping("/models")
    public List<HermesModels.Model> models(@Parameter(hidden = true) Principal p) {
        var profile = settings.profile(identity.resolve(p));
        return profile == null ? models.available() : models.available(profile);
    }
    @GetMapping("/configuration")
    public HermesController.Settings configuration(@Parameter(hidden = true) Principal p) { return view(settings.get(identity.resolve(p))); }
    @PutMapping("/configuration")
    public HermesController.Settings update(@Valid @RequestBody HermesController.Update request, @Parameter(hidden = true) Principal p) {
        return view(settings.update(identity.resolve(p), request.model(), request.instructions()));
    }
    @GetMapping("/voice")
    public VoiceSettings voice(@Parameter(hidden = true) Principal p) { return voice.get(identity.resolve(p)); }
    @PutMapping("/voice")
    public VoiceSettings updateVoice(@Valid @RequestBody VoiceSettings request, @Parameter(hidden = true) Principal p) {
        return voice.update(identity.resolve(p), request);
    }
    @GetMapping("/voice/voices")
    public List<VoiceController.Voice> voices() {
        return List.of(new VoiceController.Voice("pt-BR-FranciscaNeural", "Francisca", "Female", "pt-BR"),
                new VoiceController.Voice("pt-BR-ThalitaMultilingualNeural", "Thalita", "Female", "pt-BR"),
                new VoiceController.Voice("pt-BR-AntonioNeural", "Antônio", "Male", "pt-BR"));
    }
    @PostMapping("/voice/preview")
    public ChatAudio preview(@Valid @RequestBody(required = false) VoiceSettings request, @Parameter(hidden = true) Principal p) {
        return voice.preview(identity.resolve(p), request);
    }
    private HermesController.Settings view(HermesConfiguration config) {
        return new HermesController.Settings("nous", config.getModel(), true, config.getInstructions());
    }
}
