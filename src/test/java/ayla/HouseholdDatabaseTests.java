package ayla;

import static ayla.household.HouseholdData.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import ayla.household.*;
import ayla.chat.session.ConversationService;
import ayla.finance.FinanceAgentLedger;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties={"ayla.whatsapp.api-key=whatsapp-integration-test-key-1234567890","ayla.finance.bridge-key=household-integration-test-key-1234567890"})
class HouseholdDatabaseTests {
    @Autowired HouseholdService service;
    @Autowired ConversationService conversations;
    @Autowired FinanceAgentLedger turns;
    @Autowired WebApplicationContext context;
    static BigDecimal number(String value) {return new BigDecimal(value);}
    static ItemInput item(String name,String qty,Unit unit,String min,Long version) {return new ItemInput(name,qty==null?null:number(qty),unit,min==null?null:number(min),null,null,null,version,false);}

    @Test void exactStockAdjustmentsAreIdempotentAndRespectVersionsUnitsAndOwnership() {
        String user="pantry-"+UUID.randomUUID();
        var rice=service.savePantry(user,null,item("Arroz","2",Unit.KG,"3",null),"create");
        var input=new Adjust(rice.version(),Adjustment.CONSUME,number("500"),Unit.G,null);
        var consumed=service.adjust(user,rice.id(),input,"consume");
        assertThat(consumed.quantity()).isEqualByComparingTo("1.500");
        assertThat(service.adjust(user,rice.id(),input,"consume").quantity()).isEqualByComparingTo("1.500");
        assertThat(service.pantry(user,rice.id()).version()).isEqualTo(1);
        assertThatThrownBy(()->service.adjust(user,rice.id(),input,"another-consume"))
                .isInstanceOfSatisfying(HouseholdException.class,e->assertThat(e.status()).isEqualTo(409));
        assertThatThrownBy(()->service.adjust(user,rice.id(),new Adjust(1L,Adjustment.CONSUME,number("2"),Unit.KG,null),null)).isInstanceOf(HouseholdException.class);
        assertThatThrownBy(()->service.adjust(user,rice.id(),new Adjust(1L,Adjustment.ADD,number("1"),Unit.L,null),null)).isInstanceOf(HouseholdException.class);
        assertThatThrownBy(()->service.pantry("another-user",rice.id())).isInstanceOf(HouseholdException.class);
        assertThatThrownBy(()->service.savePantry(user,null,item(" ARROZ ","1",Unit.KG,null,null),null)).isInstanceOf(HouseholdException.class);
        var grams=service.savePantry(user,rice.id(),item(null,null,Unit.G,null,1L),null);
        assertThat(grams.quantity()).isEqualByComparingTo("1500");
        assertThat(grams.minimumQuantity()).isEqualByComparingTo("3000");
        assertThat(service.pantryList(user,"arroz",true,null,0,50).total()).isEqualTo(1);
        assertThatThrownBy(()->service.delete(user,true,rice.id(),1,null)).isInstanceOf(HouseholdException.class);
        service.delete(user,true,rice.id(),grams.version(),"delete");
        assertThat(service.delete(user,true,rice.id(),grams.version(),"delete").deleted()).isTrue();
    }
    @Test void shortageGenerationAndPurchasesDoNotDuplicateAndUnknownQuantitiesStayUnknown() {
        String user="shopping-"+UUID.randomUUID();
        var stock=service.savePantry(user,null,item("Arroz","0",Unit.KG,"2",null),null);
        service.saveShopping(user,null,item("arroz",null,null,null,null),null);
        var generated=service.generate(user,null).items().getFirst();
        assertThat(generated.quantity()).isEqualByComparingTo("2");
        service.generate(user,null);
        assertThat(service.shoppingList(user,null,Status.PENDING,0,50).total()).isEqualTo(1);
        var request=new Purchase(generated.version(),true,null,null,LocalDate.of(2030,1,1));
        var purchased=service.purchase(user,generated.id(),request,"purchase");
        assertThat(purchased.shoppingItem().stockApplied()).isTrue();
        assertThat(service.pantry(user,stock.id()).quantity()).isEqualByComparingTo("2");
        service.purchase(user,generated.id(),request,"purchase");
        service.purchase(user,generated.id(),request,"repeat-different-key");
        assertThat(service.pantry(user,stock.id()).quantity()).isEqualByComparingTo("2");
        assertThat(service.generate(user,null).items()).isEmpty();
        var milk=service.savePantry(user,null,item("Leite","500",Unit.ML,null,null),null);
        var milkList=service.saveShopping(user,null,item("Leite","2",Unit.L,null,null),null);
        service.purchase(user,milkList.id(),new Purchase(0L,true,null,null,null),null);
        assertThat(service.pantry(user,milk.id()).quantity()).isEqualByComparingTo("2500");
        var unknown=service.saveShopping(user,null,item("Café",null,null,null,null),null);
        assertThat(unknown.quantity()).isNull();
        assertThatThrownBy(()->service.purchase(user,unknown.id(),new Purchase(0L,true,null,null,null),null)).isInstanceOf(HouseholdException.class);
        assertThat(service.shopping(user,unknown.id()).status()).isEqualTo(Status.PENDING);
        var checked=service.purchase(user,unknown.id(),new Purchase(0L,false,null,null,null),null);
        assertThat(checked.shoppingItem().quantity()).isNull();
        assertThat(checked.shoppingItem().stockApplied()).isFalse();
        service.delete(user,false,generated.id(),purchased.shoppingItem().version(),null);
        assertThat(service.pantry(user,stock.id()).quantity()).isEqualByComparingTo("2");
        assertThat(service.shoppingList("another-user",null,null,0,50).total()).isZero();
    }
    @Test void frontendAndAgentShareLiveDataAndAgentBatchesAreAtomicAndScoped() throws Exception {
        String user="household-"+UUID.randomUUID();
        var conversation=conversations.newConversation(user,"Compras");
        turns.begin(user,conversation.conversationId(),"Coloca leite e café na lista.");
        var api=MockMvcBuilders.webAppContextSetup(context).build();
        api.perform(options("/api/v1/pantry/items").header("Origin","http://localhost:5173")
                .header("Access-Control-Request-Method","POST").header("Access-Control-Request-Headers","Content-Type, Idempotency-Key"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin","http://localhost:5173"));
        api.perform(options("/api/v1/pantry/items").header("Origin","https://unconfigured.example")
                .header("Access-Control-Request-Method","POST")).andExpect(status().isForbidden());
        api.perform(post("/api/v1/shopping-list/items").principal(()->user).contentType("application/json").content("{\"name\":\"Leite\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"));
        String auth="Bearer household-integration-test-key-1234567890";
        api.perform(post("/internal/hermes/household/list").header("Authorization",auth).header("X-Ayla-Session",conversation.sessionId())
                .contentType("application/json").content("{\"target\":\"SHOPPING\",\"query\":\"leite\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        api.perform(post("/internal/hermes/household/list").header("Authorization","Bearer wrong").header("X-Ayla-Session",conversation.sessionId())
                .contentType("application/json").content("{\"target\":\"PANTRY\"}"))
                .andExpect(status().isUnauthorized());
        String invalid="""
                {"actions":[{"operation":"SHOPPING_SAVE","item":{"name":"Café"}},
                  {"operation":"PANTRY_SAVE","item":{"name":"Arroz","unit":"KG"}}]}
                """;
        api.perform(post("/internal/hermes/household/apply").header("Authorization",auth).header("X-Ayla-Session",conversation.sessionId())
                .contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
        assertThat(service.shoppingList(user,"cafe",Status.PENDING,0,50).total()).isZero();
        String valid="{\"actions\":[{\"operation\":\"SHOPPING_SAVE\",\"item\":{\"name\":\"Café\"}}]}";
        for(int i=0;i<2;i++) api.perform(post("/internal/hermes/household/apply").header("Authorization",auth).header("X-Ayla-Session",conversation.sessionId())
                .contentType("application/json").content(valid)).andExpect(status().isOk());
        var coffee=service.shoppingList(user,"café",Status.PENDING,0,50).items().getFirst();
        assertThat(service.shoppingList(user,"café",Status.PENDING,0,50).total()).isEqualTo(1);
        api.perform(patch("/api/v1/shopping-list/items/"+coffee.id()).principal(()->user).contentType("application/json")
                .content("{\"version\":0,\"quantity\":2,\"unit\":\"UN\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        api.perform(delete("/api/v1/shopping-list/items/"+coffee.id()).principal(()->user).param("version","0")).andExpect(status().isConflict());
        api.perform(get("/api/v1/shopping-list/items/"+coffee.id()).principal(()->"other-user")).andExpect(status().isNotFound());
        api.perform(delete("/api/v1/shopping-list/items/"+coffee.id()).principal(()->user).param("version","1")).andExpect(status().isNoContent());
        api.perform(post("/api/v1/pantry/items").principal(()->user).contentType("application/json")
                .content("{\"name\":\"Arroz\",\"quantity\":-1,\"unit\":\"KG\"}")).andExpect(status().isBadRequest());
    }
}
