package ayla.hermes;

import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class HermesModels {
    public record Model(String provider, String model, boolean free) {}
    private final HermesClient client;
    private record Cached(List<Model> models,long expires) {}
    private final java.util.concurrent.ConcurrentMap<String,Cached> cache=new java.util.concurrent.ConcurrentHashMap<>();
    public HermesModels(HermesClient client) { this.client = client; }

    public List<Model> available() {
        return available("default");
    }
    public List<Model> available(String profile) {
        long now=System.nanoTime();var cached=cache.get(profile);
        if(cached!=null&&cached.expires()>now)return cached.models();
        // refresh=1 avoids making a decision from an incomplete, asynchronously warmed price cache.
        var models=freeModels(client.get(profile, "/api/model/options?include_unconfigured=false&refresh=1"));
        if(!models.isEmpty()){
            if(cache.size()>512)cache.entrySet().removeIf(entry->entry.getValue().expires()<=now);
            cache.put(profile,new Cached(models,now+java.util.concurrent.TimeUnit.SECONDS.toNanos(15)));
        }
        return models;
    }

    static List<Model> freeModels(Map<String, Object> payload) {
        Set<Model> result = new LinkedHashSet<>();
        if (!(payload.get("providers") instanceof List<?> providers)) return List.of();
        for (Object item : providers) {
            if (!(item instanceof Map<?, ?> row) || !"nous".equals(row.get("slug"))
                    || !Boolean.TRUE.equals(row.get("authenticated"))
                    || Boolean.TRUE.equals(row.get("pricing_pending"))
                    || Boolean.TRUE.equals(row.get("free_tier_pending"))) continue;
            if (!(row.get("models") instanceof List<?> models)) continue;
            var unavailable = row.get("unavailable_models") instanceof List<?> values ? values : List.of();
            var pricing = row.get("pricing") instanceof Map<?, ?> prices ? prices : Map.of();
            for (Object value : models) {
                if (!(value instanceof String model) || model.isBlank() || unavailable.contains(model)) continue;
                boolean free = Boolean.TRUE.equals(row.get("free_tier_row"));
                if (pricing.get(model) instanceof Map<?, ?> price)
                    free |= Boolean.TRUE.equals(price.get("free")) && "free".equals(price.get("input"))
                            && ("free".equals(price.get("output")) || "".equals(price.get("output")));
                if (free) result.add(new Model("nous", model, true));
            }
        }
        return List.copyOf(result);
    }

    public Model select(String configured) {
        return select(configured, "default");
    }
    public Model selectForImages(String profile) {
        // This native visual model is verified; never use an automatic paid vision provider.
        return available(profile).stream().filter(m -> m.model().equals("stepfun/step-3.7-flash:free"))
                .findFirst().orElseThrow(()->new HermesException(503,
                        "O modelo visual gratuito está indisponível no Hermes. Não consegui analisar a imagem agora."));
    }
    public Model select(String configured, String profile) {
        var models = available(profile);
        if (models.isEmpty()) throw new HermesException(503,
                "Nenhum modelo gratuito Nous disponível no Hermes. Conclua o login/configuração do Hermes ou aguarde a renovação do limite gratuito.");
        if (configured == null || configured.isBlank()) {
            // Prefer the free model validated for native financial tool calls; respect manual selection.
            return models.stream().filter(m -> m.model().equals("stepfun/step-3.7-flash:free"))
                    .findFirst().orElse(models.getFirst());
        }
        return models.stream().filter(m -> m.model().equals(configured)).findFirst()
                .orElseThrow(() -> new HermesException(409,
                        "O modelo escolhido não está disponível gratuitamente. Consulte /api/v1/hermes/models e atualize a configuração."));
    }
}
