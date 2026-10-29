package ayla.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** Called while the account row is locked in the caller's transaction. */
final class LoginAttempts {
    private LoginAttempts() {}
    static void check(Map<String,Object> account) {
        if(account!=null && account.get("login_locked_until") instanceof Timestamp timestamp
                && timestamp.toInstant().isAfter(Instant.now()))throw blocked(timestamp.toInstant());
    }
    static void failed(JdbcTemplate jdbc,Map<String,Object> account) {
        int previous=((Number)account.get("login_failed_attempts")).intValue();
        int attempts=previous==Integer.MAX_VALUE?3:previous+1;
        int level=((Number)account.get("login_lock_level")).intValue();
        Instant blockedUntil=null;
        if(attempts%3==0){blockedUntil=Instant.now().plusSeconds(level==0?1800:3600);level=Math.min(2,level+1);}
        jdbc.update("update auth_accounts set login_failed_attempts=?,login_lock_level=?,login_locked_until=?,last_login_failure_at=now(),updated_at=now() where user_id=?",
                attempts,level,blockedUntil==null?null:Timestamp.from(blockedUntil),account.get("user_id"));
        if(blockedUntil!=null)throw blocked(blockedUntil);
    }
    static void reset(JdbcTemplate jdbc,String user) {
        jdbc.update("update auth_accounts set login_failed_attempts=0,login_lock_level=0,login_locked_until=null,last_login_failure_at=null where user_id=?",user);
    }
    private static AuthException blocked(Instant until) {
        long minutes=Math.max(1,(until.toEpochMilli()-Instant.now().toEpochMilli()+59999)/60000);
        return new AuthException(429,"Acesso temporariamente bloqueado. Tente novamente em "+minutes+" minuto(s) ou redefina sua senha para liberar o acesso.");
    }
}
