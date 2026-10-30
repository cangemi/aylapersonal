package ayla.hermes;

import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Only talks to the local Hermes gateway, never to an inference provider. */
@Component
public class HermesClient {
    private static final Logger log = LoggerFactory.getLogger(HermesClient.class);
    private static final ParameterizedTypeReference<Map<String, Object>> TYPE = new ParameterizedTypeReference<>() {};
    private RestClient client;
    private final String baseUrl;
    private final SimpleClientHttpRequestFactory requestFactory;
    @org.springframework.beans.factory.annotation.Autowired
    void observe(RestClient.Builder builder){client=builder.clone().baseUrl(baseUrl).requestFactory(requestFactory).observationConvention(new ayla.monitoring.SafeClientObservation()).build();}
    private record SessionKey(String profile,String id) {}
    private final java.util.concurrent.ConcurrentMap<SessionKey,Long> knownSessions=new java.util.concurrent.ConcurrentHashMap<>();
    private static final long SESSION_TTL=java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    private void rememberSession(SessionKey session){
        long now=System.nanoTime();
        if(knownSessions.size()>=1024)knownSessions.entrySet().removeIf(entry->entry.getValue()<=now);
        if(knownSessions.size()<1024)knownSessions.put(session,now+SESSION_TTL);
    }
    private final String key;
    private final org.springframework.core.env.Environment environment;

