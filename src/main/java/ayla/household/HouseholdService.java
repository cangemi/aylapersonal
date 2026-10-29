package ayla.household;

import static ayla.household.HouseholdData.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ayla.chat.session.*;
import tools.jackson.databind.json.JsonMapper;

@Service
@Transactional
public class HouseholdService {
    private final JdbcTemplate jdbc;
    private final ChatUserRepository users;
    private final ChatIdentity identity;
    private ayla.finance.FinanceService finance;
    @org.springframework.beans.factory.annotation.Autowired
    public void setFinance(ayla.finance.FinanceService finance){this.finance=finance;}
    private final JsonMapper json=JsonMapper.builder().build();
    public HouseholdService(JdbcTemplate jdbc,ChatUserRepository users,ChatIdentity identity) {this.jdbc=jdbc;this.users=users;this.identity=identity;}
    private static final RowMapper<PantryItem> PANTRY=(r,n)->{
        var qty=r.getBigDecimal("quantity"); var min=r.getBigDecimal("minimum_quantity");
        var expiry=r.getObject("expires_on",LocalDate.class);
        return new PantryItem(r.getObject("id",UUID.class),r.getString("name"),qty,Unit.valueOf(r.getString("unit")),min,expiry,
                qty.signum()==0 || qty.compareTo(min)<0,expiry!=null && expiry.isBefore(LocalDate.now(ZoneId.of("America/Sao_Paulo"))),
                r.getString("category"),r.getString("notes"),r.getLong("version"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant());
    };
    private static final RowMapper<ShoppingItem> SHOPPING=(r,n)->new ShoppingItem(r.getObject("id",UUID.class),r.getString("name"),
            r.getBigDecimal("quantity"),r.getString("unit")==null?null:Unit.valueOf(r.getString("unit")),r.getString("category"),r.getString("notes"),
            Status.valueOf(r.getString("status")),r.getBoolean("stock_applied"),r.getTimestamp("purchased_at")==null?null:r.getTimestamp("purchased_at").toInstant(),
            r.getLong("version"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant(),
            r.getObject("unit_price_cents",Long.class),r.getObject("total_price_cents",Long.class),r.getObject("checkout_id",UUID.class));

    public PantryItem pantry(String user,UUID id) {return jdbc.query("select * from pantry_items where user_id=? and id=?",PANTRY,user,id).stream().findFirst().orElseThrow(()->error(404,"Item da despensa não encontrado."));}
    public ShoppingItem shopping(String user,UUID id) {return jdbc.query("select * from shopping_items where user_id=? and id=?",SHOPPING,user,id).stream().findFirst().orElseThrow(()->error(404,"Item da lista não encontrado."));}

    public Page<PantryItem> pantryList(String user,String query,boolean lowStock,LocalDate expiringBefore,int page,int size) {
        pagination(page,size);
        String where=" where user_id=? and name_key like ?"+(lowStock?" and (quantity=0 or quantity<minimum_quantity)":"")+(expiringBefore==null?"":" and expires_on<=?");
        List<Object> args=new ArrayList<>(List.of(user,pattern(query)));
        if(expiringBefore!=null) args.add(expiringBefore);
        long total=jdbc.queryForObject("select count(*) from pantry_items"+where,Long.class,args.toArray());
        args.add(size);args.add((long)page*size);
        return new Page<>(jdbc.query("select * from pantry_items"+where+" order by name_key,id limit ? offset ?",PANTRY,args.toArray()),total,page,size);
    }
    public Page<ShoppingItem> shoppingList(String user,String query,Status status,int page,int size) {
        return shoppingList(user,query,status,page,size,"NORMAL");
    }
    public Page<ShoppingItem> shoppingList(String user,String query,Status status,int page,int size,String order) {
        pagination(page,size);
        if(!Set.of("NORMAL","CART_LAST").contains(order))throw error(400,"Ordenação inválida.");
        String where=" where user_id=? and name_key like ?"+(status==null?"":status==Status.PENDING?" and status in ('PENDING','IN_CART')":" and status=?");
        List<Object> args=new ArrayList<>(List.of(user,pattern(query)));
        if(status!=null&&status!=Status.PENDING) args.add(status.name());
        long total=jdbc.queryForObject("select count(*) from shopping_items"+where,Long.class,args.toArray());
        args.add(size);args.add((long)page*size);
        return new Page<>(jdbc.query("select * from shopping_items"+where+" order by "+("CART_LAST".equals(order)?"case when status='IN_CART' then 1 else 0 end,":"")+"name_key,id limit ? offset ?",SHOPPING,args.toArray()),total,page,size);
    }
    public PantryItem savePantry(String user,UUID id,ItemInput input,String key) {
        if(input.unitPriceCents()!=null||input.totalPriceCents()!=null||input.inCart()!=null||Boolean.TRUE.equals(input.clearPrice()))throw error(400,"Preço e carrinho pertencem à lista de compras.");
        return once(user,key,List.of("pantry_save",id==null?"new":id,input),PantryItem.class,()->{
            PantryItem old=id==null?null:pantry(user,id);
            if(old!=null) version(old.version(),input.version());
            String name=name(input.name()==null&&old!=null?old.name():input.name());
            Unit unit=input.unit()==null&&old!=null?old.unit():input.unit();
            if(unit==null) throw error(400,"Qual é a unidade desse item?");
            BigDecimal qty=quantity(input.quantity()==null&&old!=null?convert(old.quantity(),old.unit(),unit):input.quantity(),false);
            BigDecimal minimum=quantity(input.minimumQuantity()==null?(old==null?BigDecimal.ZERO:convert(old.minimumQuantity(),old.unit(),unit)):input.minimumQuantity(),false);
            if(Boolean.TRUE.equals(input.clearExpiry())&&input.expiresOn()!=null) throw error(400,"Escolha informar ou limpar a validade.");
            LocalDate expires=Boolean.TRUE.equals(input.clearExpiry())?null:input.expiresOn()==null&&old!=null?old.expiresOn():input.expiresOn();
            String category=text(input.category(),old==null?"":old.category(),80),notes=text(input.notes(),old==null?"":old.notes(),500);
            var duplicate=pantryByName(user,name);
            if(duplicate!=null && (id==null || !duplicate.id().equals(id))) throw error(409,"Esse produto já está na despensa. Consulte-o para somar ou ajustar a quantidade.");
            UUID target=id==null?UUID.randomUUID():id;
            if(old==null) jdbc.update("insert into pantry_items(id,user_id,name,name_key,quantity,unit,minimum_quantity,expires_on,category,notes) values (?,?,?,?,?,?,?,?,?,?)",target,user,name,normalize(name),qty,unit.name(),minimum,expires,category,notes);
            else jdbc.update("update pantry_items set name=?,name_key=?,quantity=?,unit=?,minimum_quantity=?,expires_on=?,category=?,notes=?,version=version+1,updated_at=now() where user_id=? and id=?",name,normalize(name),qty,unit.name(),minimum,expires,category,notes,user,id);
            return pantry(user,target);
        });
    }
    public PantryItem adjust(String user,UUID id,Adjust input,String key) {
        return once(user,key,List.of("pantry_adjust",id,input),PantryItem.class,()->{
            var old=pantry(user,id);version(old.version(),input.version());
            if(input.action()==null || input.unit()==null) throw error(400,"Informe entrada ou consumo e a unidade.");
            BigDecimal delta=convert(quantity(input.quantity(),true),input.unit(),old.unit());
            BigDecimal next=input.action()==Adjustment.ADD?old.quantity().add(delta):old.quantity().subtract(delta);
            if(next.signum()<0) throw error(409,"A quantidade informada é maior que o estoque disponível.");
            LocalDate expiry=input.action()==Adjustment.ADD?(old.quantity().signum()==0?input.expiresOn():earliest(old.expiresOn(),input.expiresOn())):old.expiresOn();
            jdbc.update("update pantry_items set quantity=?,expires_on=?,version=version+1,updated_at=now() where user_id=? and id=?",quantity(next,false),expiry,user,id);
            return pantry(user,id);
        });
    }
    /** Safety net for a product price sent to the financial tool by mistake. */
    public ShoppingItem routeShoppingPrice(String user,String description,long cents,UUID turn) {
        users.lockUser(user).orElseThrow();
        if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from shopping_checkouts where user_id=? and agent_turn_id=?)",Boolean.class,user,turn)))return null;
        String product=normalize(description).replaceAll("^(?:compra|gasto|despesa|pagamento)(?: de| com| do| da)?\\s+", "");
        var matches=jdbc.query("select * from shopping_items where user_id=? and status in ('PENDING','IN_CART') and name_key=?",SHOPPING,user,product);
        if(matches.isEmpty()) {
            matches=jdbc.query("select * from shopping_items where user_id=? and status in ('PENDING','IN_CART')",SHOPPING,user).stream()
                .filter(item->(" "+normalize(item.name())+" ").contains(" "+product+" ") || (" "+product+" ").contains(" "+normalize(item.name())+" ")).toList();
        }
        if(matches.size()>1)throw error(409,"Há mais de um produto correspondente na lista ativa. Pergunte qual produto antes de salvar o preço; não registre gasto avulso.");
        if(matches.isEmpty())return null;
        var item=matches.getFirst();
        String key="agent:"+turn+":shopping-price:"+item.id()+":"+cents;
        var receipt=jdbc.queryForList("select result_json from household_receipts where user_id=? and request_key=?",String.class,user,key);
        if(!receipt.isEmpty())return json.readValue(receipt.getFirst(),ShoppingItem.class);
        return saveShopping(user,item.id(),new ItemInput(null,null,null,null,null,null,null,item.version(),false,null,cents,true,false),key);
    }
    public ShoppingItem saveShopping(String user,UUID id,ItemInput input,String key) {
        return once(user,key,List.of("shopping_save",id==null?"new":id,input),ShoppingItem.class,()->{
            if(input.minimumQuantity()!=null || input.expiresOn()!=null || Boolean.TRUE.equals(input.clearExpiry()))
                throw error(400,"Estoque mínimo e validade pertencem à despensa, não à lista de compras.");
            ShoppingItem old=id==null?null:shopping(user,id);
            if(old!=null) {version(old.version(),input.version());if(old.status()==Status.PURCHASED||old.status()==Status.ARCHIVED) throw error(409,"Essa compra já foi concluída. Adicione um novo item para comprar novamente.");}
            String name=name(input.name()==null&&old!=null?old.name():input.name());
            BigDecimal qty=input.quantity()==null&&old!=null?old.quantity():input.quantity();
            Unit unit=input.unit()==null?(old!=null?old.unit():qty==null?null:Unit.UN):input.unit();
            if(old!=null && input.quantity()==null && qty!=null && unit!=old.unit()) qty=convert(qty,old.unit(),unit);
            if(qty==null&&(unit==null||unit==Unit.UN)&&(input.unitPriceCents()!=null||input.totalPriceCents()!=null)){qty=BigDecimal.ONE;unit=Unit.UN;}
            if(qty!=null) qty=quantity(qty,true);
            if((qty==null)!=(unit==null)) throw error(400,"Informe quantidade e unidade juntas, ou deixe as duas sem informar.");
            String category=text(input.category(),old==null?"":old.category(),80),notes=text(input.notes(),old==null?"":old.notes(),500);
            var duplicate=pendingByName(user,name);
            if(duplicate!=null && (id==null || !duplicate.id().equals(id))) throw error(409,"Esse produto já está na lista. Consulte-o para ajustar a quantidade sem duplicar.");
            UUID target=id==null?UUID.randomUUID():id;
            if(old==null) jdbc.update("insert into shopping_items(id,user_id,name,name_key,quantity,unit,category,notes) values (?,?,?,?,?,?,?,?)",target,user,name,normalize(name),qty,unit==null?null:unit.name(),category,notes);
            else jdbc.update("update shopping_items set name=?,name_key=?,quantity=?,unit=?,category=?,notes=?,version=version+1,updated_at=now() where user_id=? and id=?",name,normalize(name),qty,unit==null?null:unit.name(),category,notes,user,id);
            boolean hasPrice=input.unitPriceCents()!=null||input.totalPriceCents()!=null;
            if(Boolean.TRUE.equals(input.clearPrice())&&hasPrice)throw error(400,"Informe o preço ou remova o preço, não os dois.");
            Long unitPrice=old==null?null:old.unitPriceCents(),totalPrice=old==null?null:old.totalPriceCents();
            if(Boolean.TRUE.equals(input.clearPrice())){unitPrice=null;totalPrice=null;}
            if(hasPrice){
                if(input.unitPriceCents()!=null&&input.totalPriceCents()!=null)throw error(400,"O preço é por unidade ou pelo total desse produto? Informe apenas um.");
                unitPrice=input.unitPriceCents();totalPrice=input.totalPriceCents();
            }
            if(old!=null&&old.unitPriceCents()!=null&&unit!=old.unit()&&!hasPrice&&!Boolean.TRUE.equals(input.clearPrice()))throw error(400,"Informe o preço na nova unidade ou retire o preço antes de mudar a unidade.");
            if(unitPrice!=null){
                price(unitPrice);if(qty==null)throw error(400,"Qual é a quantidade de "+name+" para calcular o preço por unidade?");
                totalPrice=price(BigDecimal.valueOf(unitPrice).multiply(qty));
            }
            if(totalPrice!=null)price(totalPrice);
            Status status=Boolean.FALSE.equals(input.inCart())?Status.PENDING:Boolean.TRUE.equals(input.inCart())||hasPrice?Status.IN_CART:old==null?Status.PENDING:old.status();
            jdbc.update("update shopping_items set unit_price_cents=?,total_price_cents=?,status=? where user_id=? and id=?",unitPrice,totalPrice,status.name(),user,target);
            return shopping(user,target);
        });
    }
    private static long price(long cents){if(cents<0||cents>ayla.finance.FinanceService.MAX_CENTS)throw error(400,"Preço fora do limite permitido.");return cents;}
    private static long price(BigDecimal cents){try{return price(cents.setScale(0,RoundingMode.HALF_UP).longValueExact());}catch(ArithmeticException invalid){throw error(400,"O total desse produto ultrapassa o limite permitido.");}}
    public CartSummary cart(String user){
        var active=jdbc.query("select * from shopping_items where user_id=? and status in ('PENDING','IN_CART') order by name_key,id",SHOPPING,user);
        var cart=active.stream().filter(item->item.status()==Status.IN_CART).toList();long total=0;int missingPrices=0,missingQuantities=0;
        for(var item:cart){
            if(item.totalPriceCents()==null)missingPrices++;
            else {if(item.totalPriceCents()>ayla.finance.FinanceService.MAX_CENTS-total)throw error(400,"O total do carrinho ultrapassa o limite permitido.");total+=item.totalPriceCents();}
            if(item.quantity()==null||item.unit()==null)missingQuantities++;
        }
        return new CartSummary(total,cart.size(),active.size()-cart.size(),missingPrices,missingQuantities,cart);
    }
    private static final RowMapper<CheckoutSummary> CHECKOUT=(r,n)->new CheckoutSummary(r.getObject("id",UUID.class),r.getLong("total_cents"),r.getTimestamp("paid_at").toInstant(),r.getObject("financial_entry_id",UUID.class),r.getInt("item_count"),r.getInt("purchased_count"),true);
    private static final String CHECKOUT_SELECT="select c.*,(select count(*) from shopping_purchase_items p where p.checkout_id=c.id) as item_count,(select count(*) from shopping_purchase_items p where p.checkout_id=c.id and p.purchased) as purchased_count from shopping_checkouts c";
    public Page<CheckoutSummary> checkouts(String user,int page,int size){
        pagination(page,size);long total=jdbc.queryForObject("select count(*) from shopping_checkouts where user_id=?",Long.class,user);
        return new Page<>(jdbc.query(CHECKOUT_SELECT+" where c.user_id=? order by c.paid_at desc,c.id limit ? offset ?",CHECKOUT,user,size,(long)page*size),total,page,size);
    }
    public Object checkoutDetails(String user,UUID id){
        var headers=jdbc.query(CHECKOUT_SELECT+" where c.user_id=? and c.id=?",CHECKOUT,user,id);
        if(headers.isEmpty())throw error(404,"Compra não encontrada.");
        return Map.of("purchase",headers.getFirst(),"items",jdbc.queryForList("select p.id,p.name,p.quantity,p.unit,p.unit_price_cents as \"unitPriceCents\",p.total_price_cents as \"totalPriceCents\",p.purchased from shopping_purchase_items p join shopping_checkouts c on c.id=p.checkout_id where c.user_id=? and c.id=? order by p.name_key,p.id",user,id));
    }
    public CheckoutResult checkout(String user,Checkout input,String key,UUID turn){
        return once(user,key,List.of("checkout",input),CheckoutResult.class,()->{
            if(input.items()==null||input.items().isEmpty()||input.items().size()>200)throw error(400,"Consulte os itens do carrinho antes de finalizar.");
            var cart=cart(user);var expected=new HashMap<UUID,Long>();
            for(var target:input.items()){if(expected.put(target.id(),target.version())!=null)throw error(400,"Há um produto repetido no pedido de pagamento.");}
            if(cart.cartCount()==0)throw error(409,"Não há itens no carrinho para finalizar. Consulte o histórico antes de repetir uma compra.");
            if(expected.size()!=cart.cartCount()||cart.cartItems().stream().anyMatch(item->!expected.containsKey(item.id())||expected.get(item.id())!=item.version()))throw error(409,"O carrinho mudou. Consulte os produtos e o total antes de finalizar.");
            var missing=cart.cartItems().stream().filter(item->item.totalPriceCents()==null||item.quantity()==null||item.unit()==null).map(item->item.name()+" ("+(item.totalPriceCents()==null?"preço; ":"")+(item.quantity()==null?"quantidade; ":"")+(item.unit()==null?"unidade":"")+")").toList();
            if(!missing.isEmpty())throw error(400,"Faltam preço, quantidade ou unidade para: "+String.join(", ",missing)+". Complete só esses dados antes de finalizar.");
            UUID checkoutId=UUID.randomUUID();
            var expense=cart.cartTotalCents()==0?null:finance.create(user,new ayla.finance.FinanceData.Input(ayla.finance.FinanceData.Type.EXPENSE,cart.cartTotalCents(),"Compra mercado","Supermercado",input.occurredAt()),"shopping-checkout:"+checkoutId);
            var paidAt=input.occurredAt()==null?OffsetDateTime.now(ZoneId.of("America/Sao_Paulo")):input.occurredAt();
            jdbc.update("insert into shopping_checkouts(id,user_id,total_cents,financial_entry_id,agent_turn_id,paid_at) values (?,?,?,?,?,?)",checkoutId,user,cart.cartTotalCents(),expense==null?null:expense.id(),turn,java.sql.Timestamp.from(paidAt.toInstant()));
            jdbc.update("insert into shopping_purchase_items(id,checkout_id,source_item_id,name,name_key,quantity,unit,unit_price_cents,total_price_cents,purchased) select gen_random_uuid(),?,id,name,name_key,quantity,unit,unit_price_cents,total_price_cents,status='IN_CART' from shopping_items where user_id=? and status in ('PENDING','IN_CART')",checkoutId,user);
            jdbc.update("update shopping_items set status='ARCHIVED',checkout_id=?,version=version+1,updated_at=now() where user_id=? and status='PENDING'",checkoutId,user);
            var purchased=new ArrayList<ShoppingItem>();
            for(var item:cart.cartItems()){
                purchase(user,item.id(),new Purchase(item.version(),true,null,null,null),null);
                jdbc.update("update shopping_items set checkout_id=?,purchased_at=? where user_id=? and id=?",checkoutId,java.sql.Timestamp.from(paidAt.toInstant()),user,item.id());
                purchased.add(shopping(user,item.id()));
            }
            return new CheckoutResult(checkoutId,cart.cartTotalCents(),expense,purchased);
        });
    }
    public PurchaseResult purchase(String user,UUID id,Purchase input,String key) {
        return once(user,key,List.of("purchase",id,input),PurchaseResult.class,()->{
            var old=shopping(user,id);
            if(old.status()==Status.ARCHIVED)throw error(409,"Essa lista já foi arquivada. Adicione um novo item à lista atual.");
            if(old.status()==Status.PURCHASED) return new PurchaseResult(old,null); // Never add stock twice.
            version(old.version(),input.version());
            BigDecimal qty=input.quantity()==null?old.quantity():quantity(input.quantity(),true);
            Unit unit=input.unit()==null?old.unit():input.unit();
            if(input.quantity()==null && qty!=null && unit!=old.unit()) qty=convert(qty,old.unit(),unit);
            if((qty==null)!=(unit==null)) throw error(400,"Informe quantidade e unidade da compra.");
            if(old.unitPriceCents()!=null&&unit!=old.unit())throw error(400,"Atualize o preço na unidade comprada antes de concluir.");
            PantryItem stock=null;
            boolean apply=Boolean.TRUE.equals(input.addToPantry());
            if(apply) {
                if(qty==null) throw error(400,"Quanto foi comprado para adicionar à despensa?");
                var existing=pantryByName(user,old.name());
                if(existing==null) stock=savePantry(user,null,new ItemInput(old.name(),qty,unit,null,input.expiresOn(),old.category(),old.notes(),null,false),null);
                else stock=adjust(user,existing.id(),new Adjust(existing.version(),Adjustment.ADD,qty,unit,input.expiresOn()),null);
            }
            jdbc.update("update shopping_items set quantity=?,unit=?,total_price_cents=?,status='PURCHASED',stock_applied=?,purchased_at=now(),version=version+1,updated_at=now() where user_id=? and id=?",qty,unit==null?null:unit.name(),old.unitPriceCents()==null?old.totalPriceCents():price(BigDecimal.valueOf(old.unitPriceCents()).multiply(qty)),apply,user,id);
            return new PurchaseResult(shopping(user,id),stock);
        });
    }
    public Deleted delete(String user,boolean pantry,UUID id,long expected,String key) {
        return once(user,key,List.of("delete",pantry,id,expected),Deleted.class,()->{
            if(pantry) version(pantry(user,id).version(),expected);else version(shopping(user,id).version(),expected);
            jdbc.update("delete from "+(pantry?"pantry_items":"shopping_items")+" where user_id=? and id=?",user,id);
            return new Deleted(true);
        });
    }
    public Generated generate(String user,String key) {
        return once(user,key,List.of("generate"),Generated.class,()->{
            List<ShoppingItem> result=new ArrayList<>();
            var lows=jdbc.query("select * from pantry_items where user_id=? and (quantity=0 or quantity<minimum_quantity) order by name_key",PANTRY,user);
            for(var item:lows) {
                BigDecimal needed=item.minimumQuantity().subtract(item.quantity());
                BigDecimal qty=needed.signum()>0?needed:null;
                Unit unit=qty==null?null:item.unit();
                var pending=pendingByName(user,item.name());
                if(pending==null) result.add(saveShopping(user,null,new ItemInput(item.name(),qty,unit,null,null,item.category(),"",null,false),null));
                else if(qty!=null && (pending.quantity()==null || convert(pending.quantity(),pending.unit(),unit).compareTo(qty)<0))
                    result.add(saveShopping(user,pending.id(),new ItemInput(null,qty,unit,null,null,null,null,pending.version(),false),null));
                else result.add(pending);
            }
            return new Generated(result);
        });
    }
    private PantryItem pantryByName(String user,String name) {return jdbc.query("select * from pantry_items where user_id=? and name_key=?",PANTRY,user,normalize(name)).stream().findFirst().orElse(null);}
    private ShoppingItem pendingByName(String user,String name) {return jdbc.query("select * from shopping_items where user_id=? and name_key=? and status in ('PENDING','IN_CART')",SHOPPING,user,normalize(name)).stream().findFirst().orElse(null);}
    private <T> T once(String user,String key,Object input,Class<T> type,Supplier<T> mutation) {
        users.ensureUser(user,user.equals(identity.ownerId())?"default":null);users.lockUser(user).orElseThrow();
        if(key!=null && !key.matches("[A-Za-z0-9_.:-]{1,200}")) throw error(400,"Idempotency-Key inválida.");
        String fingerprint=hash(json.writeValueAsString(input));
        if(key!=null) {
            var saved=jdbc.queryForList("select fingerprint,result_json from household_receipts where user_id=? and request_key=?",user,key);
            if(!saved.isEmpty()) {
                if(!fingerprint.equals(saved.getFirst().get("fingerprint"))) throw error(409,"Essa chave já foi usada para outra alteração.");
                return json.readValue((String)saved.getFirst().get("result_json"),type);
            }
        }
        T result=mutation.get();
        if(key!=null) jdbc.update("insert into household_receipts(user_id,request_key,fingerprint,result_json) values (?,?,?,?)",user,key,fingerprint,json.writeValueAsString(result));
        return result;
    }
    public static String hash(String input) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }
    public static BigDecimal convert(BigDecimal qty,Unit from,Unit to) {
        if(from==null || to==null || qty==null) throw error(400,"Informe quantidade e unidade.");
        if(from==to) return quantity(qty,false);
        if(qty.signum()==0) return quantity(qty,false);
        boolean mass=(from==Unit.G||from==Unit.KG)&&(to==Unit.G||to==Unit.KG);
        boolean volume=(from==Unit.ML||from==Unit.L)&&(to==Unit.ML||to==Unit.L);
        if(!mass && !volume) throw error(400,"Essas unidades não são compatíveis. Informe a quantidade na unidade do estoque.");
        return quantity(from==Unit.KG||from==Unit.L?qty.multiply(new BigDecimal("1000")):qty.divide(new BigDecimal("1000")),false);
    }
    private static BigDecimal quantity(BigDecimal qty,boolean positive) {
        if(qty==null || qty.signum()<0 || (positive&&qty.signum()==0) || qty.compareTo(new BigDecimal("999999999"))>0) throw error(400,"Informe uma quantidade válida.");
        try {return qty.setScale(3,RoundingMode.UNNECESSARY);}catch(ArithmeticException e) {throw error(400,"Use até três casas decimais na unidade do estoque, sem arredondar.");}
    }
    private static String name(String name) {if(name==null || name.isBlank() || name.strip().length()>160) throw error(400,"Qual é o produto?");return name.strip();}
    private static String text(String value,String old,int max) {if(value==null)return old; if(value.length()>max)throw error(400,"Texto muito longo.");return value.strip();}
    static String normalize(String text) {return Normalizer.normalize(text,Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT).strip().replaceAll("\\s+"," ");}
    private static String pattern(String text) {return "%"+normalize(text==null?"":text).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";}
    private static void pagination(int page,int size) {if(page<0 || page>1000000 || size<1 || size>100)throw error(400,"Use página a partir de zero e tamanho entre 1 e 100.");}
    private static void version(long current,Long expected) {if(expected==null || expected<0)throw error(400,"Consulte a versão atual do item.");if(current!=expected)throw error(409,"O item mudou. Consulte-o novamente antes de alterar.");}
    private static LocalDate earliest(LocalDate a,LocalDate b) {return a==null?b:b==null?a:a.isBefore(b)?a:b;}
    private static HouseholdException error(int status,String message) {return new HouseholdException(status,message);}
}
