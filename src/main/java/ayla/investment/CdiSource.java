package ayla.investment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Fonte da taxa CDI diária em % ao dia. */
public interface CdiSource {
    record Rate(LocalDate date, BigDecimal dailyPercent) {}
    List<Rate> fetch(LocalDate from, LocalDate to);
}
