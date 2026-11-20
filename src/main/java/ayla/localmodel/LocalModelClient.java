package ayla.localmodel;

import java.time.Duration;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Converse casual em um modelo pequeno local (Ollama); indisponibilidade nunca bloqueia o chat. */
@Component
public class LocalModelClient {
    private static final ParameterizedTypeReference<Map<String, Object>> TYPE = new ParameterizedTypeReference<>() {};
    private final RestClient client;
    private final String model;
    private final boolean enabled;
    public LocalModelClient(@Value("${ayla.local-model.base-url:http://127.0.0.1:11434}") String baseUrl,
                            @Value("${ayla.local-model.model:qwen3:4b}") String model,
                            @Value("${ayla.local-model.timeout-seconds:30}") int timeoutSeconds,
                            @Value("${ayla.local-model.enabled:true}") boolean enabled) {
        this.model = model;
        this.enabled = enabled && !baseUrl.isBlank();
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)));
        this.client = RestClient.builder().baseUrl(baseUrl.replaceAll("/$", "")).requestFactory(factory).build();
    }
    /** Conteúdo JSON do assistente, ou null quando o modelo local está desligado ou indisponível. */
    public String complete(String system, List<Map<String, String>> history, String user) {
        if (!enabled) return null;
        var messages = new ArrayList<Map<String, Object>>();
        messages.add(Map.of("role", "system", "content", system));
        if (history != null) for (var turn : history)
            if (turn.get("role") != null && turn.get("content") != null)
                messages.add(Map.of("role", turn.get("role"), "content", turn.get("content")));
        messages.add(Map.of("role", "user", "content", user));
        var body = new HashMap<String, Object>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", false);
        body.put("format", "json");
        body.put("think", false);
        body.put("keep_alive", "10m");
        body.put("options", Map.of("temperature", 0.7, "top_p", 0.9, "num_predict", 400, "num_ctx", 2048));
        long started = System.nanoTime();
        try {
            var result = client.post().uri("/api/chat").body(body).retrieve().body(TYPE);
            if (result == null || !(result.get("message") instanceof Map<?, ?> message)
                    || !(message.get("content") instanceof String content) || content.isBlank()) return null;
            LoggerFactory.getLogger(getClass()).info("Modelo local disponivel={} tempoMs={}", model,
                    (System.nanoTime() - started) / 1_000_000);
            return content;
        } catch (RuntimeException unavailable) {
            LoggerFactory.getLogger(getClass()).debug("Modelo local indisponível; o turno segue pelo Hermes.");
            return null;
        }
    }
}
