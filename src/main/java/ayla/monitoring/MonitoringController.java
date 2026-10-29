package ayla.monitoring;

import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.server.ResponseStatusException;
import ayla.auth.AuthData.Identity;
import ayla.chat.session.ChatIdentity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/monitoring")
@Tag(name="Monitoramento",description="Uso e falhas da aplicação. Exclusivo do proprietário/superadministrador.")
public class MonitoringController {
    private final MonitoringService service;
    private final ChatIdentity identity;
    private AiUsageService aiUsage;
    @org.springframework.beans.factory.annotation.Autowired
    public void setAiUsage(AiUsageService aiUsage){this.aiUsage=aiUsage;}
    public MonitoringController(MonitoringService service,ChatIdentity identity){this.service=service;this.identity=identity;}
    private void owner(Identity user){if(user==null||!"OWNER".equals(user.role())||!identity.ownerId().equals(user.userId()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Área exclusiva do superadministrador.");}
    private ResponseEntity<?> result(Object value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
    @GetMapping("/ai-usage") @Operation(summary="Consultar tokens informados pelo Hermes por usuário, modelo e dia",description="Contadores a partir da ativação. Inclui chat e análise visual; chamadas sem uso retornado ficam como não informadas. Não é uma fatura do provedor.")
    public ResponseEntity<?> aiUsage(@AuthenticationPrincipal Identity user,@RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,
            @RequestParam(required=false) String userId,@RequestParam(required=false) String source){
        owner(user);return result(aiUsage.overview(from,to,userId,source));
    }
    @GetMapping("/ai-usage/calls") @Operation(summary="Listar tokens por chamada à IA",description="Histórico dos últimos dois meses, paginado e exclusivo do superadministrador. Não inclui conteúdo das conversas.")
    public ResponseEntity<?> aiCalls(@AuthenticationPrincipal Identity user,@RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,
            @RequestParam(required=false) String userId,@RequestParam(required=false) String source,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){
        owner(user);return result(aiUsage.calls(from,to,userId,source,page,size));
    }
    @GetMapping @Operation(summary="Consultar uso e ações com mais erros")
    public ResponseEntity<?> overview(@AuthenticationPrincipal Identity user,@RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,
            @RequestParam(required=false) String userId,@RequestParam(required=false) String source,@RequestParam(defaultValue="ALL") String outcome,@RequestParam(required=false) String q){
        owner(user);return result(service.overview(new MonitoringService.Query(from,to,userId,source,outcome,q,0,25)));
    }
    @GetMapping("/events") @Operation(summary="Listar atividades de todos os usuários")
    public ResponseEntity<?> events(@AuthenticationPrincipal Identity user,@RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,
            @RequestParam(required=false) String userId,@RequestParam(required=false) String source,@RequestParam(defaultValue="ALL") String outcome,@RequestParam(required=false) String q,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){
        owner(user);return result(service.events(new MonitoringService.Query(from,to,userId,source,outcome,q,page,size)));
    }
    @GetMapping("/events/{id}") @Operation(summary="Consultar detalhes técnicos de um evento")
    public ResponseEntity<?> detail(@AuthenticationPrincipal Identity user,@PathVariable UUID id){owner(user);return result(service.detail(id));}
    @GetMapping("/users") @Operation(summary="Listar usuários para filtrar o monitoramento")
    public ResponseEntity<?> users(@AuthenticationPrincipal Identity user){owner(user);return result(service.users());}
}
