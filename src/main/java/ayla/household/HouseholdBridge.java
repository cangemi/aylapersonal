package ayla.household;

import static ayla.household.HouseholdData.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Hidden;
import tools.jackson.databind.json.JsonMapper;

@Hidden
@RestController
@RequestMapping("/internal/hermes/household")
public class HouseholdBridge {
    public enum Target { PANTRY, SHOPPING }
    public enum Operation { PANTRY_SAVE, PANTRY_ADJUST, PANTRY_DELETE, SHOPPING_SAVE, SHOPPING_PURCHASE, SHOPPING_DELETE, GENERATE }
    public record Read(@NotNull Target target,@Size(max=160) String query,Status status,Boolean all,
                       Boolean lowStock,LocalDate expiringBefore,@Min(0) Integer page,@Min(1) @Max(100) Integer size) {}
    public record Action(@NotNull Operation operation,UUID id,@Min(0) Long version,@Valid ItemInput item,
                         Adjustment adjustment,@DecimalMin("0.001") @DecimalMax("999999999") @Digits(integer=9,fraction=3) BigDecimal quantity,
                         Unit unit,LocalDate expiresOn,Boolean addToPantry) {}
    public record Apply(@NotNull @Size(min=1,max=20) List<@NotNull @Valid Action> actions) {}
    private ayla.chat.session.ConversationContext context;
    @org.springframework.beans.factory.annotation.Autowired
    public void setContext(ayla.chat.session.ConversationContext context){this.context=context;}
    private ShoppingPriceService prices;
    @org.springframework.beans.factory.annotation.Autowired
    public void setPrices(ShoppingPriceService prices){this.prices=prices;}
    private final HouseholdService service;
    private final JdbcTemplate jdbc;
    private final byte[] key;
    private final JsonMapper json=JsonMapper.builder().build();
    public HouseholdBridge(HouseholdService service,JdbcTemplate jdbc,@Value("${ayla.finance.bridge-key:}") String key) {this.service=service;this.jdbc=jdbc;this.key=key.getBytes(StandardCharsets.UTF_8);}
    private String owner(String auth,String session) {
        if(key.length<32 || !auth.startsWith("Bearer ") || !MessageDigest.isEqual(key,auth.substring(7).getBytes(StandardCharsets.UTF_8))) throw new HouseholdException(401,"Integração não autorizada.");
        var owners=jdbc.queryForList("select user_id from chat_conversations where hermes_session_id=?",String.class,session);
        if(owners.size()!=1) throw new HouseholdException(403,"Abra a conversa pela API Java para acessar a despensa.");
        ayla.monitoring.MonitoringContext.user(owners.getFirst());
        return owners.getFirst();
    }
    @PostMapping("/list")
    public Object list(@RequestHeader(value="Authorization",defaultValue="") String auth,@RequestHeader(value="X-Ayla-Session",defaultValue="") String session,@Valid @RequestBody Read input) {
        String user=owner(auth,session);int page=input.page()==null?0:input.page(),size=input.size()==null?50:input.size();
        if(input.target()==Target.PANTRY)return service.pantryList(user,input.query(),Boolean.TRUE.equals(input.lowStock()),input.expiringBefore(),page,size);
        var result=service.shoppingList(user,input.query(),Boolean.TRUE.equals(input.all())?null:input.status()==null?Status.PENDING:input.status(),page,size);
        return Map.of("items",result.items(),"total",result.total(),"page",result.page(),"size",result.size(),"frontendUrl",context.shoppingListUrl());
    }
    @PostMapping("/apply")
    @Transactional
    public Object apply(@RequestHeader(value="Authorization",defaultValue="") String auth,@RequestHeader(value="X-Ayla-Session",defaultValue="") String session,@Valid @RequestBody Apply input) {
        String user=owner(auth,session);
        var turns=jdbc.queryForList("select t.id from finance_agent_turns t join chat_conversations c on c.id=t.conversation_id where t.user_id=? and c.hermes_session_id=? order by t.sequence desc limit 1",UUID.class,user,session);
        if(turns.isEmpty()) throw new HouseholdException(409,"Envie o pedido pela conversa antes de alterar os dados.");
        List<Object> results=new ArrayList<>();
        for(var action:input.actions()) {
            String receipt="agent:"+turns.getFirst()+":"+HouseholdService.hash(json.writeValueAsString(action));
            results.add(switch(action.operation()) {
                case PANTRY_SAVE -> service.savePantry(user,action.id(),item(action),receipt);
                case SHOPPING_SAVE -> service.saveShopping(user,action.id(),item(action),receipt);
                case PANTRY_ADJUST -> service.adjust(user,id(action),new Adjust(action.version(),action.adjustment(),action.quantity(),action.unit(),action.expiresOn()),receipt);
                case PANTRY_DELETE -> service.delete(user,true,id(action),version(action),receipt);
                case SHOPPING_DELETE -> service.delete(user,false,id(action),version(action),receipt);
                case SHOPPING_PURCHASE -> service.purchase(user,id(action),new Purchase(action.version(),action.addToPantry(),action.quantity(),action.unit(),action.expiresOn()),receipt);
                case GENERATE -> service.generate(user,receipt);
            });
        }
        return Map.of("results",results);
    }
    public record PriceQuery(@Size(max=160) String name,Unit unit,LocalDate from,LocalDate to,@Min(0) Integer page,@Min(1) @Max(200) Integer size) {}
    @PostMapping("/price_history") public Object priceHistory(@RequestHeader(value="Authorization",defaultValue="") String auth,@RequestHeader(value="X-Ayla-Session",defaultValue="") String session,@Valid @RequestBody PriceQuery input){return prices.history(owner(auth,session),input.name(),input.unit(),input.from(),input.to(),input.page()==null?0:input.page(),input.size()==null?100:input.size());}
    @PostMapping("/price_products") public Object priceProducts(@RequestHeader(value="Authorization",defaultValue="") String auth,@RequestHeader(value="X-Ayla-Session",defaultValue="") String session,@Valid @RequestBody PriceQuery input){return prices.products(owner(auth,session),input.name(),input.page()==null?0:input.page(),input.size()==null?50:input.size());}
    @PostMapping("/price_compare") public Object priceCompare(@RequestHeader(value="Authorization",defaultValue="") String auth,@RequestHeader(value="X-Ayla-Session",defaultValue="") String session,@Valid @RequestBody ShoppingPriceService.CompareInput input){return prices.compare(owner(auth,session),input);}
    @PostMapping("/cart_prices") public Object cartPrices(@RequestHeader(value="Authorization",defaultValue="") String auth,@RequestHeader(value="X-Ayla-Session",defaultValue="") String session,@RequestBody Map<String,Object> input){return prices.cartComparison(owner(auth,session));}
    public record Purchases(UUID id,@Min(0) Integer page,@Min(1) @Max(100) Integer size) {}
    @PostMapping("/purchases")
    public Object purchases(@RequestHeader(value="Authorization",defaultValue="") String auth,@RequestHeader(value="X-Ayla-Session",defaultValue="") String session,@Valid @RequestBody Purchases input){
        String user=owner(auth,session);return input.id()==null?service.checkouts(user,input.page()==null?0:input.page(),input.size()==null?20:input.size()):service.checkoutDetails(user,input.id());
    }
    @PostMapping("/cart")
    public Object cart(@RequestHeader(value="Authorization",defaultValue="") String auth,@RequestHeader(value="X-Ayla-Session",defaultValue="") String session,@RequestBody Map<String,Object> input){return service.cart(owner(auth,session));}
    @PostMapping("/checkout") @Transactional
    public Object checkout(@RequestHeader(value="Authorization",defaultValue="") String auth,@RequestHeader(value="X-Ayla-Session",defaultValue="") String session,@Valid @RequestBody Checkout input){
        String user=owner(auth,session);
        var turns=jdbc.queryForList("select t.id from finance_agent_turns t join chat_conversations c on c.id=t.conversation_id where t.user_id=? and c.hermes_session_id=? order by t.sequence desc limit 1",UUID.class,user,session);
        if(turns.isEmpty())throw new HouseholdException(409,"Faça o pedido na conversa antes de finalizar.");
        UUID turn=turns.getFirst();var result=service.checkout(user,input,"agent-checkout:"+turn,turn);
        jdbc.update("update finance_agent_turns set completed=true where id=? and user_id=?",turn,user);
        return result;
    }
    private static UUID id(Action action) {if(action.id()==null)throw new HouseholdException(400,"Consulte o item antes de alterar.");return action.id();}
    private static long version(Action action) {if(action.version()==null)throw new HouseholdException(400,"Consulte a versão atual do item.");return action.version();}
    private static ItemInput item(Action action) {
        var item=action.item();if(item==null)throw new HouseholdException(400,"Informe o produto e os campos a alterar.");
        return new ItemInput(item.name(),item.quantity(),item.unit(),item.minimumQuantity(),item.expiresOn(),item.category(),item.notes(),action.version(),item.clearExpiry(),item.unitPriceCents(),item.totalPriceCents(),item.inCart(),item.clearPrice());
    }
}
