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
    public record Status(boolean provisioned, boolean connected, boolean loggedIn, boolean ready, String message,boolean authAvailable) {
        public Status(boolean provisioned,boolean connected,boolean loggedIn,boolean ready,String message){this(provisioned,connected,loggedIn,ready,message,connected);}
    }
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
        // OAuth uses the dashboard, independently of the profile's inference process.
        // Never restart all users' gateways or hold login waiting for chat health.
        return name;
    }

    public Status status(String userId) {
        var profile = profile(userId);
        if (profile == null || profile.isBlank())
            return new Status(false, false, false, false, "Seu perfil Hermes ainda não foi criado.");
        boolean logged=false,authAvailable=false;
        try{logged=loggedIn(profile);authAvailable=true;}catch(HermesException unavailable){/* Report the two services separately. */}
        try{client.get(profile,"/health");}
        catch(HermesException failure){
            return new Status(true,false,logged,false,authAvailable
                ?"O servidor de conversas deste perfil está indisponível. Você pode conectar ou trocar sua conta Nous abaixo. Detalhe: "+failure.getMessage()
                :"Não foi possível acessar o servidor de conversas nem o painel de autorização do Hermes. Verifique os serviços e as credenciais da integração.",authAvailable);
        }
        if(!authAvailable)return new Status(true,true,false,false,"O servidor de conversas responde, mas o painel de autorização está indisponível. Tente conectar para verificar o erro.",false);
        if (!logged) return new Status(true, true, false, false, "Entre na sua conta Nous para liberar o Hermes. Cada usuário usa a própria conta.",true);
        // Account controls must not wait on the model/pricing catalogue. Chat still validates
        // the requested model and its free eligibility before every inference.
        return new Status(true,true,true,true,"Conta Nous autorizada e servidor de conversas conectado. A disponibilidade do modelo é verificada ao conversar.",true);
    }

    private Map<?, ?> nousStatus(String profile) {
        var result = dashboard.admin("GET", "/api/providers/oauth" + query(profile), null);
        if (!(result.get("providers") instanceof java.util.List<?> list)) return Map.of();
        for (Object item : list)
            if (item instanceof Map<?, ?> row && "nous".equals(row.get("id")) && row.get("status") instanceof Map<?, ?> st) return st;
        return Map.of();
    }
    /** Legacy profiles may contain copied credentials. Require an OAuth flow issued to this profile. */
    private boolean loggedIn(String profile) {
        if (!"default".equals(profile) && !Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from hermes_oauth_sessions s join chat_users u on u.user_id=s.user_id
                where s.profile=? and u.hermes_profile=s.profile and s.completed_at is not null)
                """, Boolean.class, profile))) return false;
        return Boolean.TRUE.equals(nousStatus(profile).get("logged_in"));
    }
    private String query(String profile) { return "default".equals(profile) ? "" : "?profile=" + profile; }

    public Map<String, Object> startLogin(String userId) {
        return cluster==null?startLoginLocked(userId):cluster.exclusive("profile:"+userId,()->startLoginLocked(userId));
    }
    private Map<String,Object> startLoginLocked(String userId){
        var profile = provisionLocked(userId);
        var previous=jdbc.queryForList("select session_id from hermes_oauth_sessions where user_id=? and profile=? and expires_at>now()",String.class,userId,profile);
        for(var session:previous)if(session.matches("[A-Za-z0-9_-]{8,128}"))
            dashboard.admin("DELETE","/api/providers/oauth/sessions/"+session+query(profile),null);
        var result=dashboard.admin("POST", "/api/providers/oauth/nous/start" + query(profile), null);
        if(!(result.get("session_id") instanceof String session)||!session.matches("[A-Za-z0-9_-]{8,128}"))throw new HermesException(502,"O Hermes não retornou uma sessão de login válida.");
        jdbc.update("""
                insert into hermes_oauth_sessions(user_id,session_id,profile,expires_at) values (?,?,?,now()+interval '15 minutes')
                on conflict(user_id) do update set session_id=excluded.session_id,profile=excluded.profile,expires_at=excluded.expires_at,completed_at=null
                """,userId,session,profile);
        return result;
    }
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> pollLogin(String userId, String session) {
        if (!session.matches("[A-Za-z0-9_-]{8,128}")) throw new HermesException(400, "Sessão de login inválida.");
        var issued=jdbc.queryForList("select profile from hermes_oauth_sessions where user_id=? and session_id=? and expires_at>now()",String.class,userId,session);
        if(issued.size()!=1)throw new HermesException(403,"Inicie novamente o login do Hermes nesta conta.");
        var profile = profile(userId);
        if (profile == null) throw new HermesException(409, "Inicie o login primeiro.");
        if(!profile.equals(issued.getFirst()))throw new HermesException(403,"O perfil da sessão de login foi alterado.");
        var result=dashboard.admin("GET", "/api/providers/oauth/nous/poll/" + session + query(profile), null);
        if("approved".equals(result.get("status"))&&!Boolean.TRUE.equals(nousStatus(profile).get("logged_in")))
            throw new HermesException(409,"A autorização não foi salva no seu perfil Hermes. Conecte novamente sua própria conta Nous.");
        if("approved".equals(result.get("status"))&&jdbc.update("update hermes_oauth_sessions set completed_at=now() where user_id=? and session_id=? and completed_at is null",userId,session)==1){
            // Keep an observation barrier so late events from the old account cannot restore its quota.
            // Only this user's pointer changes; shared quotas, sessions and history remain intact.
            jdbc.update("update provider_connections set credential_scope=?,credential_observed_at=now(),last_model=null where user_id=? and profile=? and provider='nous'",
                ayla.auth.AuthService.hash("pending-nous-account:"+session),userId,profile);
            models.invalidate(profile);
        }
        return result;
    }
    public boolean loggedInForUsage(String userId){String profile=profile(userId);return profile!=null&&loggedIn(profile);}

    public boolean ready(String userId) { return status(userId).ready(); }
    public void requireOwnLogin(String userId) {
        String profile=scope.profile(userId,true);
        if(!loggedIn(profile))throw new HermesException(409,"Conecte sua própria conta Hermes para conversar.");
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
