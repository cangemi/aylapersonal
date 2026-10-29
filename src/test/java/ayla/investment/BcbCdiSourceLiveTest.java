package ayla.investment;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class BcbCdiSourceLiveTest {
    @Test void fetchesRealCdiFromBancoCentralWhenOnline() {
        var source = new BcbCdiSource("https://api.bcb.gov.br/dados/serie/bcdata.sgs.12/dados");
        java.util.List<?> rates;
        try {
            rates = source.fetch(LocalDate.now().minusDays(20), LocalDate.now());
        } catch (RuntimeException offline) {
            assumeTrue(false, "Banco Central inacessível: " + offline.getMessage());
            return;
        }
        assertFalse(rates.isEmpty());
        var first = (CdiSource.Rate) rates.getFirst();
        assertTrue(first.dailyPercent().doubleValue() > 0.01 && first.dailyPercent().doubleValue() < 0.2);
    }
}
