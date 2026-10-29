package ayla.monitoring;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Provider-reported usage only: no prompts, responses, credentials or estimated billing. */
@Service
public class AiUsageService {
    private final NamedParameterJdbcTemplate jdbc;
    public AiUsageService(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }
    static Instant retentionStart(Instant now) { return now.atZone(ZoneOffset.UTC).minusMonths(2).toInstant(); }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=3600000,initialDelay=60000)
    public void purgeExpired() {
        try { deleteBefore(retentionStart(Instant.now())); }
        catch(RuntimeException error) { LoggerFactory.getLogger(getClass()).warn("Limpeza de uso da IA indisponível. tipo={}",error.getClass().getSimpleName()); }
    }
    int deleteBefore(Instant cutoff) {
        return jdbc.update("delete from ai_usage_events where occurred_at<:cutoff",Map.of("cutoff",java.sql.Timestamp.from(cutoff)));
    }

    static Long tokens(Object value) {
        if (!(value instanceof Number number)) return null;
        try {
            long result=new BigDecimal(number.toString()).longValueExact();
            return result>=0 ? result : null;
        } catch (NumberFormatException|ArithmeticException error) { return null; }
    }
    private static String modelId(Object value) {
        return value instanceof String text && text.matches("[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,159}")
                && !text.contains("://") ? text : null;
    }
    private static Map<?,?> map(Object value) { return value instanceof Map<?,?> m ? m : Map.of(); }

    public void record(String profile,Map<String,Object> request,Map<String,Object> response,int status,long durationMs) {
        try {
            var result=response==null?Map.<String,Object>of():response;
            var usage=map(result.get("usage"));
            var runtime=map(result.get("runtime"));
            if(runtime.isEmpty()) runtime=map(usage.get("runtime"));
            Long input=tokens(usage.containsKey("input_tokens")?usage.get("input_tokens"):usage.get("prompt_tokens"));
            Long output=tokens(usage.containsKey("output_tokens")?usage.get("output_tokens"):usage.get("completion_tokens"));
            Long total=tokens(usage.get("total_tokens"));
            if(total==null && input!=null && output!=null) {
                try {total=Math.addExact(input,output);} catch(ArithmeticException ignored) { /* Unknown, never overflow. */ }
            }
            var users=jdbc.queryForList("select user_id from chat_users where hermes_profile=:profile",Map.of("profile",profile),String.class);
            String user=users.size()==1?users.getFirst():null;
            var auth=SecurityContextHolder.getContext().getAuthentication();
            String source=auth!=null && auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_WHATSAPP"))?"WHATSAPP":"WEB";
            var p=new MapSqlParameterSource().addValue("id",UUID.randomUUID()).addValue("time",java.sql.Timestamp.from(Instant.now()))
                    .addValue("user",user).addValue("source",source)
                    .addValue("provider",modelId(runtime.get("provider"))).addValue("model",modelId(runtime.get("model")))
                    .addValue("purpose",request.get("message") instanceof List<?>?"VISION":"CHAT")
                    .addValue("status",status).addValue("duration",Math.max(0,durationMs))
                    .addValue("input",input).addValue("output",output).addValue("total",total);
            jdbc.update("""
                    insert into ai_usage_events(id,occurred_at,user_id,source,provider,model,purpose,status,duration_ms,input_tokens,output_tokens,total_tokens)
                    values(:id,:time,:user,:source,:provider,:model,:purpose,:status,:duration,:input,:output,:total)
                    """,p);
        } catch (RuntimeException error) {
            // A metrics failure must never cause a repeated financial action or inference.
            LoggerFactory.getLogger(getClass()).warn("Não foi possível salvar uso da IA. tipo={}",error.getClass().getSimpleName());
        }
    }

    private record Filter(String sql,Map<String,Object> params) {}
    private Filter filter(Instant from,Instant to,String userId,String source) {
        Instant now=Instant.now(),until=to==null?now:to,since=from==null?until.minus(Duration.ofDays(1)):from;
        if(since.isAfter(until)||Duration.between(since,until).compareTo(Duration.ofDays(63))>0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Consulte até dois meses de histórico de uso da IA.");
        Instant cutoff=retentionStart(now);
        if(since.isBefore(cutoff)) since=cutoff;
        var p=new HashMap<String,Object>();p.put("from",java.sql.Timestamp.from(since));p.put("to",java.sql.Timestamp.from(until));
        var where=new StringBuilder(" e.occurred_at>=:from and e.occurred_at<=:to");
        if(userId!=null&&!userId.isBlank()){where.append(" and e.user_id=:user");p.put("user",userId);}
        if(source!=null&&!source.isBlank()) {
            if(!Set.of("WEB","WHATSAPP","HERMES","ANONYMOUS").contains(source))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Origem inválida.");
            where.append(" and e.source=:source");p.put("source",source);
        }
        return new Filter(where.toString(),p);
    }
    public Map<String,Object> overview(Instant from,Instant to,String userId,String source) {
        var filter=filter(from,to,userId,source);
        String where=filter.sql();var p=filter.params();
        String counters="""
                count(*) as calls,count(*) filter(where e.status>=400) as failures,
                count(*) filter(where e.total_tokens is null) as unreported_calls,
                count(*) filter(where e.input_tokens is null or e.output_tokens is null) as incomplete_calls,
                coalesce(sum(e.input_tokens),0) as input_tokens,coalesce(sum(e.output_tokens),0) as output_tokens,
                coalesce(sum(e.total_tokens),0) as total_tokens
                """;
        String table=" from ai_usage_events e ";
        var totals=jdbc.queryForMap("select "+counters+table+" where "+where,p);
        var byUser=jdbc.queryForList("select e.user_id,coalesce(a.display_name,a.username,'Usuário não identificado') as user_name,"
                +counters+table+" left join auth_accounts a on a.user_id=e.user_id where "+where
                +" group by e.user_id,a.display_name,a.username order by total_tokens desc,calls desc limit 50",p);
        var byModel=jdbc.queryForList("select e.provider,e.model,"+counters+table+" where "+where
                +" group by e.provider,e.model order by total_tokens desc,calls desc limit 50",p);
        var daily=jdbc.queryForList("select (e.occurred_at at time zone 'America/Sao_Paulo')::date as day,"+counters+table
                +" where "+where+" group by day order by day",p);
        return Map.of("totals",totals,"byUser",byUser,"byModel",byModel,"daily",daily);
    }
    public Map<String,Object> calls(Instant from,Instant to,String userId,String source,int page,int size) {
        if(page<0||page>10000||size<1||size>100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Paginação inválida.");
        var filter=filter(from,to,userId,source);var p=filter.params();p.put("limit",size);p.put("offset",page*size);
        var rows=jdbc.queryForList("""
                select e.id,e.occurred_at,e.user_id,coalesce(a.display_name,a.username,'Usuário não identificado') as user_name,
                       e.source,e.provider,e.model,e.purpose,e.status,e.duration_ms,e.input_tokens,e.output_tokens,e.total_tokens
                from ai_usage_events e left join auth_accounts a on a.user_id=e.user_id where
                """+filter.sql()+" order by e.occurred_at desc,e.id limit :limit offset :offset",p);
        Long total=jdbc.queryForObject("select count(*) from ai_usage_events e where "+filter.sql(),p,Long.class);
        return Map.of("items",rows,"total",total==null?0:total,"page",page,"size",size);
    }
}
