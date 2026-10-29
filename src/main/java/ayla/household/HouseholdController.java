package ayla.household;

import static ayla.household.HouseholdData.*;
import java.security.Principal;
import java.time.LocalDate;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import ayla.chat.session.ChatIdentity;

@RestController
@RequestMapping("/api/v1")
@Tag(name="Despensa e compras",description="Dados do usuário atual no PostgreSQL. Quantidades decimais exatas, unidades UN/KG/G/L/ML/PACOTE. Finalizar carrinho repõe estoque e gera despesa com total calculado no servidor.")
public class HouseholdController {
    private final HouseholdService service;
    private final ChatIdentity identity;
    public HouseholdController(HouseholdService service,ChatIdentity identity) {this.service=service;this.identity=identity;}
    @GetMapping("/pantry/items")
    @Operation(summary="Listar despensa",description="Filtros opcionais por nome, estoque baixo e validade até uma data inclusiva. Estoque baixo é quantidade zero ou abaixo do mínimo. Paginação começa em zero, tamanho máximo 100.")
    public Page<PantryItem> pantry(Principal principal,@RequestParam(required=false) String q,@RequestParam(defaultValue="false") boolean lowStock,
                                  @RequestParam(required=false) LocalDate expiringBefore,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size) {
        return service.pantryList(identity.resolve(principal),q,lowStock,expiringBefore,page,size);
    }
    @GetMapping("/pantry/items/{id}")
    @Operation(summary="Consultar item da despensa")
    public PantryItem pantry(Principal principal,@PathVariable UUID id) {return service.pantry(identity.resolve(principal),id);}
    @PostMapping("/pantry/items")
    @Operation(summary="Cadastrar produto na despensa",description="Informe name, quantity (pode ser zero) e unit. minimumQuantity padrão zero; validade, categoria e notas opcionais. Mesmo nome normalizado retorna 409 para evitar duplicação. Idempotency-Key evita repetir a operação.")
    public ResponseEntity<PantryItem> createPantry(Principal principal,@Valid @RequestBody ItemInput input,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        var item=service.savePantry(identity.resolve(principal),null,input,key);
        return ResponseEntity.created(java.net.URI.create("/api/v1/pantry/items/"+item.id())).body(item);
    }
    @PatchMapping("/pantry/items/{id}")
    @Operation(summary="Editar item da despensa",description="Envie version e somente os campos a mudar. quantity substitui o total, não soma. clearExpiry=true remove validade. Mudanças compatíveis de unidade convertem os valores preservados; não converte pacotes em unidades.")
    public PantryItem editPantry(Principal principal,@PathVariable UUID id,@Valid @RequestBody ItemInput input,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        return service.savePantry(identity.resolve(principal),id,input,key);
    }
    @PostMapping("/pantry/items/{id}/adjustments")
    @Operation(summary="Adicionar ou consumir estoque",description="action=ADD/CONSUME, quantity positiva, unit e version obrigatórios. Converte KG/G e L/ML sem ponto flutuante. Impede estoque negativo. Em entradas, preserva a validade mais próxima conhecida.")
    public PantryItem adjust(Principal principal,@PathVariable UUID id,@Valid @RequestBody Adjust input,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        return service.adjust(identity.resolve(principal),id,input,key);
    }
    @DeleteMapping("/pantry/items/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary="Excluir produto da despensa",description="Exige version atual; uma edição concorrente retorna 409.")
    public void deletePantry(Principal principal,@PathVariable UUID id,@RequestParam long version,@RequestHeader(value="Idempotency-Key",required=false) String key) {service.delete(identity.resolve(principal),true,id,version,key);}
    @GetMapping("/shopping-list/items")
    @Operation(summary="Consultar lista de compras",description="Padrão PENDING inclui pendentes e carrinho; IN_CART mostra só carrinho. status=PURCHASED lista compras concluídas; all=true inclui ambos. Filtro de nome e paginação com tamanho máximo 100.")
    public Page<ShoppingItem> shopping(Principal principal,@RequestParam(required=false) String q,@RequestParam(defaultValue="PENDING") Status status,
                                      @RequestParam(defaultValue="false") boolean all,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size,@RequestParam(defaultValue="NORMAL") String order) {
        return service.shoppingList(identity.resolve(principal),q,all?null:status,page,size,order);
    }
    @GetMapping("/shopping-list/items/{id}")
    @Operation(summary="Consultar item da lista")
    public ShoppingItem shopping(Principal principal,@PathVariable UUID id) {return service.shopping(identity.resolve(principal),id);}
    @PostMapping("/shopping-list/items")
    @Operation(summary="Adicionar produto à lista",description="Só name é obrigatório. quantity e unit podem ficar ausentes; não é preciso perguntar a quantidade para listar um produto. Se quantity for informada sem unit, usa UN. Um nome já pendente retorna 409.")
    public ResponseEntity<ShoppingItem> createShopping(Principal principal,@Valid @RequestBody ItemInput input,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        var item=service.saveShopping(identity.resolve(principal),null,input,key);
        return ResponseEntity.created(java.net.URI.create("/api/v1/shopping-list/items/"+item.id())).body(item);
    }
    @PatchMapping("/shopping-list/items/{id}")
    @Operation(summary="Editar item pendente",description="Envie version e campos a mudar; quantity substitui o total. Itens já comprados permanecem como histórico; para comprar novamente, adicione novo item.")
    public ShoppingItem editShopping(Principal principal,@PathVariable UUID id,@Valid @RequestBody ItemInput input,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        return service.saveShopping(identity.resolve(principal),id,input,key);
    }
    @PostMapping("/shopping-list/items/{id}/purchase")
    @Operation(summary="Marcar como comprado e opcionalmente repor estoque",description="version obrigatória. addToPantry=true soma a quantidade comprada à despensa na mesma transação; usa quantidade/unidade da lista se não forem substituídas. Sem quantidade conhecida, pergunte antes de dar entrada no estoque. Repetir não repõe novamente.")
    public PurchaseResult purchase(Principal principal,@PathVariable UUID id,@Valid @RequestBody Purchase input,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        return service.purchase(identity.resolve(principal),id,input,key);
    }
    @DeleteMapping("/shopping-list/items/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary="Excluir item da lista",description="Exige version atual. Excluir compra concluída não desfaz entrada na despensa; estoque deve ser ajustado explicitamente.")
    public void deleteShopping(Principal principal,@PathVariable UUID id,@RequestParam long version,@RequestHeader(value="Idempotency-Key",required=false) String key) {service.delete(identity.resolve(principal),false,id,version,key);}
    @PostMapping("/shopping-list/from-pantry")
    @Operation(summary="Adicionar faltantes da despensa à lista",description="Usa o déficit em relação ao estoque mínimo, sem duplicar produtos pendentes ou somar a sugestão repetidamente. Produto zerado sem mínimo entra sem quantidade definida. Não considera validade como consumo automático.")
    public Generated generate(Principal principal,@RequestHeader(value="Idempotency-Key",required=false) String key) {return service.generate(identity.resolve(principal),key);}
    @GetMapping("/shopping-list/cart")
    @Operation(summary="Consultar carrinho e total calculado no banco",description="Itens IN_CART, total inteiro em centavos, quantidades e preços ausentes. Não altera estoque nem financeiro.")
    public CartSummary cart(Principal principal){return service.cart(identity.resolve(principal));}
    @PostMapping("/shopping-list/checkout")
    @Operation(summary="Confirmar pagamento do carrinho",description="Envie todos os IDs e versões atuais do carrinho. Uma transação cria a compra, soma estoque e lança Compra mercado no financeiro. Falta de dados ou conflito desfaz tudo. Idempotency-Key protege repetições.")
    public CheckoutResult checkout(Principal principal,@Valid @RequestBody Checkout input,@RequestHeader(value="Idempotency-Key",required=false) String key){return service.checkout(identity.resolve(principal),input,key,null);}

    @GetMapping("/shopping-list/checkouts")
    public Page<CheckoutSummary> checkouts(Principal principal,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return service.checkouts(identity.resolve(principal),page,size);}
    @GetMapping("/shopping-list/checkouts/{id}")
    public Object checkoutDetails(Principal principal,@PathVariable UUID id){return service.checkoutDetails(identity.resolve(principal),id);}

}
