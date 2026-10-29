package ayla.auth;

import static ayla.auth.AuthData.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ayla.chat.session.ChatUserRepository;

@Service
public class AuthService {
    private static final long ACCESS_SECONDS=900,REFRESH_SECONDS=30L*86400;
    private final JdbcTemplate jdbc;
    private final UserProfileService profiles;
    private final ChatUserRepository users;
    private final String ownerId;
    private final byte[] setupKey;
    private final BCryptPasswordEncoder passwords=new BCryptPasswordEncoder(12);
    private final SecureRandom random=new SecureRandom();
    private final String dummyHash=passwords.encode("dummy-password-not-a-login");
    private final ayla.hermes.HermesAccountService hermes;
    private final ayla.whatsapp.WhatsappGateway whatsapp;
    public AuthService(JdbcTemplate jdbc,ChatUserRepository users,UserProfileService profiles,@org.springframework.context.annotation.Lazy ayla.hermes.HermesAccountService hermes,ayla.whatsapp.WhatsappGateway whatsapp,@Value("${ayla.chat.owner-id:local-owner}") String ownerId,
                       @Value("${ayla.auth.setup-key:}") String setupKey) {
        this.hermes=hermes;this.whatsapp=whatsapp;
        this.jdbc=jdbc;this.users=users;this.profiles=profiles;this.ownerId=ownerId;this.setupKey=setupKey.getBytes(StandardCharsets.UTF_8);
    }
    private static final RowMapper<Identity> IDENTITY=(rs,n)->new Identity(rs.getString("user_id"),rs.getString("username"),rs.getString("role"),rs.getObject("id",UUID.class));
    @Transactional
    public User setup(String key,Credentials input) {
        if(setupKey.length<32 || key==null || !MessageDigest.isEqual(setupKey,key.getBytes(StandardCharsets.UTF_8)))
            throw new AuthException(403,"Chave de configuração inicial inválida.");
        jdbc.queryForObject("select pg_advisory_xact_lock(16349281)",Object.class);
        if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from auth_accounts where role='OWNER')",Boolean.class)))
            throw new AuthException(409,"A conta principal já foi configurada.");
        return create(ownerId,input,"OWNER");
    }
    @Transactional
    public User createUser(Credentials input) {return create(UUID.randomUUID().toString(),input,"USER");}
    public void requireOwner(Identity actor) {
        if(actor==null||!"OWNER".equals(actor.role())||!ownerId.equals(actor.userId()))
            throw new AuthException(403,"Ação exclusiva do superadministrador.");
    }
    public UserPage users(Identity actor,String search,int page,int size) {
        requireOwner(actor);
        if(page<0||page>10000||size<1||size>100||search==null||search.length()>120)
            throw new AuthException(400,"Busca ou paginação inválida.");
        String text=search.strip(),pattern="%"+text.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
        String where=" where (?='' or username ilike ? or display_name ilike ?)";
        var items=jdbc.query("select user_id,username,display_name,role,enabled,created_at from auth_accounts"+where
                +" order by case when role='OWNER' then 0 else 1 end,username,user_id limit ? offset ?",
                (rs,n)->new ManagedUser(rs.getString("user_id"),rs.getString("username"),rs.getString("display_name"),rs.getString("role"),rs.getBoolean("enabled"),rs.getTimestamp("created_at").toInstant()),text,pattern,pattern,size,page*size);
        long total=jdbc.queryForObject("select count(*) from auth_accounts"+where,Long.class,text,pattern,pattern);
        return new UserPage(items,total,page,size);
    }
    @Transactional
    public void resetPassword(Identity actor,String targetId,AdminPasswordReset input) {
        requireOwner(actor);newPassword(input.newPassword());
        if(targetId==null||targetId.isBlank()||targetId.length()>255)throw new AuthException(400,"Usuário inválido.");
        var rows=jdbc.queryForList("select username from auth_accounts where user_id=? for update",String.class,targetId);
        if(rows.isEmpty())throw new AuthException(404,"Usuário não encontrado.");
        jdbc.update("update auth_accounts set password_hash=?,recovery_password_hash=null,recovery_expires_at=null,updated_at=now() where user_id=?",passwords.encode(input.newPassword()),targetId);
        LoginAttempts.reset(jdbc,targetId);
        jdbc.update("update auth_sessions set revoked_at=now() where user_id=? and revoked_at is null",targetId);
        jdbc.update("delete from auth_login_throttle where bucket=?","user:"+hash(rows.getFirst()));
    }
    @Transactional
    public void deleteUser(Identity actor,String targetId) {
        requireOwner(actor);
        if(targetId==null||targetId.isBlank()||targetId.length()>255)throw new AuthException(400,"Usuário inválido.");
        var rows=jdbc.queryForList("select username,role from auth_accounts where user_id=? for update",targetId);
        if(rows.isEmpty())throw new AuthException(404,"Usuário não encontrado.");
        if("OWNER".equals(rows.getFirst().get("role"))||ownerId.equals(targetId))throw new AuthException(403,"O superadministrador não pode ser excluído.");
        String instances="select id from whatsapp_instances where owner_user_id=?";
        hermes.delete(targetId);
        try{whatsapp.post("/logout",targetId);}catch(RuntimeException ignored){}
        jdbc.update("delete from auth_refresh_tokens where session_id in (select id from auth_sessions where user_id=?)",targetId);
        jdbc.update("delete from auth_sessions where user_id=?",targetId);
        jdbc.update("delete from auth_accounts where user_id=?",targetId);
        jdbc.update("delete from whatsapp_messages where instance_id in ("+instances+")",targetId);
        jdbc.update("delete from whatsapp_chats where instance_id in ("+instances+")",targetId);
        jdbc.update("delete from whatsapp_sessions where instance_id in ("+instances+")",targetId);
        jdbc.update("delete from whatsapp_instances where owner_user_id=?",targetId);
        for(String table:List.of("finance_chat_drafts","finance_chat_messages","finance_agent_turns","personal_memories","pantry_items","shopping_items",
                "household_receipts","financial_entries","financial_receipts","investment_settings"))
            jdbc.update("delete from "+table+" where user_id=?",targetId);
        jdbc.update("update chat_users set current_conversation_id=null where user_id=?",targetId);
        jdbc.update("delete from chat_conversations where user_id=?",targetId);
        jdbc.update("delete from chat_users where user_id=?",targetId);
        jdbc.update("delete from auth_login_throttle where bucket=?","user:"+hash((String)rows.getFirst().get("username")));
    }
    @Transactional
    public void setEnabled(Identity actor,String targetId,boolean enabled) {
        requireOwner(actor);
        var rows=jdbc.queryForList("select role from auth_accounts where user_id=? for update",String.class,targetId);
        if(rows.isEmpty())throw new AuthException(404,"Usuário não encontrado.");
        if("OWNER".equals(rows.getFirst())||ownerId.equals(targetId))throw new AuthException(403,"O superadministrador não pode ser inativado.");
        jdbc.update("update auth_accounts set enabled=?,updated_at=now() where user_id=?",enabled,targetId);
        if(!enabled) {
            jdbc.update("update whatsapp_instances set desired_connected=false,updated_at=now() where owner_user_id=?",targetId);
            jdbc.update("update auth_sessions set revoked_at=now() where user_id=? and revoked_at is null",targetId);
            jdbc.update("update auth_accounts set recovery_password_hash=null,recovery_expires_at=null where user_id=?",targetId);
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
                @Override public void afterCommit(){try{whatsapp.post("/disconnect",targetId);}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(AuthService.class).warn("Conta inativada; desconexão WhatsApp pendente. tipo={}",e.getClass().getSimpleName());}}
            });
        }
    }
    public void requireActiveAgentSession(String session) {
        if(session==null||session.isBlank())throw new AuthException(403,"Sessão da conversa inválida.");
        var owners=jdbc.queryForList("select c.user_id from chat_conversations c join auth_accounts a on a.user_id=c.user_id where c.hermes_session_id=? and a.enabled and c.deleted_at is null",String.class,session);
        if(owners.size()!=1)throw new AuthException(403,"Usuário ou sessão sem acesso às ferramentas.");
        ayla.monitoring.MonitoringContext.user(owners.getFirst());
    }
    private User create(String id,Credentials input,String role) {
        String username=username(input.username());newPassword(input.password());
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtext(?))",Object.class,"auth-name:"+username);
        if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from auth_accounts where username=?)",Boolean.class,username)))
            throw new AuthException(409,"Este login já está em uso.");
        users.ensureUser(id,id.equals(ownerId)?"default":null);
        jdbc.update("insert into auth_accounts(user_id,username,password_hash,role,onboarded,password_temporary) values (?,?,?,?,false,?)",id,username,passwords.encode(input.password()),role,"USER".equals(role));
        bindConnectedNumber(id,false);
        return view(id,username,role);
    }
    @Transactional(noRollbackFor=AuthException.class)
    public Tokens login(Credentials input,String address) {
        String username=username(input.username());
        String accountBucket="user:"+hash(username),ipBucket="ip:"+hash(address==null?"unknown":address);
        // Consistent lock order avoids races in both the user and remote-address counters.
        lockBucket(ipBucket);lockBucket(accountBucket);
        var rows=jdbc.queryForList("select * from auth_accounts where username=? for update",username);
        var row=rows.isEmpty()?null:rows.getFirst();
        boolean pending=row!=null && row.get("recovery_password_hash")!=null && row.get("recovery_expires_at")!=null
                && ((java.sql.Timestamp)row.get("recovery_expires_at")).toInstant().isAfter(Instant.now());
        if(!pending)LoginAttempts.check(row);
        String password=input.password();boolean reasonable=password!=null&&password.getBytes(StandardCharsets.UTF_8).length<=72;
        boolean matches=passwords.matches(reasonable?password:"invalid-password",row==null?dummyHash:(String)row.get("password_hash"));
        boolean recovered=passwords.matches(reasonable?password:"invalid-password",pending?(String)row.get("recovery_password_hash"):dummyHash)&&pending&&reasonable;
        if(!recovered)LoginAttempts.check(row);
        if(row==null)checkThrottle(accountBucket);
        if(row==null || !reasonable || (!matches&&!recovered) || !Boolean.TRUE.equals(row.get("enabled"))) {
            checkThrottle(ipBucket);
            failure(ipBucket,30);
            if(row==null)failure(accountBucket,8);
            else if(Boolean.TRUE.equals(row.get("enabled")))LoginAttempts.failed(jdbc,row);
            throw new AuthException(401,"Login ou senha inválidos.");
        }
        jdbc.update("delete from auth_login_throttle where bucket=?",accountBucket);
        LoginAttempts.reset(jdbc,(String)row.get("user_id"));
        if(recovered) {
            jdbc.update("update auth_accounts set password_hash=recovery_password_hash,recovery_password_hash=null,recovery_expires_at=null,updated_at=now() where user_id=?",row.get("user_id"));
            jdbc.update("update auth_sessions set revoked_at=now() where user_id=? and revoked_at is null",row.get("user_id"));
        }
        User user=new User((String)row.get("user_id"),username,(String)row.get("role"));
        UUID session=UUID.randomUUID();Instant until=Instant.now().plusSeconds(REFRESH_SECONDS);
        return issue(user,session,until,true);
    }
    private Tokens issue(User user,UUID session,Instant refreshUntil,boolean create) {
        user=view(user.id(),user.username(),user.role());
        String access=token("ayla_at_"),refresh=token("ayla_rt_");Instant now=Instant.now(),accessUntil=now.plusSeconds(ACCESS_SECONDS);
        if(accessUntil.isAfter(refreshUntil))accessUntil=refreshUntil;
        if(create) jdbc.update("insert into auth_sessions(id,user_id,access_hash,access_expires_at,refresh_expires_at) values (?,?,?,?,?)",
                session,user.id(),hash(access),java.sql.Timestamp.from(accessUntil),java.sql.Timestamp.from(refreshUntil));
        else jdbc.update("update auth_sessions set access_hash=?,access_expires_at=? where id=?",hash(access),java.sql.Timestamp.from(accessUntil),session);
        jdbc.update("insert into auth_refresh_tokens(token_hash,session_id,expires_at) values (?,?,?)",hash(refresh),session,java.sql.Timestamp.from(refreshUntil));
        return new Tokens(access,refresh,"Bearer",Math.max(0,Duration.between(now,accessUntil).getSeconds()),accessUntil,refreshUntil,user);
    }
    @Transactional(noRollbackFor=AuthException.class)
    public Tokens refresh(String raw) {
        if(raw==null || !raw.matches("ayla_rt_[A-Za-z0-9_-]{43}"))throw new AuthException(401,"Sessão inválida ou expirada. Entre novamente.");
        var rows=jdbc.queryForList("""
                select s.id,s.user_id,a.username,a.role,a.enabled,s.revoked_at,s.refresh_expires_at,t.consumed_at
                from auth_refresh_tokens t join auth_sessions s on s.id=t.session_id join auth_accounts a on a.user_id=s.user_id
                where t.token_hash=? for update of s,t
                """,hash(raw));
        if(rows.isEmpty())throw new AuthException(401,"Sessão inválida ou expirada. Entre novamente.");
        var row=rows.getFirst();UUID session=(UUID)row.get("id");
        Instant until=((java.sql.Timestamp)row.get("refresh_expires_at")).toInstant();
        if(row.get("consumed_at")!=null || row.get("revoked_at")!=null || !Boolean.TRUE.equals(row.get("enabled")) || !until.isAfter(Instant.now())) {
            jdbc.update("update auth_sessions set revoked_at=coalesce(revoked_at,now()) where id=?",session);
            throw new AuthException(401,"Sessão inválida ou expirada. Entre novamente.");
        }
        jdbc.update("update auth_refresh_tokens set consumed_at=now() where token_hash=?",hash(raw));
        return issue(new User((String)row.get("user_id"),(String)row.get("username"),(String)row.get("role")),session,until,false);
    }
    public Identity authenticate(String token) {
        if(token==null || !token.matches("ayla_at_[A-Za-z0-9_-]{43}"))throw new AuthException(401,"Token de acesso inválido ou expirado.");
        return jdbc.query("""
                select s.id,s.user_id,a.username,a.role from auth_sessions s join auth_accounts a on a.user_id=s.user_id
                where s.access_hash=? and s.revoked_at is null and s.access_expires_at>now() and s.refresh_expires_at>now() and a.enabled
                """,IDENTITY,hash(token)).stream().findFirst().orElseThrow(()->new AuthException(401,"Token de acesso inválido ou expirado."));
    }
    public User me(Identity identity) {return view(identity.userId(),identity.username(),identity.role());}
    private User view(String id,String username,String role) {
        var flags=jdbc.query("select onboarded,password_temporary from auth_accounts where user_id=?",
                (rs,n)->new boolean[]{!rs.getBoolean("onboarded"),rs.getBoolean("password_temporary")},id).stream().findFirst().orElse(new boolean[]{false,false});
        return new User(id,username,role,profiles.name(id),flags[0],flags[1]);
    }
    @Transactional
    public User completeOnboarding(Identity identity) {
        if(profiles.name(identity.userId())==null)throw new AuthException(409,"Informe seu nome para continuar.");
        if(Boolean.TRUE.equals(jdbc.queryForObject("select password_temporary from auth_accounts where user_id=?",Boolean.class,identity.userId())))
        throw new AuthException(409,"Troque a senha provisória para continuar.");
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from whatsapp_instances where owner_user_id=? and state='ready')",Boolean.class,identity.userId())))
            throw new AuthException(409,"Conecte o WhatsApp para continuar.");
        boolean hermesReady;
        try{hermesReady=hermes.ready(identity.userId());}catch(RuntimeException e){hermesReady=false;}
        if(!hermesReady)throw new AuthException(409,"Conclua a configuração do Hermes para continuar.");
        jdbc.update("update auth_accounts set onboarded=true,updated_at=now() where user_id=?",identity.userId());
        return me(identity);
    }
    public User updateProfile(Identity identity,Profile input) {
        profiles.save(identity.userId(),input.displayName());
        return me(identity);
    }
    @Transactional
    public void logout(Identity identity,boolean all) {
        if(all)jdbc.update("update auth_sessions set revoked_at=now() where user_id=? and revoked_at is null",identity.userId());
        else jdbc.update("update auth_sessions set revoked_at=coalesce(revoked_at,now()) where id=? and user_id=?",identity.sessionId(),identity.userId());
    }
    @Transactional
    public void changePassword(Identity identity,PasswordChange input) {
        var hashes=jdbc.queryForList("select password_hash from auth_accounts where user_id=? for update",String.class,identity.userId());
        String current=input.currentPassword();
        if(hashes.isEmpty() || current.getBytes(StandardCharsets.UTF_8).length>72 || !passwords.matches(current,hashes.getFirst()))
            throw new AuthException(401,"Senha atual inválida.");
        newPassword(input.newPassword());
        jdbc.update("update auth_accounts set password_hash=?,password_temporary=false,recovery_password_hash=null,recovery_expires_at=null,updated_at=now() where user_id=?",passwords.encode(input.newPassword()),identity.userId());
        LoginAttempts.reset(jdbc,identity.userId());
        jdbc.update("delete from auth_login_throttle where bucket=?","user:"+hash(identity.username()));
        logout(identity,true);
    }
    @Transactional
    public Map<String,String> bindRecoveryChannel(Identity identity,RecoveryChannel input) {
        String encoded=jdbc.queryForObject("select password_hash from auth_accounts where user_id=? for update",String.class,identity.userId());
        if(input.currentPassword().getBytes(StandardCharsets.UTF_8).length>72 || !passwords.matches(input.currentPassword(),encoded))throw new AuthException(401,"Senha atual inválida.");
        String phone=bindConnectedNumber(identity.userId(),true);
        return Map.of("maskedNumber","***"+phone.substring(Math.max(0,phone.length()-4)));
    }
    private String bindConnectedNumber(String user,boolean required) {
        var phones=jdbc.queryForList("select connected_number from whatsapp_instances where owner_user_id=? and state='ready' and connected_number is not null order by updated_at desc limit 1",String.class,user);
        if(phones.isEmpty()) {if(required)throw new AuthException(409,"Conecte o WhatsApp deste usuário antes de cadastrar a recuperação.");return null;}
        String phone=phones.getFirst();
        jdbc.update("update auth_accounts set recovery_phone=?,recovery_password_hash=null,recovery_expires_at=null where user_id=?",phone,user);
        return phone;
    }
    private void lockBucket(String bucket) {jdbc.queryForObject("select pg_advisory_xact_lock(hashtext(?))",Object.class,bucket);}
    private void checkThrottle(String bucket) {
        if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from auth_login_throttle where bucket=? and blocked_until>now())",Boolean.class,bucket)))
            throw new AuthException(429,"Muitas tentativas. Aguarde quinze minutos para tentar novamente.");
    }
    private void failure(String bucket,int limit) {
        jdbc.update("""
                insert into auth_login_throttle(bucket,failures) values (?,1)
                on conflict(bucket) do update set failures=case when auth_login_throttle.window_start<now()-interval '15 minutes' then 1 else auth_login_throttle.failures+1 end,
                  window_start=case when auth_login_throttle.window_start<now()-interval '15 minutes' then now() else auth_login_throttle.window_start end,
                  blocked_until=null
                """,bucket);
        jdbc.update("update auth_login_throttle set blocked_until=now()+interval '15 minutes' where bucket=? and failures>=?",bucket,limit);
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=3600000,initialDelay=3600000)
    @Transactional
    public void cleanupExpired() {
        jdbc.update("delete from auth_refresh_tokens where expires_at<now()");
        jdbc.update("delete from auth_sessions where refresh_expires_at<now()");
        jdbc.update("delete from auth_login_throttle where window_start<now()-interval '1 day' and (blocked_until is null or blocked_until<now())");
        jdbc.update("delete from auth_recovery_limits where window_start<now()-interval '1 day'");
        jdbc.update("update auth_accounts set recovery_password_hash=null,recovery_expires_at=null where recovery_expires_at<now()");
    }
    static String username(String value) {
        String normalized=Normalizer.normalize(value==null?"":value,Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT);
        if(!normalized.matches("[a-z0-9][a-z0-9._@+\\-]{2,119}"))throw new AuthException(400,"Use um login de 3 a 120 caracteres: letras, números, ponto, hífen, sublinhado ou e-mail.");
        return normalized;
    }
    private static void newPassword(String value) {
        if(value==null || value.isBlank() || value.codePointCount(0,value.length())<12 || value.getBytes(StandardCharsets.UTF_8).length>72)
            throw new AuthException(400,"Use uma senha com pelo menos 12 caracteres e até 72 bytes em UTF-8.");
    }
    private String token(String prefix) {byte[] bytes=new byte[32];random.nextBytes(bytes);return prefix+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    public static String hash(String token) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
