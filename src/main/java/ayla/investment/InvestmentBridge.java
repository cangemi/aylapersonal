package ayla.investment;

import static ayla.investment.InvestmentData.*;
import ayla.finance.FinanceException;
import io.swagger.v3.oas.annotations.Hidden;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@Hidden
@RestController
@RequestMapping("/internal/hermes/investment")
public class InvestmentBridge {
    private final InvestmentService service;
    private final JdbcTemplate jdbc;
    private final byte[] key;
    private PortfolioService portfolio;
    @org.springframework.beans.factory.annotation.Autowired
    public void setPortfolio(PortfolioService portfolio){this.portfolio=portfolio;}
    public InvestmentBridge(InvestmentService service, JdbcTemplate jdbc, @Value("${ayla.finance.bridge-key:}") String key) {
        this.service = service; this.jdbc = jdbc; this.key = key.getBytes(StandardCharsets.UTF_8);
    }

    /** Valores em reais como texto ("1500.50"), convertidos aqui para centavos exatos. */
    public record Command(String initialAmount, String monthlyAmount, Integer months, BigDecimal cdiAnnualPercent, BigDecimal percentCdi,
        UUID id,String name,String institution,PortfolioData.AssetType assetType,String ticker,LocalDate openedOn,
        String openingAmount,String investedAmount,PortfolioData.RateType rateType,BigDecimal ratePercent,PortfolioData.DayBasis dayBasis,
        LocalDate maturityDate,String notes,Long version,PortfolioData.EventType eventType,LocalDate date,String amount,
        UUID eventId,Integer year,Integer eventOffset,Integer eventLimit,Boolean clearMaturity) {}

    @PostMapping("/{operation}")
    public Object execute(@RequestHeader(value = "Authorization", defaultValue = "") String authorization,
            @RequestHeader(value = "X-Ayla-Session", defaultValue = "") String session,
            @PathVariable String operation, @RequestBody Command command) {
        if (key.length < 32 || !authorization.startsWith("Bearer ")
                || !MessageDigest.isEqual(key, authorization.substring(7).getBytes(StandardCharsets.UTF_8)))
            throw new FinanceException(401, "Integração de investimentos não autorizada.");
        var owners = jdbc.queryForList("select user_id from chat_conversations where hermes_session_id=?", String.class, session);
        if (owners.size() != 1) throw new FinanceException(403, "Abra esta conversa pela API Java para acessar os investimentos.");
        String user = owners.getFirst();
        ayla.monitoring.MonitoringContext.user(user);
        return switch (operation) {
            case "portfolio" -> {
                var data=portfolio.portfolio(user);
                var result=new java.util.LinkedHashMap<String,Object>();
                result.put("asOf",data.asOf());result.put("balanceCents",data.balanceCents());result.put("gainCents",data.gainCents());
                result.put("netInvestedCents",data.netInvestedCents());result.put("incomeCents",data.incomeCents());
                result.put("monthGainCents",data.monthGainCents());result.put("legacyCaixinha",data.legacy());result.put("warnings",data.warnings());
                result.put("positions",data.positions().stream().map(InvestmentBridge::agentView).toList());
                yield result;
            }
            case "get_position" -> {
                var view=portfolio.get(user,required(command.id()));
                int offset=command.eventOffset()==null?0:command.eventOffset(),limit=command.eventLimit()==null?30:command.eventLimit();
                if(offset<0||limit<1||limit>100)throw new FinanceException(400,"Use offset positivo e limite de 1 a 100 movimentações.");
                var events=view.events().reversed();
                int start=Math.min(offset,events.size()),end=(int)Math.min((long)start+limit,events.size());
                var result=new java.util.LinkedHashMap<String,Object>(agentView(view));result.remove("recentEvents");
                result.put("events",events.subList(start,end));result.put("hasMoreEvents",end<events.size());result.put("nextEventOffset",end);
                yield result;
            }
            case "save_position" -> {
                var old=command.id()==null?null:portfolio.get(user,command.id());
                var p=old==null?null:old.position();var rate=old==null?null:old.currentRate();
                if(rate!=null&&command.rateType()!=null&&command.rateType()!=rate.type()&&command.rateType()!=PortfolioData.RateType.MANUAL&&command.ratePercent()==null)
                    throw new FinanceException(400,"Informe a taxa da nova regra de rendimento.");
                Long opening=command.openingAmount()!=null?cents(command.openingAmount()):p==null?null:p.openingBalanceCents();
                var input=new PortfolioData.Input(or(command.name(),p==null?null:p.name()),or(command.institution(),p==null?null:p.institution()),
                    or(command.assetType(),p==null?null:p.assetType()),or(command.ticker(),p==null?null:p.ticker()),or(command.openedOn(),p==null?null:p.openedOn()),
                    opening,command.investedAmount()!=null?cents(command.investedAmount()):p==null?opening:Long.valueOf(p.openingInvestedCents()),
                    or(command.rateType(),rate==null?null:rate.type()),command.rateType()==PortfolioData.RateType.MANUAL?BigDecimal.ZERO:or(command.ratePercent(),rate==null?null:rate.percent()),
                    or(command.dayBasis(),rate==null?null:rate.dayBasis()),Boolean.TRUE.equals(command.clearMaturity())?null:or(command.maturityDate(),p==null?null:p.maturityDate()),
                    or(command.notes(),p==null?null:p.notes()),command.version());
                yield agentView(command.id()==null?portfolio.create(user,input,creationKey(user,session,input)):portfolio.update(user,command.id(),input));
            }
            case "event" -> agentView(portfolio.addEvent(user,required(command.id()),new PortfolioData.EventInput(command.eventType(),
                command.date(),cents(command.amount()),command.notes(),command.version())));
            case "delete_position" -> {portfolio.delete(user,required(command.id()),version(command));yield Map.of("deleted",true,"id",command.id());}
            case "delete_event" -> agentView(portfolio.deleteEvent(user,required(command.id()),required(command.eventId()),version(command)));
            case "update_event" -> {
                var current=portfolio.get(user,required(command.id()));
                var event=current.events().stream().filter(item->item.id().equals(required(command.eventId()))).findFirst()
                    .orElseThrow(()->new FinanceException(404,"Movimentação não encontrada."));
                yield agentView(portfolio.updateEvent(user,command.id(),command.eventId(),new PortfolioData.EventInput(or(command.eventType(),event.type()),
                    or(command.date(),event.date()),command.amount()==null?event.amountCents():cents(command.amount()),or(command.notes(),event.notes()),command.version())));
            }
            case "analytics" -> analysis(user,command);
            case "overview" -> service.overview(user);
            case "simulate" -> service.simulate(user, new SimulationInput(cents(command.initialAmount()), cents(command.monthlyAmount()),
                    command.months() == null ? 12 : command.months(), command.cdiAnnualPercent(), command.percentCdi()));
            default -> throw new FinanceException(404, "Operação não encontrada.");
        };
    }

