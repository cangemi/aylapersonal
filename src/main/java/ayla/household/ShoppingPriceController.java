package ayla.household;

import java.security.Principal;
import java.time.LocalDate;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import ayla.chat.session.ChatIdentity;

@RestController @RequestMapping("/api/v1/shopping-list")
@Tag(name="Preços das compras",description="Histórico próprio de listas arquivadas, comparação por unidade e cálculos no servidor.")
public class ShoppingPriceController {
    private final ShoppingPriceService prices;private final ChatIdentity identity;
    public ShoppingPriceController(ShoppingPriceService prices,ChatIdentity identity){this.prices=prices;this.identity=identity;}
    @GetMapping("/prices/products") public Object products(Principal p,@RequestParam(required=false) String q,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size){return prices.products(identity.resolve(p),q,page,size);}
    @GetMapping("/prices/history") public Object history(Principal p,@RequestParam String name,@RequestParam(required=false) HouseholdData.Unit unit,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="100") int size){return prices.history(identity.resolve(p),name,unit,from,to,page,size);}
    @PostMapping("/prices/compare") public Object compare(Principal p,@Valid @RequestBody ShoppingPriceService.CompareInput input){return prices.compare(identity.resolve(p),input);}
    @GetMapping("/prices/cart-comparison") public Object cart(Principal p){return prices.cartComparison(identity.resolve(p));}
    @GetMapping("/items/{id}/price-comparison") public Object item(Principal p,@PathVariable UUID id){return prices.compareItem(identity.resolve(p),id);}
}
