package ayla.hermes;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/hermes")
@Tag(name = "Hermes", description = "Status, catálogo gratuito e configuração do agente.")
public class HermesController {
    public record Settings(String provider, String model, boolean freeOnly, String instructions) {}
    public record Update(
            @Schema(description = "Identificador obtido em GET /models. Null ou vazio ativa a escolha automática de um modelo gratuito.", nullable = true)
            @Size(max = 255) String model,
            @Schema(description = "Instruções adicionais à personalidade da Ayla. Omitir ou enviar null limpa as instruções.", example = "Responda de forma objetiva e informal.")
            @Size(max = 4000) String instructions) {}
    public record Status(boolean connected, boolean ready, String message, String provider, String model) {}
    private final HermesClient client;
    private final HermesModels models;
    private final HermesSettingsService settings;
    public HermesController(HermesModels models, HermesSettingsService settings, HermesClient client) {
        this.models = models; this.settings = settings; this.client = client;
    }
    @Operation(summary = "Verificar conexão e prontidão", description = "HTTP 200 também pode indicar ready=false; consulte message para identificar pendências de login, modelo ou conexão.")
    @GetMapping("/status") public Status status() {
        try {
            client.get("/health");
        } catch (HermesException e) {
            return new Status(false, false, e.getMessage(), "nous", null);
        }
        try {
            var selected = models.select(settings.get().getModel());
            return new Status(true, true, "Hermes pronto com modelo gratuito.", selected.provider(), selected.model());
        } catch (HermesException e) {
            return new Status(true, false, e.getMessage(), "nous", null);
        }
    }
    @Operation(summary = "Listar modelos gratuitos disponíveis", description = "Consulta o catálogo autenticado do perfil principal do Hermes. Preços desconhecidos, pendentes e modelos pagos são excluídos.")
    @GetMapping("/models") public List<HermesModels.Model> models() { return models.available(); }
    @Operation(summary = "Consultar configuração atual", description = "model=null indica escolha automática. provider=nous e freeOnly=true são fixos.")
    @GetMapping("/configuration") public Settings configuration() { return view(settings.get()); }
    @Operation(summary = "Configurar modelo e instruções", description = "Substitui a configuração persistida. O modelo informado precisa estar disponível gratuitamente no catálogo; null restaura a escolha automática. Não recebe chaves de providers.")
    @PutMapping("/configuration") public Settings update(@Valid @RequestBody Update request) {
        return view(settings.update(request.model(), request.instructions()));
    }
    private Settings view(HermesConfiguration config) {
        return new Settings("nous", config.getModel(), true, config.getInstructions());
    }
}
