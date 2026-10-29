package ayla.finance;

import static ayla.finance.FinanceData.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Hidden;

@Hidden
@RestController
@RequestMapping("/internal/hermes/finance")
public class FinanceBridgeController {
    private final FinanceService service;
    private final FinanceInsights insights;
    private final JdbcTemplate jdbc;
    private final byte[] key;
    private final FinanceAgentLedger ledger;
    private ayla.household.HouseholdService household;
    @org.springframework.beans.factory.annotation.Autowired
    public void setHousehold(ayla.household.HouseholdService household){this.household=household;}
    public FinanceBridgeController(FinanceService service,FinanceInsights insights,JdbcTemplate jdbc, FinanceAgentLedger ledger,
            @Value("${ayla.finance.bridge-key:}") String key) {
        this.service=service; this.insights=insights; this.jdbc=jdbc; this.ledger=ledger; this.key=key.getBytes(StandardCharsets.UTF_8);
    }
    public record Command(UUID id,Long version,LocalDate from,LocalDate to,
                          Instant asOf,Group groupBy,Type type,String category,Integer page,Integer size,
                          String amount, FinanceAgentLedger.Unit unit, String description, OffsetDateTime occurredAt, Integer months, Boolean outsideShoppingList) {
        FinanceAgentLedger.RecordInput recordInput() {
            return new FinanceAgentLedger.RecordInput(type,amount,unit,description,category,occurredAt);
        }
    }

    @PostMapping("/{operation}")
    @org.springframework.transaction.annotation.Transactional
    public Object execute(@RequestHeader(value="Authorization",defaultValue="") String authorization,
            @RequestHeader(value="X-Ayla-Session",defaultValue="") String session,
            @RequestHeader(value="Idempotency-Key",required=false) String requestKey,
            @PathVariable String operation,@RequestBody Command command) {
        if (key.length<32 || !MessageDigest.isEqual(key,authorization.replaceFirst("^Bearer ","").getBytes(StandardCharsets.UTF_8))
                || !authorization.startsWith("Bearer ")) throw new FinanceException(401,"Integração financeira não autorizada.");
        var owners=jdbc.queryForList("select user_id from chat_conversations where hermes_session_id=?",String.class,session);
        if (owners.size()!=1) throw new FinanceException(403,"Abra esta conversa pela API Java para acessar o financeiro.");
        String user=owners.getFirst();
        ayla.monitoring.MonitoringContext.user(user);
        return switch(operation) {
            case "record" -> {
                if(command.type()==Type.EXPENSE && !Boolean.TRUE.equals(command.outsideShoppingList())) {
                    String description=FinanceAgentLedger.description(command.description());
                    long cents=FinanceAgentLedger.cents(command.amount(),command.unit());
                    var turns=jdbc.queryForList("select t.id from finance_agent_turns t join chat_conversations c on c.id=t.conversation_id where t.user_id=? and c.hermes_session_id=? order by t.sequence desc limit 1",UUID.class,user,session);
                    if(turns.isEmpty())throw new FinanceException(409,"Envie o pedido pela conversa antes de registrar.");
                    var shopping=household.routeShoppingPrice(user,description,cents,turns.getFirst());
                    if(shopping!=null)yield java.util.Map.of("destination","SHOPPING","shoppingItem",shopping);
                }
                var entry=ledger.record(user,jdbc.queryForObject("select id from chat_conversations where user_id=? and hermes_session_id=?",UUID.class,user,session),command.recordInput());
                yield java.util.Map.of("entry",entry,"summary",service.summary(user,null,null,null,null));
            }
            case "get" -> service.get(user,requiredId(command));
            case "list" -> service.list(user,command.from(),command.to(),command.type(),command.category(),
                    command.page()==null?0:command.page(),command.size()==null?50:command.size());
            case "summary" -> service.summary(user,command.from(),command.to(),command.asOf(),command.groupBy(),command.category());
            case "insights" -> insights.insights(user,command.months()==null?6:command.months());
            case "update" -> {
                var entry=ledger.update(user,requiredId(command),command.version(),command.recordInput());
                yield java.util.Map.of("entry",entry,"summary",service.summary(user,null,null,null,null));
            }
            case "delete" -> {
                if (command.version()==null) throw new FinanceException(400,"Versão obrigatória.");
                service.delete(user,requiredId(command),command.version());
                yield java.util.Map.of("deleted",true,"summary",service.summary(user,null,null,null,null));
            }
            default -> throw new FinanceException(404,"Operação não encontrada.");
        };
    }
    private static UUID requiredId(Command command) {
        if (command.id()==null) throw new FinanceException(400,"ID obrigatório."); return command.id();
    }
}
