package ayla.finance;

import static ayla.finance.FinanceData.*;
import ayla.chat.session.ChatIdentity;
import jakarta.validation.Valid;
import java.security.Principal;
import java.time.*;
import java.util.UUID;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/finance")
@Tag(name="Financeiro", description="Receitas, despesas e gráficos em centavos inteiros de BRL. Todos os campos monetários terminam em Cents. Datas por America/Sao_Paulo. Dados do usuário atual.")
public class FinanceController {
    private final FinanceService service;
    private final FinanceInsights insights;
    private final ChatIdentity identity;
    public FinanceController(FinanceService service, FinanceInsights insights, ChatIdentity identity) { this.service=service; this.insights=insights; this.identity=identity; }

    @PostMapping("/entries")
    @Operation(summary="Registrar receita ou despesa", description="Use Idempotency-Key para repetir uma requisição sem duplicar o lançamento. amountCents inteiro positivo (3590 = R$ 35,90); INCOME soma e EXPENSE subtrai.")
    public ResponseEntity<Entry> create(@Parameter(hidden=true) Principal principal, @Valid @RequestBody Input entry,
            @RequestHeader(value="Idempotency-Key",required=false) String key) {
        var saved=service.create(identity.resolve(principal),entry,key);
        return ResponseEntity.created(java.net.URI.create("/api/v1/finance/entries/"+saved.id())).body(saved);
    }
    @GetMapping("/entries/{id}")
    @Operation(summary="Consultar lançamento")
    public Entry get(@Parameter(hidden=true) Principal principal, @PathVariable UUID id) { return service.get(identity.resolve(principal),id); }

    @GetMapping("/entries")
    @Operation(summary="Listar lançamentos com filtros e paginação", description="from/to são dias inclusivos no fuso de São Paulo. Página inicial 0; tamanho máximo 100. Inclui lançamentos futuros.")
    public Entries list(@Parameter(hidden=true) Principal principal, @RequestParam(required=false) LocalDate from,
            @RequestParam(required=false) LocalDate to, @RequestParam(required=false) Type type,
            @RequestParam(required=false) String category, @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="50") int size) {
        return service.list(identity.resolve(principal),from,to,type,category,page,size);
    }
    @PutMapping("/entries/{id}")
    @Operation(summary="Substituir lançamento", description="Envie {version, entry}. Use a versão da última consulta; edição concorrente retorna 409.")
    public Entry update(@Parameter(hidden=true) Principal principal, @PathVariable UUID id, @Valid @RequestBody Edit edit) {
        return service.update(identity.resolve(principal),id,edit);
    }
    @DeleteMapping("/entries/{id}")
    @Operation(summary="Excluir lançamento", description="Exige a versão atual para evitar apagar uma edição recente.")
    public ResponseEntity<Void> delete(@Parameter(hidden=true) Principal principal,@PathVariable UUID id,@RequestParam long version) {
        service.delete(identity.resolve(principal),id,version); return ResponseEntity.noContent().build();
    }
    @GetMapping("/summary")
    @Operation(summary="Saldo, totais e séries para gráficos", description="Saldo acumulado de todos os registros até asOf (padrão: agora), sem lançamentos futuros. Totais e série usam from/to inclusivos; padrão: início do mês até hoje. groupBy=DAY, MONTH ou CATEGORY. category (opcional) restringe totais e série a uma categoria, sem afetar o saldo. Dias sem movimentos não são incluídos. Não representa saldo bancário sincronizado.")
    public Summary summary(@Parameter(hidden=true) Principal principal,@RequestParam(required=false) LocalDate from,
            @RequestParam(required=false) LocalDate to,@RequestParam(required=false) Instant asOf,
            @RequestParam(defaultValue="DAY") Group groupBy,@RequestParam(required=false) String category) {
        return service.summary(identity.resolve(principal),from,to,asOf,groupBy,category);
    }
    @GetMapping("/insights")
    @Operation(summary="Análise de gastos: categorias, tendência, ritmo do mês e comparação com o mês anterior", description="Gastos excluem a categoria Investimento (aportes aparecem à parte em investedCents). months de 2 a 24.")
    public FinanceInsights.Insights insights(@Parameter(hidden=true) Principal principal,@RequestParam(defaultValue="6") int months) {
        return insights.insights(identity.resolve(principal),months);
    }
}
