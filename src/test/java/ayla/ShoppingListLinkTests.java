package ayla;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import ayla.chat.session.ConversationContext;
class ShoppingListLinkTests {
 @Test void includesAuthenticatedShoppingPageInBothConversationScopes() {
  var context=new ConversationContext(null,"https://ayla.example");
  assertThat(context.shoppingListUrl()).isEqualTo("https://ayla.example/compras");
  assertThat(context.instructions(true)).contains("https://ayla.example/compras");
  assertThat(context.instructions(false)).contains("https://ayla.example/compras").contains("exige login");
 }
}
