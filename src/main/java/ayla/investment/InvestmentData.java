package ayla.investment;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class InvestmentData {
    private InvestmentData() {}

    public record CdiPoint(LocalDate date, BigDecimal dailyPercent, BigDecimal annualPercent) {}
    public record Cdi(BigDecimal dailyPercent, BigDecimal annualPercent, LocalDate date, boolean stale, String source, List<CdiPoint> history) {}
    public record Point(LocalDate date, long balanceCents, long investedCents, long yieldCents) {}
    public record MonthYield(String month, long yieldCents, long netDepositCents, long endBalanceCents) {}
    public record Projection(String label, LocalDate until, long balanceCents, long yieldCents) {}
    public record Movement(UUID entryId, LocalDate date, String type, long amountCents, String description) {}
    public record Baseline(LocalDate date, long balanceCents, long investedCents, Long monthYieldCents) {}
    public record BaselineInput(LocalDate date,
                                @NotNull @Positive @Max(100_000_000_000_000L) Long balanceCents,
                                @PositiveOrZero @Max(100_000_000_000_000L) Long investedCents,
                                @PositiveOrZero @Max(100_000_000_000_000L) Long monthYieldCents) {}
    public record Overview(String name, BigDecimal cdiPercent, boolean hasData, LocalDate asOf, Cdi cdi,
                           BigDecimal productAnnualPercent, long balanceCents, long depositedCents, long withdrawnCents,
                           long netInvestedCents, long gainCents, BigDecimal gainPercent,
                           long lastDayYieldCents, LocalDate lastYieldDate, long monthYieldCents, long last30DaysYieldCents,
                           long nextDayYieldCents, LocalDate nextYieldDate, long nextMonthYieldCents, String nextMonth,
                           List<Projection> projections, List<Point> history, List<MonthYield> months, List<Movement> movements,
                           Baseline baseline) {}

    public record SimulationInput(
            @PositiveOrZero @Max(100_000_000_000_000L) Long initialCents,
            @PositiveOrZero @Max(100_000_000_000_000L) Long monthlyCents,
            @NotNull @Min(1) @Max(600) Integer months,
            @DecimalMin("0.01") @DecimalMax("200") BigDecimal cdiAnnualPercent,
            @DecimalMin("1") @DecimalMax("1000") BigDecimal percentCdi) {}
    public record SimulationPoint(int month, LocalDate date, long balanceCents, long investedCents, long gainCents) {}
    public record Simulation(BigDecimal cdiAnnualPercent, BigDecimal percentCdi, BigDecimal productAnnualPercent,
                             long initialCents, long monthlyCents, int months, long finalBalanceCents,
                             long investedCents, long gainCents, List<SimulationPoint> points) {}

    public record Settings(@NotNull @DecimalMin("1") @DecimalMax("1000") BigDecimal cdiPercent) {}
}
