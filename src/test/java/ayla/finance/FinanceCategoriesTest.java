package ayla.finance;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class FinanceCategoriesTest {
    @Test void infersFromDescriptionWhenCategoryIsMissingOrOutros() {
        assertEquals("Transporte", FinanceCategories.resolve(null, "gasolina"));
        assertEquals("Casa", FinanceCategories.resolve("Outros", "uma bacia, foi vinte"));
        assertEquals("Mercado", FinanceCategories.resolve(" ", "Compras no Mercado"));
        assertEquals("Jogos", FinanceCategories.resolve(null, "Jogo na Steam"));
        assertEquals("Contas", FinanceCategories.resolve(null, "conta de luz"));
        assertEquals("Contas", FinanceCategories.resolve(null, "gás"));
        assertEquals("Investimento", FinanceCategories.resolve(null, "aporte na caixinha"));
        assertEquals("Investimento", FinanceCategories.resolve(null, "resgate de 200"));
        assertEquals("Investimento", FinanceCategories.resolve(null, "coloquei 100 na caixinha"));
        assertEquals("Investimento", FinanceCategories.resolve(null, "tirei da caixinha"));
        assertEquals("Investimento", FinanceCategories.resolve(null, "investi 50"));
    }

    @Test void keepsExplicitCategoriesAndFallsBackToOutros() {
        assertEquals("Lazer", FinanceCategories.resolve("lazer", "gasolina"));
        assertEquals("Minha categoria", FinanceCategories.resolve("Minha categoria", "x"));
        assertEquals("Outros", FinanceCategories.resolve(null, "cartão"));
        assertEquals("Outros", FinanceCategories.resolve(null, "Transferência Pix"));
    }
}
