package ayla.investment;

import static ayla.investment.InvestmentData.*;
import ayla.chat.session.ChatIdentity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/investments")
@Tag(name = "Investimentos", description = "Caixinha Nubank: saldo, rendimento pelo CDI do Banco Central e simulações. Aportes e resgates são lançamentos financeiros da categoria Investimento (despesa = aporte, receita = resgate). Valores em centavos.")
public class InvestmentController {
    private final InvestmentService service;
    private final ChatIdentity identity;
    public InvestmentController(InvestmentService service, ChatIdentity identity) { this.service = service; this.identity = identity; }

    @GetMapping("/overview")
    @Operation(summary = "Saldo, rendimento (dia, mês), projeções, CDI e histórico",
            description = "Rendimento bruto estimado, antes do IR e IOF, com CDI diário do Banco Central nos dias úteis. Aportes rendem a partir do dia útil seguinte.")
    public Overview overview(@Parameter(hidden = true) Principal principal) { return service.overview(identity.resolve(principal)); }

    @PutMapping("/settings")
    @Operation(summary = "Definir o percentual do CDI do investimento", description = "A Caixinha Nubank rende 100% do CDI.")
    public Settings settings(@Parameter(hidden = true) Principal principal, @Valid @RequestBody Settings settings) {
        return service.saveSettings(identity.resolve(principal), settings.cdiPercent());
    }

    @PutMapping("/baseline")
    @Operation(summary = "Definir o saldo de partida da caixinha",
            description = "Alinha ao saldo real do Nubank: balanceCents é o saldo na data (padrão hoje); investedCents é quanto já foi aportado (padrão = saldo). Lançamentos de investimento até essa data deixam de contar.")
    public Baseline saveBaseline(@Parameter(hidden = true) Principal principal, @Valid @RequestBody BaselineInput input) {
        return service.saveBaseline(identity.resolve(principal), input);
    }

    @DeleteMapping("/baseline")
    @Operation(summary = "Remover o saldo de partida e voltar a calcular só pelos lançamentos")
    public void clearBaseline(@Parameter(hidden = true) Principal principal) { service.clearBaseline(identity.resolve(principal)); }

    @PostMapping("/simulate")
    @Operation(summary = "Simular rendimento futuro", description = "initialCents omitido usa o saldo atual. O aporte mensal é feito ao fim de cada mês. cdiAnnualPercent omitido usa o CDI atual.")
    public Simulation simulate(@Parameter(hidden = true) Principal principal, @Valid @RequestBody SimulationInput input) {
        return service.simulate(identity.resolve(principal), input);
    }
}
