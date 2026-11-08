package ayla.investment;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class PortfolioData {
    private PortfolioData() {}
    public enum RateType { CDI, ANNUAL, MONTHLY, MANUAL }
    public enum DayBasis { BUSINESS, CALENDAR }
    public enum EventType { DEPOSIT, WITHDRAWAL, INCOME, VALUATION, RATE_CHANGE }
    public enum AssetType { CAIXINHA, CDB, RDB, LCI, LCA, TESOURO, POUPANCA, ACAO, FII, ETF, FUNDO, PREVIDENCIA, CRIPTO, OUTRO }

    public record Input(@NotBlank @Size(max=120) String name, @NotBlank @Size(max=120) String institution,
        @NotNull AssetType assetType, @Size(max=32) String ticker, @NotNull LocalDate openedOn,
        @NotNull @Min(0) @Max(100_000_000_000_000L) Long openingBalanceCents,
        @NotNull @Min(0) @Max(100_000_000_000_000L) Long openingInvestedCents,
        @NotNull RateType rateType, @NotNull @DecimalMin("0") @DecimalMax("1000") BigDecimal ratePercent,
        @NotNull DayBasis dayBasis, LocalDate maturityDate, @Size(max=2000) String notes, @Min(0) Long version) {}

    public record EventInput(@NotNull EventType type, @NotNull LocalDate date,
        @NotNull @Min(0) @Max(100_000_000_000_000L) Long amountCents, @Size(max=500) String notes,
        @NotNull @Min(0) Long version) {}

    public record Rate(RateType type, BigDecimal percent, DayBasis dayBasis) {}
    public record Event(UUID id, EventType type, LocalDate date, long amountCents, String notes, Rate rate) {}
    public record Position(UUID id, String name, String institution, AssetType assetType, String ticker,
        LocalDate openedOn, long openingBalanceCents, long openingInvestedCents, Rate rate,
        LocalDate maturityDate, String notes, long version) {}
    public record Point(LocalDate date, long balanceCents, long netInvestedCents, long gainCents) {}
    public record View(Position position, Rate currentRate, long balanceCents, long depositedCents,
        long withdrawnCents, long incomeCents, long netInvestedCents, long gainCents, BigDecimal gainPercent,
        long monthGainCents, Long next30DaysGainCents, LocalDate lastValuationDate,
        boolean estimated, List<String> warnings, List<Point> history, List<Event> events) {}
    public record Legacy(long balanceCents, long netInvestedCents, long gainCents, long monthGainCents) {}
    public record Portfolio(LocalDate asOf, long balanceCents, long netInvestedCents, long gainCents,
        long incomeCents, long monthGainCents, List<View> positions, Legacy legacy, List<String> warnings) {}
}
