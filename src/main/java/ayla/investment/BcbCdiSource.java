package ayla.investment;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** API pública e gratuita do Banco Central (SGS 12, CDI diário). Só envia datas; nenhum dado do usuário sai daqui. */
@Component
public class BcbCdiSource implements CdiSource {
    private static final DateTimeFormatter BR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final ParameterizedTypeReference<List<Map<String, String>>> TYPE = new ParameterizedTypeReference<>() {};
    private final RestClient client;

    public BcbCdiSource(@Value("${ayla.investment.cdi-url:https://api.bcb.gov.br/dados/serie/bcdata.sgs.12/dados}") String url) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(15));
        client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    @Override
    public List<Rate> fetch(LocalDate from, LocalDate to) {
        List<Rate> rates = new ArrayList<>();
        // A API limita consultas diárias a 10 anos.
        for (LocalDate cursor = from; !cursor.isAfter(to); cursor = cursor.plusYears(9)) {
            final LocalDate start = cursor;
            final LocalDate end = start.plusYears(9).minusDays(1).isAfter(to) ? to : start.plusYears(9).minusDays(1);
            var body = client.get().uri(uri -> uri.queryParam("formato", "json")
                    .queryParam("dataInicial", BR.format(start)).queryParam("dataFinal", BR.format(end)).build())
                    .retrieve().body(TYPE);
            if (body == null) continue;
            for (var row : body) {
                String date = row.get("data"), value = row.get("valor");
                if (date != null && value != null) rates.add(new Rate(LocalDate.parse(date, BR), new BigDecimal(value)));
            }
        }
        return rates;
    }
}
