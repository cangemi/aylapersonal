package ayla.hermes;

import ayla.chat.session.ChatIdentity;
import ayla.chat.session.ChatUserRepository;
import java.security.SecureRandom;
import java.security.Principal;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Creates and checks the caller's own Hermes profile without leaving the application. */
@Service
public class HermesAccountService {
    public record Status(boolean provisioned, boolean connected, boolean loggedIn, boolean ready, String message) {}
    private final JdbcTemplate jdbc;
    private final ChatUserRepository users;
    private final ChatIdentity identity;
    private final HermesDashboardClient dashboard;
    private final HermesClient client;
    private final HermesModels models;
    private final HermesUserScope scope;
    private ayla.cluster.ClusterOperations cluster;
    @org.springframework.beans.factory.annotation.Autowired
    public void setCluster(ayla.cluster.ClusterOperations cluster){this.cluster=cluster;}
    public HermesAccountService(JdbcTemplate jdbc, ChatUserRepository users, ChatIdentity identity,
            HermesDashboardClient dashboard, HermesClient client, HermesModels models,HermesUserScope scope) {
        this.jdbc = jdbc; this.users = users; this.identity = identity; this.dashboard = dashboard; this.client = client; this.models = models;this.scope=scope;
    }

    public String profile(String userId) {
        jdbc.update("insert into chat_users(user_id,hermes_profile,created_at) values (?,?,CURRENT_TIMESTAMP) on conflict (user_id) do nothing",
                userId, userId.equals(identity.ownerId()) ? "default" : null);
        return scope.profile(userId,false);
    }