    @org.springframework.beans.factory.annotation.Autowired
    public HermesClient(@Value("${ayla.hermes.base-url}") String baseUrl,
                        @Value("${ayla.hermes.api-key:}") String key,
                        @Value("${ayla.hermes.timeout-seconds:180}") int timeoutSeconds,
                        org.springframework.core.env.Environment environment) {
        this.key = key;
        this.baseUrl=baseUrl;
        this.environment = environment;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.requestFactory=factory;
        client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + key).build();
    }

    public HermesClient(String baseUrl, String key, int timeoutSeconds) {
        this(baseUrl, key, timeoutSeconds, new org.springframework.core.env.StandardEnvironment());
    }
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    private ayla.monitoring.AiUsageService usage;
    @org.springframework.beans.factory.annotation.Autowired
    public void setUsage(ayla.monitoring.AiUsageService usage) { this.usage=usage; }
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setJdbc(org.springframework.jdbc.core.JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private String profileKey(String profile) {
        if ("default".equals(profile)) return key;
        if (jdbc != null) {
            Long bindings=jdbc.queryForObject("select count(*) from chat_users where hermes_profile=?",Long.class,profile);
            if(bindings==null||bindings!=1L)throw new HermesException(409,"Perfil Hermes sem vínculo exclusivo.");
            var stored = jdbc.queryForList("select hermes_api_key from chat_users where hermes_profile=? and hermes_api_key is not null", String.class, profile);
            if (!stored.isEmpty() && !stored.getFirst().isBlank()) return stored.getFirst();
        }
        return environment.getProperty("ayla.hermes.profiles." + profile + ".api-key", "");
    }
    public Map<String, Object> get(String path) { return exchange("default", path, null); }
    public Map<String, Object> post(String path, Map<String, Object> body) { return exchange("default", path, body); }
    public Map<String, Object> get(String profile, String path) { return exchange(profile, path, null); }
    public Map<String, Object> post(String profile, String path, Map<String, Object> body) { return exchange(profile, path, body); }
    public Map<String,Object> delete(String profile,String path) {
        forgetSession(profile,path);
        return exchange(profile,path,null,org.springframework.http.HttpMethod.DELETE);
    }
    private void forgetSession(String profile,String path){
        if(path.startsWith("/api/sessions/")){
            String id=path.substring("/api/sessions/".length()).split("/",2)[0];
            knownSessions.remove(new SessionKey(profile,id));
        }
    }

    public void ensureSession(String profile, String id, Map<String, Object> creation) {
        var session=new SessionKey(profile,id);
        Long expires=knownSessions.get(session);
        if(expires!=null&&expires>System.nanoTime())return;
        String path = "/api/sessions/" + id;
        try { get(profile, path); rememberSession(session); return; }
        catch (HermesException e) { if (e.status() != 404) throw e; }
        try { post(profile, "/api/sessions", creation); }
        catch (HermesException e) {
            // Another request may have created the same durable session. No inference is retried.
            if (e.status() != 409) throw e;
            get(profile, path);
        }
        rememberSession(session);
    }

    private Map<String, Object> exchange(String profile, String path, Map<String, Object> body) {
        return exchange(profile,path,body,body==null?org.springframework.http.HttpMethod.GET:org.springframework.http.HttpMethod.POST);
    }
    private Map<String,Object> exchange(String profile,String path,Map<String,Object> body,org.springframework.http.HttpMethod method) {
        if (profile == null || !profile.matches("[a-zA-Z0-9_-]{1,64}"))
            throw new HermesException(503, "Perfil Hermes inválido para este usuário.");
        String profileKey = profileKey(profile);
        if(!"default".equals(profile)&&!profileKey.isBlank()&&profileKey.equals(key))throw new HermesException(503,"O perfil Hermes precisa de uma chave própria.");
        if (profileKey.isBlank()) throw new HermesException(503, "default".equals(profile)
                ? "Configure HERMES_API_SERVER_KEY para conectar ao Hermes."
                : "Configure a chave de acesso ao perfil Hermes deste usuário.");
        String routedPath = "default".equals(profile) ? path : "/p/" + profile + path;
        long started = System.nanoTime();
        Map<String,Object> usageResponse=null;
        int usageStatus=502;
        try {
            Map<String,Object> result;
            while(true){
                try {
                    if(body!=null && path.matches("/api/sessions/[A-Za-z0-9_-]+/chat"))
                        ayla.chat.queue.ChatJobContext.beforeInference();
                    var request = client.method(method).uri(routedPath);
                    if(body!=null) request.body(body);
                    request.headers(headers -> headers.setBearerAuth(profileKey));
                    result=request.retrieve().body(TYPE);
                    if(body!=null && path.endsWith("/chat"))ayla.chat.queue.ChatJobContext.inferenceAccepted();
                    break;
                }catch(RestClientResponseException rejected){
                    // This pinned local gateway returns HTTP 429 at admission, BEFORE running the agent.
                    // Retry only that explicit rejection; never replay a timeout, 5xx or failed turn.
                    if(rejected.getStatusCode().value()!=429 || body==null || !path.endsWith("/chat")
                            || !ayla.chat.queue.ChatJobContext.active())throw rejected;
                    ayla.chat.queue.ChatJobContext.admissionRejected();
                    if(ayla.chat.queue.ChatJobContext.safeToRequeue()){
                        usageStatus=429;
                        throw new ayla.cluster.OperationBusyException(429,"Aguardando disponibilidade da Ayla.");
                    }
                    try {Thread.sleep(5000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new HermesException(503,"Processamento interrompido.");}
                }
            }
            if (result == null && method==org.springframework.http.HttpMethod.DELETE) result=Map.of();
            if (result == null) throw new HermesException(502, "O Hermes retornou uma resposta vazia.");
            if(body!=null && path.matches("/api/sessions/[A-Za-z0-9_-]+/chat") && "completed".equals(result.get("status")))
                rememberSession(new SessionKey(profile,path.substring("/api/sessions/".length(),path.length()-"/chat".length())));
            usageResponse=result;
            usageStatus="completed".equals(result.get("status"))?200:502;
            log.info("Hermes operacao={} tempoMs={} resultado={}", path.split("\\?")[0], (System.nanoTime() - started) / 1_000_000,
                    body!=null && path.endsWith("/chat") && usageStatus>=400?"nao_concluido":"sucesso");
            return result;
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            usageStatus=status;
            // Do not log bodies or exception messages: they may contain tokens or conversation data.
            log.warn("Hermes operacao={} httpStatus={}", path.split("\\?")[0], status);
            if (status == 404 && path.startsWith("/api/sessions/")){
                forgetSession(profile,path);
                throw new HermesException(404, "Conversa não encontrada no Hermes. Inicie outra sem sessionId.");
            }
            if (status == 401 || status == 403)
                throw new HermesException(503, "O Hermes recusou a autenticação. Verifique HERMES_API_SERVER_KEY e o login Nous no Hermes.");
            if (status == 429) throw new HermesException(429, "O Hermes atingiu o limite de uso. Tente novamente mais tarde.");
            if (status == 409) throw new HermesException(409, "Conflito ao atualizar a sessão no Hermes.");
            throw new HermesException(502, "Falha ao executar a operação no Hermes (HTTP " + status + "). Consulte os logs do Hermes.");
        } catch (ResourceAccessException e) {
            usageStatus=503;
            log.warn("Hermes operacao={} resultado=indisponivel", path.split("\\?")[0]);
            throw new HermesException(503, "O Hermes não respondeu. Verifique se o contêiner está ativo e tente novamente.");
        } catch (org.springframework.web.client.RestClientException e) {
            throw new HermesException(502, "O Hermes retornou uma resposta inválida.");
        } finally {
            if(usage!=null && body!=null && path.matches("/api/sessions/[A-Za-z0-9_-]+/chat"))
                usage.record(profile,body,usageResponse,usageStatus,(System.nanoTime()-started)/1_000_000);
        }
    }
}
