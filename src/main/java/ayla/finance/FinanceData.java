package ayla.finance;

import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.annotation.JsonDeserialize;
import java.time.*;
import java.util.*;

public final class FinanceData {
    private FinanceData() {}
    public enum Type { INCOME, EXPENSE }
    public enum Group { DAY, MONTH, CATEGORY }
    public record Input(
            @NotNull Type type,
            @NotNull @Positive @Max(9007199254740991L)
            @JsonDeserialize(using=CentsDeserializer.class)
            @Schema(example="3590", description="Inteiro em centavos de BRL. 3590 = R$ 35,90; 10 = R$ 0,10. Máximo 9007199254740991.") Long amountCents,
            @NotBlank @Size(max=300) String description,
            @NotBlank @Size(max=80) String category,
            @Schema(description="Data/hora com fuso. Omitir usa o instante atual", example="2026-10-22T12:30:00-03:00") OffsetDateTime occurredAt) {}
    public record Edit(@NotNull @PositiveOrZero Long version, @NotNull @jakarta.validation.Valid Input entry) {}
    public record Entry(UUID id, Type type, long amountCents, String currency, String description,
                        String category, OffsetDateTime occurredAt, Instant createdAt, Instant updatedAt, long version) {}
    public record Entries(List<Entry> items, long total, int page, int size) {}
    public record Totals(long incomeCents, long expenseCents, long netCents) {}
    public record Bucket(String label, long incomeCents, long expenseCents, long netCents) {}
    public record Summary(String currency, String timezone, LocalDate from, LocalDate to, Instant asOf,
                          long balanceCents, Totals period, Group groupBy, List<Bucket> series) {}
}
