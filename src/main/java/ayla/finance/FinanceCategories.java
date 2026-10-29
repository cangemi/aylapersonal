package ayla.finance;

import java.text.Normalizer;
import java.util.*;

/** Categorias padrão e inferência por palavras-chave, para "Outros" ficar só com o que não dá para classificar. */
public final class FinanceCategories {
    private FinanceCategories() {}

    public static final String OTHER = "Outros";
    public static final List<String> NAMES = List.of("Mercado", "Alimentação", "Casa", "Contas", "Transporte", "Saúde", "Lazer", "Jogos",
            "Assinaturas", "Educação", "Vestuário", "Beleza", "Pets", "Presentes", "Viagem", "Tecnologia", "Impostos e taxas",
            "Família", "Trabalho", "Salário", "Renda extra", "Investimento", OTHER);

    private static final Map<String, List<String>> KEYWORDS = new LinkedHashMap<>();
    static {
        KEYWORDS.put("Investimento", List.of("caixinha", "caixinhas", "investimento", "investimentos", "investi", "investir", "investindo", "aporte", "aportei", "aportar", "resgate", "resgatei", "resgatar", "apliquei", "aplicacao", "guardei", "guardar", "retirei", "saquei", "cdb", "lci", "lca", "tesouro", "acoes", "fii", "cripto", "bitcoin", "poupanca", "rendimento da caixinha"));
        KEYWORDS.put("Mercado", List.of("mercado", "supermercado", "atacadao", "hortifruti", "feira", "acougue", "padaria", "sacolao", "assai", "carrefour"));
        KEYWORDS.put("Alimentação", List.of("restaurante", "lanche", "almoco", "jantar", "cafe", "pizza", "hamburguer", "ifood", "sorvete", "marmita", "bar ", "cerveja", "delivery", "salgado", "pastel", "acai"));
        KEYWORDS.put("Transporte", List.of("gasolina", "combustivel", "etanol", "uber", "99", "onibus", "metro", "estacionamento", "pedagio", "passagem", "ipva", "oficina", "mecanico", "carro", "moto", "oleo", "pneu", "lavagem"));
        KEYWORDS.put("Contas", List.of("luz", "energia", "agua", "internet", "telefone", "celular", "gas", "condominio", "aluguel", "iptu", "conta de"));
        KEYWORDS.put("Casa", List.of("bacia", "vaso", "movel", "sofa", "cama", "colchao", "eletrodomestico", "geladeira", "fogao", "reforma", "tinta", "cimento", "ferramenta", "limpeza", "detergente", "pia", "chuveiro", "torneira", "casa", "decoracao", "lampada", "faxina"));
        KEYWORDS.put("Saúde", List.of("farmacia", "remedio", "medico", "consulta", "exame", "dentista", "hospital", "plano de saude", "terapia", "psicologo", "vitamina", "otica", "oculos"));
        KEYWORDS.put("Jogos", List.of("video game", "videogame", "game", "jogo", "steam", "playstation", "psn", "xbox", "nintendo", "switch", "epic games", "dlc"));
        KEYWORDS.put("Assinaturas", List.of("netflix", "spotify", "disney", "prime", "hbo", "max", "youtube premium", "assinatura", "mensalidade app", "icloud", "chatgpt", "copilot"));
        KEYWORDS.put("Lazer", List.of("cinema", "show", "ingresso", "teatro", "parque", "balada", "festa", "passeio", "boliche", "clube", "streaming"));
        KEYWORDS.put("Educação", List.of("curso", "faculdade", "escola", "livro", "apostila", "mensalidade", "matricula", "udemy", "material escolar"));
        KEYWORDS.put("Vestuário", List.of("roupa", "camisa", "camiseta", "calca", "tenis", "sapato", "sandalia", "bermuda", "jaqueta", "meia", "vestido"));
        KEYWORDS.put("Beleza", List.of("cabelo", "barbeiro", "salao", "manicure", "perfume", "maquiagem", "shampoo", "creme", "estetica", "depilacao"));
        KEYWORDS.put("Pets", List.of("pet", "racao", "veterinario", "cachorro", "gato", "banho e tosa", "petshop"));
        KEYWORDS.put("Presentes", List.of("presente", "aniversario", "doacao", "dizimo"));
        KEYWORDS.put("Viagem", List.of("viagem", "hotel", "hospedagem", "airbnb", "passagem aerea", "voo", "pousada"));
        KEYWORDS.put("Tecnologia", List.of("notebook", "computador", "teclado", "mouse", "monitor", "fone", "headset", "carregador", "cabo", "tablet", "smartphone", "memoria ram", "ssd", "placa de video"));
        KEYWORDS.put("Impostos e taxas", List.of("imposto", "taxa", "multa", "tarifa", "anuidade", "cartorio", "juros", "iof", "darf", "das", "irpf"));
        KEYWORDS.put("Trabalho", List.of("material de escritorio", "cliente", "coworking", "ferramenta de trabalho"));
        KEYWORDS.put("Salário", List.of("salario", "pagamento", "holerite", "13o", "decimo terceiro", "ferias"));
        KEYWORDS.put("Renda extra", List.of("freela", "freelance", "venda", "bonus", "comissao", "reembolso", "cashback", "rendimento"));
    }

    private static String plain(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).strip();
    }

    private static boolean contains(String text, String keyword) {
        // palavra inteira (ou prefixo de palavra) para "gas" não casar com "gasolina"
        return java.util.regex.Pattern.compile("(?<![a-z0-9])" + java.util.regex.Pattern.quote(keyword.strip()) + "(?![a-z0-9])").matcher(text).find();
    }

    public static Optional<String> canonical(String category) {
        String key = plain(category);
        return NAMES.stream().filter(n -> plain(n).equals(key)).findFirst();
    }

    public static String infer(String description) {
        String text = plain(description);
        for (var entry : KEYWORDS.entrySet())
            for (String keyword : entry.getValue())
                if (contains(text, plain(keyword))) return entry.getKey();
        return OTHER;
    }

    /** Usa a categoria informada quando é válida; vazia ou "Outros" é inferida pela descrição. */
    public static String resolve(String category, String description) {
        if (category == null || category.isBlank()) return infer(description);
        var known = canonical(category);
        if (known.isPresent()) return OTHER.equals(known.get()) ? infer(description) : known.get();
        String key = plain(category);
        if (key.equals("geral") || key.equals("diversos") || key.equals("outras")) return infer(description);
        return category.strip();
    }
}
