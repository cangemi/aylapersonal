package ayla.household;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;

public final class HouseholdData {
    private HouseholdData() {}
    public enum Unit { UN, KG, G, L, ML, PACOTE }
    public enum Status { PENDING, IN_CART, PURCHASED, ARCHIVED }
    public enum Adjustment { ADD, CONSUME }
    public record ItemInput(
            @Size(max=160) String name,
            @DecimalMin("0") @DecimalMax("999999999") @Digits(integer=9,fraction=3) BigDecimal quantity,
            Unit unit,
            @DecimalMin("0") @DecimalMax("999999999") @Digits(integer=9,fraction=3) BigDecimal minimumQuantity,
            LocalDate expiresOn,@Size(max=80) String category,@Size(max=500) String notes,
            @Min(0) Long version,Boolean clearExpiry,
            @Min(0) @Max(9007199254740991L) @tools.jackson.databind.annotation.JsonDeserialize(using=ayla.finance.CentsDeserializer.class) Long unitPriceCents,
            @Min(0) @Max(9007199254740991L) @tools.jackson.databind.annotation.JsonDeserialize(using=ayla.finance.CentsDeserializer.class) Long totalPriceCents,
            Boolean inCart,Boolean clearPrice) {
        public ItemInput(String name,BigDecimal quantity,Unit unit,BigDecimal minimumQuantity,LocalDate expiresOn,String category,String notes,Long version,Boolean clearExpiry){this(name,quantity,unit,minimumQuantity,expiresOn,category,notes,version,clearExpiry,null,null,null,null);}
    }
    public record Adjust(@NotNull @Min(0) Long version,@NotNull Adjustment action,
                         @NotNull @DecimalMin("0.001") @DecimalMax("999999999") @Digits(integer=9,fraction=3) BigDecimal quantity,
                         @NotNull Unit unit,LocalDate expiresOn) {}
    public record Purchase(@NotNull @Min(0) Long version,
                           @Schema(description="true adiciona a compra à despensa na mesma transação; false/ausente só marca como comprada.") Boolean addToPantry,
                           @DecimalMin("0.001") @DecimalMax("999999999") @Digits(integer=9,fraction=3) BigDecimal quantity,
                           Unit unit,LocalDate expiresOn) {}
    public record PantryItem(UUID id,String name,BigDecimal quantity,Unit unit,BigDecimal minimumQuantity,
                             LocalDate expiresOn,boolean lowStock,boolean expired,String category,String notes,
                             long version,Instant createdAt,Instant updatedAt) {}
    public record ShoppingItem(UUID id,String name,BigDecimal quantity,Unit unit,String category,String notes,
                               Status status,boolean stockApplied,Instant purchasedAt,long version,Instant createdAt,Instant updatedAt,
                               Long unitPriceCents,Long totalPriceCents,UUID checkoutId) {}
    public record Page<T>(List<T> items,long total,int page,int size) {}
    public record PurchaseResult(ShoppingItem shoppingItem,PantryItem pantryItem) {}
    public record Generated(List<ShoppingItem> items) {}
    public record Deleted(boolean deleted) {}
    public record CartSummary(long cartTotalCents,int cartCount,int pendingCount,int missingPrices,int missingQuantities,List<ShoppingItem> cartItems) {}
    public record CartTarget(@NotNull UUID id,@NotNull @Min(0) Long version) {}
    public record Checkout(@NotNull @Size(min=1,max=200) List<@NotNull @jakarta.validation.Valid CartTarget> items,OffsetDateTime occurredAt) {}
    public record CheckoutSummary(UUID id,long totalCents,Instant paidAt,UUID financialEntryId,int itemCount,int purchasedCount,boolean archived) {}
    public record CheckoutResult(UUID id,long totalCents,ayla.finance.FinanceData.Entry financialEntry,List<ShoppingItem> items) {}
}