    private static <T> T or(T value,T fallback){return value==null?fallback:value;}
    private static long version(Command command){if(command.version()==null||command.version()<0)throw new FinanceException(400,"Consulte a versão atual da aplicação.");return command.version();}
    private String creationKey(String user,String session,PortfolioData.Input input){
        var jobs=jdbc.queryForList("""
            select j.id from chat_jobs j join chat_conversations c on c.id=j.conversation_id where j.user_id=? and c.hermes_session_id=?
            and (j.status='RUNNING' or (j.status='NEEDS_REVIEW' and j.review_released_at is null)) order by j.sequence desc limit 1
            """,UUID.class,user,session);
        return jobs.isEmpty()?null:"agent:"+jobs.getFirst()+":"+UUID.nameUUIDFromBytes(input.toString().getBytes(StandardCharsets.UTF_8));
    }
    private Object analysis(String user,Command command){
        var data=portfolio.analytics(user,command.year()==null?0:command.year());
        var holdings=data.holdings().stream().filter(h->command.assetType()==null||h.assetType()==command.assetType())
            .filter(h->command.institution()==null||h.institution().equalsIgnoreCase(command.institution()))
            .filter(h->command.id()==null||h.id().equals(command.id().toString())).toList();
        var result=new java.util.LinkedHashMap<String,Object>();
        result.put("asOf",data.asOf());result.put("year",data.year());result.put("cdiDate",data.cdiDate());result.put("warnings",data.warnings());
        result.put("day",sum(holdings.stream().map(h->h.performance().day()).toList()));
        result.put("month",sum(holdings.stream().map(h->h.performance().month()).toList()));
        result.put("yearResult",sum(holdings.stream().map(h->h.performance().year()).toList()));
        result.put("positions",holdings.stream().map(h->{var item=new java.util.LinkedHashMap<String,Object>();item.put("id",h.id());item.put("name",h.name());
            item.put("institution",h.institution());item.put("assetType",h.assetType());item.put("day",h.performance().day());item.put("month",h.performance().month());
            item.put("year",h.performance().year());return item;}).toList());
        return result;
    }
    private static Object sum(java.util.List<InvestmentAnalytics.Period> values){
        var periods=values.stream().filter(java.util.Objects::nonNull).toList();
        boolean complete=!periods.isEmpty()&&periods.stream().allMatch(p->p.complete()&&p.gainCents()!=null);
        var result=new java.util.LinkedHashMap<String,Object>();result.put("complete",complete);result.put("count",periods.size());
        result.put("gainCents",complete?periods.stream().mapToLong(p->p.gainCents()).sum():null);
        result.put("observedGainCents",periods.stream().mapToLong(InvestmentAnalytics.Period::observedGainCents).sum());
        result.put("partial",periods.stream().anyMatch(InvestmentAnalytics.Period::partial));return result;
    }

    private static UUID required(UUID id){if(id==null)throw new FinanceException(400,"Consulte a carteira para selecionar a aplicação.");return id;}
    private static Map<String,Object> agentView(PortfolioData.View view){
        return Map.of("position",view.position(),"currentRate",view.currentRate(),"balanceCents",view.balanceCents(),
            "netInvestedCents",view.netInvestedCents(),"gainCents",view.gainCents(),"incomeCents",view.incomeCents(),
            "monthGainCents",view.monthGainCents(),"estimated",view.estimated(),"warnings",view.warnings(),
            "recentEvents",view.events().subList(Math.max(0,view.events().size()-10),view.events().size()));
    }

    private static Long cents(String reais) {
        if (reais == null || reais.isBlank()) return null;
        try {
            var value = new BigDecimal(reais.strip().replace(',', '.'));
            if (value.signum() < 0 || value.scale() > 2 && value.stripTrailingZeros().scale() > 2) throw new NumberFormatException();
            return value.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new FinanceException(400, "Valor em reais inválido. Use, por exemplo, 1500.50.");
        }
    }
}
