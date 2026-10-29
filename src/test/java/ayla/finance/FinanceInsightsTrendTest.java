package ayla.finance;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class FinanceInsightsTrendTest {
    private static List<FinanceInsights.Month> months(long... expenses) {
        var list = new java.util.ArrayList<FinanceInsights.Month>();
        for (int i = 0; i < expenses.length; i++) list.add(new FinanceInsights.Month("2026-0" + (i + 1), 0, expenses[i], 0, -expenses[i]));
        return list;
    }

    @Test void growingExpensesPointUpAndUseTheProjectionForTheOpenMonth() {
        var trend = FinanceInsights.trend(months(100_000, 120_000, 140_000, 10_000), 160_000);
        assertEquals("UP", trend.direction());
        assertEquals(20_000, trend.slopeCentsPerMonth());
        assertEquals(4, trend.lineCents().size());
    }

    @Test void fallingAndFlatSeries() {
        assertEquals("DOWN", FinanceInsights.trend(months(200_000, 150_000, 100_000, 50_000), 50_000).direction());
        assertEquals("STABLE", FinanceInsights.trend(months(100_000, 100_000, 100_000), 100_000).direction());
        assertEquals("STABLE", FinanceInsights.trend(months(0, 0, 0), 0).direction());
    }

    @Test void investmentCategoriesAreRecognisedIgnoringCase() {
        assertTrue(InvestmentCategories.matches(" Investimento "));
        assertTrue(InvestmentCategories.matches("CAIXINHA Nubank"));
        assertFalse(InvestmentCategories.matches("Mercado"));
    }
}
