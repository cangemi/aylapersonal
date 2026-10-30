package ayla.auth;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import ayla.whatsapp.WhatsappGateway;

/** Pending credentials are hashed before delivery; no network I/O holds a database transaction. */
@Service
public class PasswordRecoveryService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final WhatsappGateway whatsapp;
    private final Executor executor;
    private final BCryptPasswordEncoder passwords=new BCryptPasswordEncoder(12);
    private final SecureRandom random=new SecureRandom();
    public record Delivery(String userId,String registeredNumber,String password,String requestId) {
        @Override public String toString(){return "Delivery[redacted]";}
    }
    public PasswordRecoveryService(JdbcTemplate jdbc,PlatformTransactionManager transactionManager,WhatsappGateway whatsapp,
            @Qualifier("passwordRecoveryExecutor") Executor executor) {
        this.jdbc=jdbc;this.transaction=new TransactionTemplate(transactionManager);this.whatsapp=whatsapp;this.executor=executor;
    }
    public void request(String username,String address) {
        String normalized=AuthService.username(username);
        Delivery delivery=transaction.execute(status->{
            String bucket=AuthService.hash(address==null?"unknown":address);
            jdbc.queryForObject("select pg_advisory_xact_lock(hashtext(?))",Object.class,"recovery:"+bucket);
            jdbc.update("""
                    insert into auth_recovery_limits(bucket) values (?) on conflict(bucket) do update set
                    attempts=case when auth_recovery_limits.window_start<now()-interval '1 hour' then 1 else least(auth_recovery_limits.attempts+1,6) end,
                    window_start=case when auth_recovery_limits.window_start<now()-interval '1 hour' then now() else auth_recovery_limits.window_start end
                    """,bucket);
            if(jdbc.queryForObject("select attempts from auth_recovery_limits where bucket=?",Integer.class,bucket)>5)return null;
            byte[] bytes=new byte[18];random.nextBytes(bytes);
            String password=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),encoded=passwords.encode(password);
            // Hashing also runs for unknown logins; responses never disclose account/phone existence.
            var rows=jdbc.queryForList("""
                    select a.user_id,a.recovery_phone from auth_accounts a where a.username=? and a.enabled and a.recovery_phone is not null
                    and (a.recovery_requested_at is null or a.recovery_requested_at<now()-interval '5 minutes')
                    and exists(select 1 from whatsapp_instances i where i.owner_user_id=a.user_id and i.connection_role='user' and i.state='ready' and i.connected_number=a.recovery_phone)
                    for update of a
                    """,normalized);
            if(rows.isEmpty())return null;
            String user=(String)rows.getFirst().get("user_id");
            // Recheck cooldown after the account lock when requests arrive from different addresses.
            int changed=jdbc.update("""
                    update auth_accounts set recovery_password_hash=?,recovery_expires_at=now()+interval '15 minutes',recovery_requested_at=now()
                    where user_id=? and (recovery_requested_at is null or recovery_requested_at<now()-interval '5 minutes')
                    """,encoded,user);
            return changed==0?null:new Delivery(user,(String)rows.getFirst().get("recovery_phone"),password,UUID.randomUUID().toString());
        });
        if(delivery==null)return;
        try {executor.execute(()->deliver(delivery));}
        catch(RuntimeException failure) {org.slf4j.LoggerFactory.getLogger(getClass()).warn("Entrega de recuperação indisponível; senha atual preservada. tipo={}",failure.getClass().getSimpleName());}
    }
    private void deliver(Delivery delivery) {
        try {
            Object result=whatsapp.sendRecovery(delivery.userId(),delivery.registeredNumber(),delivery.password(),delivery.requestId());
            if(result instanceof Map<?,?> sent&&Boolean.TRUE.equals(sent.get("sent")))transaction.execute(status->{
                var accounts=jdbc.queryForList("select username,recovery_password_hash,recovery_expires_at from auth_accounts where user_id=? and enabled for update",delivery.userId());
                if(!accounts.isEmpty()){
                    var account=accounts.getFirst();
                    if(account.get("recovery_password_hash") instanceof String encoded
                            && account.get("recovery_expires_at") instanceof java.sql.Timestamp expires
                            && expires.toInstant().isAfter(java.time.Instant.now())&&passwords.matches(delivery.password(),encoded)){
                        LoginAttempts.reset(jdbc,delivery.userId());
                        jdbc.update("delete from auth_login_throttle where bucket=?","user:"+AuthService.hash((String)account.get("username")));
                    }
                }
                return true;
            });
        } catch(RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Entrega de recuperação não confirmada; senha atual preservada. tipo={}",failure.getClass().getSimpleName());
        }
    }
}