    public String provision(String userId) {
        if(cluster!=null)return cluster.exclusive("profile:"+userId,()->provisionLocked(userId));
        return provisionLocked(userId);
    }
    private String provisionLocked(String userId) {
        var existing = profile(userId);
        if (existing != null && !existing.isBlank()) return existing;
        String name = "u-" + ayla.auth.AuthService.hash(userId).substring(0,32);
        if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from chat_users where hermes_profile=? and user_id<>?)",Boolean.class,name,userId)))
            throw new HermesException(409,"Perfil Hermes já vinculado a outra conta.");
        var bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        String key = HexFormat.of().formatHex(bytes);
        dashboard.admin("POST", "/api/profiles", Map.of("name", name, "clone_from", "ayla-internal-template", "clone_from_default", false,
                "description", "Perfil da Ayla do usuário"));
        dashboard.admin("PUT", "/api/env?profile=" + name, Map.of("key", "API_SERVER_KEY", "value", key, "profile", name));
        jdbc.update("update chat_users set hermes_profile=?, hermes_api_key=? where user_id=?", name, key, userId);
        if (!reachable(name)) {
            dashboard.admin("POST", "/api/gateway/restart", null);
            for (int i = 0; i < 30 && !reachable(name); i++) pause();
        }
        return name;
    }

    private boolean reachable(String profile) {
        try { client.get(profile, "/health"); return true; } catch (HermesException e) { return false; }
    }
    private void pause() {
        try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public Status status(String userId) {
        var profile = profile(userId);
        if (profile == null || profile.isBlank())
            return new Status(false, false, false, false, "Seu perfil Hermes ainda não foi criado.");
        if (!reachable(profile))
            return new Status(true, false, false, false, "O Hermes ainda está iniciando seu perfil. Tente novamente em instantes.");
        boolean logged = loggedIn(profile);
        if (!logged) return new Status(true, true, false, false, "Entre na sua conta Nous para liberar o Hermes. Cada usuário usa a própria conta.");
        try {
            models.select(null, profile);
            return new Status(true, true, true, true, "Hermes pronto.");
        } catch (HermesException e) {
            return new Status(true, true, true, false, e.getMessage());
        }
    }

    private Map<?, ?> nousStatus(String profile) {
        var result = dashboard.admin("GET", "/api/providers/oauth" + query(profile), null);
        if (!(result.get("providers") instanceof java.util.List<?> list)) return Map.of();
        for (Object item : list)
            if (item instanceof Map<?, ?> row && "nous".equals(row.get("id")) && row.get("status") instanceof Map<?, ?> st) return st;
        return Map.of();
    }
    /** A profile without its own login silently falls back to the administrator's token; that does not count. */
    private boolean loggedIn(String profile) {
        var own = nousStatus(profile);
        if (!Boolean.TRUE.equals(own.get("logged_in")) || "default".equals(profile)) return Boolean.TRUE.equals(own.get("logged_in"));
        var inherited = nousStatus("default");
        return own.get("token_preview") != null && !own.get("token_preview").equals(inherited.get("token_preview"));
    }
    private String query(String profile) { return "default".equals(profile) ? "" : "?profile=" + profile; }

    public Map<String, Object> startLogin(String userId) {
        var profile = provision(userId);
        var result=dashboard.admin("POST", "/api/providers/oauth/nous/start" + query(profile), null);
        if(!(result.get("session_id") instanceof String session)||!session.matches("[A-Za-z0-9_-]{8,128}"))throw new HermesException(502,"O Hermes não retornou uma sessão de login válida.");
        jdbc.update("""
                insert into hermes_oauth_sessions(user_id,session_id,profile,expires_at) values (?,?,?,now()+interval '15 minutes')
                on conflict(user_id) do update set session_id=excluded.session_id,profile=excluded.profile,expires_at=excluded.expires_at
                """,userId,session,profile);
        return result;
    }
    public Map<String, Object> pollLogin(String userId, String session) {
        if (!session.matches("[A-Za-z0-9_-]{8,128}")) throw new HermesException(400, "Sessão de login inválida.");
        var issued=jdbc.queryForList("select profile from hermes_oauth_sessions where user_id=? and session_id=? and expires_at>now()",String.class,userId,session);
        if(issued.size()!=1)throw new HermesException(403,"Inicie novamente o login do Hermes nesta conta.");
        var profile = profile(userId);
        if (profile == null) throw new HermesException(409, "Inicie o login primeiro.");
        if(!profile.equals(issued.getFirst()))throw new HermesException(403,"O perfil da sessão de login foi alterado.");
        return dashboard.admin("GET", "/api/providers/oauth/nous/poll/" + session + query(profile), null);
    }

    public boolean ready(String userId) { return status(userId).ready(); }
    public void requireOwnLogin(String userId) {
        String profile=scope.profile(userId,true);
        if(!scope.owner(userId)&&!loggedIn(profile))throw new HermesException(409,"Conecte sua própria conta Hermes para conversar.");
    }

    public void delete(String userId) {
        var raw=jdbc.queryForList("select hermes_profile from chat_users where user_id=?",String.class,userId);
        if(raw.isEmpty()||raw.getFirst()==null||"default".equalsIgnoreCase(raw.getFirst()))return;
        String profile=scope.profile(userId,false);
        if(profile==null||"default".equals(profile))return;
        var available=dashboard.admin("GET","/api/profiles",null);
        if(!(available.get("profiles") instanceof java.util.List<?> list))throw new HermesException(503,"Não foi possível verificar o perfil Hermes antes de excluir a conta.");
        if(list.stream().anyMatch(item->item instanceof Map<?,?> row&&profile.equals(row.get("name"))))
            dashboard.admin("DELETE", "/api/profiles/"+profile,null);
    }

    @RestController
    @RequestMapping("/api/v1/me/hermes")
    @Tag(name = "Hermes", description = "Perfil Hermes do usuário autenticado.")
    public static class Controller {
        private final HermesAccountService service;
        private final ChatIdentity identity;
        public Controller(HermesAccountService service, ChatIdentity identity) { this.service = service; this.identity = identity; }
        private <T> ResponseEntity<T> ok(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
        @GetMapping("/status") public ResponseEntity<Status> status(@Parameter(hidden = true) Principal p) { return ok(service.status(identity.resolve(p))); }
        @PostMapping("/provision") public ResponseEntity<Status> provision(@Parameter(hidden = true) Principal p) {
            var id = identity.resolve(p); service.provision(id); return ok(service.status(id));
        }
        @PostMapping("/login") public ResponseEntity<Map<String, Object>> login(@Parameter(hidden = true) Principal p) { return ok(service.startLogin(identity.resolve(p))); }
        @GetMapping("/login/{session}") public ResponseEntity<Map<String, Object>> poll(@PathVariable String session, @Parameter(hidden = true) Principal p) {
            return ok(service.pollLogin(identity.resolve(p), session));
        }
    }
}
