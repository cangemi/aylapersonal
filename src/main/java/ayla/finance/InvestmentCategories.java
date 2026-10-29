package ayla.finance;

import java.util.List;
import java.util.stream.Collectors;

/** Categorias financeiras tratadas como dinheiro guardado em investimento (aporte = despesa, resgate = receita). */
public final class InvestmentCategories {
    public static final String DEFAULT = "Investimento";
    public static final List<String> NAMES = List.of("investimento", "investimentos", "caixinha", "caixinha nubank");
    /** Constantes fixas, seguras para compor SQL: 'a','b'. */
    public static final String SQL_LIST = NAMES.stream().map(name -> "'" + name + "'").collect(Collectors.joining(","));
    private InvestmentCategories() {}
    public static boolean matches(String category) { return category != null && NAMES.contains(category.strip().toLowerCase(java.util.Locale.ROOT)); }
}
