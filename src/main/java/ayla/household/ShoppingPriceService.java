package ayla.household;

import static ayla.household.HouseholdData.*;
import java.math.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional(readOnly=true)
public class ShoppingPriceService {
    private final JdbcTemplate jdbc;private final HouseholdService household;
    public ShoppingPriceService(JdbcTemplate jdbc,HouseholdService household){this.jdbc=jdbc;this.household=household;}
    private static final String BASE_UNIT="case when p.unit='G' then 'KG' when p.unit='ML' then 'L' else p.unit end";
    private static final String REFERENCE="round(p.total_price_cents::numeric/100*(case when p.unit in ('G','ML') then 1000 else 1 end)/nullif(p.quantity,0),12)";
    private static final String SOURCE=" from shopping_purchase_items p join shopping_checkouts c on c.id=p.checkout_id";
    public record Product(String name,Unit unit,long purchases) {}
    public record Point(UUID purchaseId,Instant paidAt,BigDecimal quantity,Unit purchasedUnit,long totalCents,String referencePrice,String referenceLabel) {}
    public record Comparison(boolean hasHistory,Unit unit,String currentLabel,String previousLabel,String differenceLabel,String trend,String changePercent,Instant previousPaidAt) {}
    public record CompareInput(@NotBlank @Size(max=160) String name,@NotNull Unit unit,
            @DecimalMin("0.001") @DecimalMax("999999999") @Digits(integer=9,fraction=3) BigDecimal quantity,
            @Min(0) @Max(9007199254740991L) @tools.jackson.databind.annotation.JsonDeserialize(using=ayla.finance.CentsDeserializer.class) Long unitPriceCents,
            @Min(0) @Max(9007199254740991L) @tools.jackson.databind.annotation.JsonDeserialize(using=ayla.finance.CentsDeserializer.class) Long totalPriceCents) {}
    private static Unit base(Unit unit){return unit==Unit.G?Unit.KG:unit==Unit.ML?Unit.L:unit;}
    private static BigDecimal reference(long cents,BigDecimal quantity,Unit unit){
        if(quantity==null||quantity.signum()<=0||unit==null)throw error("Informe a quantidade e a unidade para comparar o preço.");
        BigDecimal factor=unit==Unit.G||unit==Unit.ML?new BigDecimal("1000"):BigDecimal.ONE;
        return BigDecimal.valueOf(cents).movePointLeft(2).multiply(factor).divide(quantity,12,RoundingMode.HALF_UP);
    }
    private static String label(BigDecimal price){return price==null?null:"R$ "+price.setScale(2,RoundingMode.HALF_UP).toPlainString().replace('.',',');}
    private static String trend(BigDecimal current,BigDecimal previous){int direction=current.compareTo(previous);return direction>0?"INCREASED":direction<0?"DECREASED":"SAME";}
    private static String percentage(BigDecimal current,BigDecimal previous){return previous.signum()==0?null:current.subtract(previous).multiply(new BigDecimal("100")).divide(previous,2,RoundingMode.HALF_UP).toPlainString();}
    private static HouseholdException error(String message){return new HouseholdException(400,message);}
    private static void pagination(int page,int size){if(page<0||page>1000000||size<1||size>200)throw error("Paginação inválida.");}
    public Page<Product> products(String user,String query,int page,int size){
        pagination(page,size);if(query!=null&&query.length()>160)throw error("Use até 160 caracteres na busca.");String text=HouseholdService.normalize(query==null?"":query).replace("\\","\\\\").replace("%","\\%").replace("_","\\_");
        String grouped="select min(p.name) as name,"+BASE_UNIT+" as unit,count(*) as purchases"+SOURCE+" where c.user_id=? and p.purchased and p.name_key like ? group by p.name_key,"+BASE_UNIT;
        long count=jdbc.queryForObject("select count(*) from ("+grouped+") products",Long.class,user,"%"+text+"%");
        var rows=jdbc.query(grouped+" order by name,unit limit ? offset ?",(r,n)->new Product(r.getString("name"),Unit.valueOf(r.getString("unit")),r.getLong("purchases")),user,"%"+text+"%",size,(long)page*size);
        return new Page<>(rows,count,page,size);
    }
    public Map<String,Object> history(String user,String name,Unit unit,LocalDate from,LocalDate to,int page,int size){
        pagination(page,size);if(name==null||name.isBlank()||name.length()>160)throw error("Informe o produto.");
        if(from!=null&&to!=null&&to.isBefore(from))throw error("O fim do período deve ser depois do início.");
        String key=HouseholdService.normalize(name);
        if(unit==null){
            var units=jdbc.queryForList("select distinct "+BASE_UNIT+SOURCE+" where c.user_id=? and p.purchased and p.name_key=?",String.class,user,key);
            if(units.size()>1)throw error("Esse produto tem compras em unidades diferentes. Escolha a unidade para comparar.");
            if(units.size()==1)unit=Unit.valueOf(units.getFirst());
        }
        Unit normalized=unit==null?null:base(unit);
        String where=" where c.user_id=? and p.purchased and p.name_key=?";var args=new ArrayList<Object>(List.of(user,key));
        if(normalized!=null){where+=" and "+BASE_UNIT+"=?";args.add(normalized.name());}
        ZoneId zone=ZoneId.of("America/Sao_Paulo");
        if(from!=null){where+=" and c.paid_at>=?";args.add(Timestamp.from(from.atStartOfDay(zone).toInstant()));}
        if(to!=null){where+=" and c.paid_at<?";args.add(Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant()));}
        var stats=jdbc.queryForMap("select count(*) as count,min("+REFERENCE+") as minimum,max("+REFERENCE+") as maximum,avg("+REFERENCE+") as average"+SOURCE+where,args.toArray());
        long count=((Number)stats.get("count")).longValue();
        var latest=jdbc.queryForList("select "+REFERENCE+" as price,c.paid_at"+SOURCE+where+" order by c.paid_at desc,p.id desc limit 2",args.toArray());
        var pageArgs=new ArrayList<>(args);pageArgs.add(size);pageArgs.add((long)page*size);
        var points=jdbc.query("select p.*,c.paid_at"+SOURCE+where+" order by c.paid_at desc,p.id desc limit ? offset ?",(r,n)->{
            var value=reference(r.getLong("total_price_cents"),r.getBigDecimal("quantity"),Unit.valueOf(r.getString("unit")));
            return new Point(r.getObject("checkout_id",UUID.class),r.getTimestamp("paid_at").toInstant(),r.getBigDecimal("quantity"),Unit.valueOf(r.getString("unit")),r.getLong("total_price_cents"),value.stripTrailingZeros().toPlainString(),label(value));
        },pageArgs.toArray());
        Collections.reverse(points);
        var result=new LinkedHashMap<String,Object>();result.put("name",name);result.put("unit",normalized);result.put("total",count);result.put("page",page);result.put("size",size);result.put("items",points);
        result.put("minimumLabel",label((BigDecimal)stats.get("minimum")));result.put("maximumLabel",label((BigDecimal)stats.get("maximum")));result.put("averageLabel",label((BigDecimal)stats.get("average")));
        if(!latest.isEmpty()){result.put("latestLabel",label((BigDecimal)latest.getFirst().get("price")));result.put("latestPaidAt",((Timestamp)latest.getFirst().get("paid_at")).toInstant());}
        if(latest.size()>1){var current=(BigDecimal)latest.getFirst().get("price");var previous=(BigDecimal)latest.get(1).get("price");result.put("trend",trend(current,previous));result.put("changePercent",percentage(current,previous));result.put("previousLabel",label(previous));}
        else result.put("trend","NO_COMPARISON");
        result.put("note","Somente suas compras pagas. Valores normalizados por unidade comparável; KG/G e L/ML são convertidos. Pacotes não são convertidos em unidades. O gráfico mostra as compras mais recentes da página, em ordem cronológica. Rótulos monetários são arredondados para exibição; cálculos usam decimal exato.");
        return result;
    }
    public Comparison compare(String user,CompareInput input){
        if(input.unit()==null||input.name()==null||input.name().isBlank())throw error("Informe produto e unidade.");
        if((input.unitPriceCents()==null)==(input.totalPriceCents()==null))throw error("Informe o preço unitário ou o total, apenas um.");
        long cents=input.unitPriceCents()!=null?input.unitPriceCents():input.totalPriceCents();
        if(cents<0||cents>ayla.finance.FinanceService.MAX_CENTS)throw error("Preço fora do limite permitido.");
        var current=reference(cents,input.unitPriceCents()!=null?BigDecimal.ONE:input.quantity(),input.unit());
        var rows=jdbc.queryForList("select "+REFERENCE+" as price,c.paid_at"+SOURCE+" where c.user_id=? and p.purchased and p.name_key=? and "+BASE_UNIT+"=? order by c.paid_at desc,p.id desc limit 1",user,HouseholdService.normalize(input.name()),base(input.unit()).name());
        if(rows.isEmpty())return new Comparison(false,base(input.unit()),label(current),null,null,"NO_HISTORY",null,null);
        var previous=(BigDecimal)rows.getFirst().get("price");
        return new Comparison(true,base(input.unit()),label(current),label(previous),label(current.subtract(previous)),trend(current,previous),percentage(current,previous),((Timestamp)rows.getFirst().get("paid_at")).toInstant());
    }
    public Comparison compareItem(String user,UUID id){var item=household.shopping(user,id);return compare(user,new CompareInput(item.name(),item.unit(),item.quantity(),item.unitPriceCents(),item.unitPriceCents()==null?item.totalPriceCents():null));}
    public Object cartComparison(String user){
        var cart=household.cart(user);var items=new ArrayList<Map<String,Object>>();
        for(var item:cart.cartItems().stream().limit(200).toList())if(item.totalPriceCents()!=null&&item.quantity()!=null&&item.unit()!=null)items.add(Map.of("id",item.id(),"name",item.name(),"comparison",compareItem(user,item.id())));
        return Map.of("items",items,"cartCount",cart.cartCount(),"note","Comparação dos primeiros 200 itens com preço e quantidade conhecidos, contra a última compra de cada produto na mesma unidade.");
    }
}
