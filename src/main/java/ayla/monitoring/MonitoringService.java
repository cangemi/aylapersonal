package ayla.monitoring;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class MonitoringService {
    private final NamedParameterJdbcTemplate jdbc;
    public MonitoringService(NamedParameterJdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Event(UUID id,Instant occurredAt,String userId,String source,String method,String route,String action,int status,long durationMs,String errorType,String errorReason,String stackSummary) {}
    public void record(Event e) {
        var p=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("id",e.id()).addValue("time",java.sql.Timestamp.from(e.occurredAt())).addValue("user",e.userId())
                .addValue("source",e.source()).addValue("method",e.method()).addValue("route",e.route()).addValue("action",e.action())
                .addValue("status",e.status()).addValue("duration",e.durationMs()).addValue("type",e.errorType()).addValue("reason",e.errorReason()).addValue("stack",e.stackSummary());
        jdbc.update("""
                insert into application_events(id,occurred_at,user_id,source,method,route,action,status,duration_ms,error_type,error_reason,stack_summary)
                values(:id,:time,:user,:source,:method,:route,:action,:status,:duration,:type,:reason,:stack)
                """,p);
    }
    public record Query(Instant from,Instant to,String userId,String source,String result,String q,int page,int size) {}
    private record Filter(String sql,Map<String,Object> params) {}
    private Filter filter(Query q) {
        Instant until=q.to()==null?Instant.now():q.to(),since=q.from()==null?until.minus(Duration.ofDays(1)):q.from();
        if(since.isAfter(until)||Duration.between(since,until).compareTo(Duration.ofDays(31))>0||q.page()<0||q.page()>10000||q.size()<1||q.size()>100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Informe um período de até 31 dias e paginação válida.");
        var p=new HashMap<String,Object>();p.put("from",java.sql.Timestamp.from(since));p.put("to",java.sql.Timestamp.from(until));
        var sql=new StringBuilder(" e.occurred_at>=:from and e.occurred_at<=:to");
        if(q.userId()!=null&&!q.userId().isBlank()){p.put("user",q.userId());sql.append(" and e.user_id=:user");}
        if(q.source()!=null&&!q.source().isBlank()){
            if(!Set.of("WEB","WHATSAPP","HERMES","ANONYMOUS").contains(q.source()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Origem inválida.");
            p.put("source",q.source());sql.append(" and e.source=:source");
        }
        sql.append(switch(q.result()==null?"ALL":q.result()){
            case "ALL",""->"";case "SUCCESS"->" and e.status<400";case "ERROR"->" and e.status>=400";
            case "CLIENT_ERROR"->" and e.status between 400 and 499";case "SERVER_ERROR"->" and e.status>=500";
            default->throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Resultado inválido.");});
        if(q.q()!=null&&!q.q().isBlank()){
            if(q.q().length()>120)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Busca muito longa.");
            p.put("q","%"+q.q().replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%");
            sql.append(" and (e.action ilike :q or e.route ilike :q or e.error_type ilike :q)");
        }
        return new Filter(sql.toString(),p);
    }
    public Map<String,Object> overview(Query q) {
        var f=filter(q);
        var totals=jdbc.queryForMap("""
                select count(*) as total,count(*) filter(where e.status>=400) as errors,
                count(*) filter(where e.status>=500) as server_errors,count(distinct e.user_id) as active_users,
                coalesce(round(avg(e.duration_ms) filter(where e.method<>'EVENT')),0) as average_ms,
                coalesce(round((percentile_cont(0.95) within group(order by e.duration_ms) filter(where e.method<>'EVENT'))::numeric),0) as p95_ms
                from application_events e where
                """+f.sql(),f.params());
        var failures=jdbc.queryForList("select e.action,e.route,e.method,count(*) as failures,max(e.occurred_at) as last_seen from application_events e where "+f.sql()
                +" and e.status>=400 group by e.action,e.route,e.method order by failures desc,last_seen desc limit 5",f.params());
        return Map.of("totals",totals,"failures",failures);
    }
    private static final String VIEW="""
            select e.id,e.occurred_at,e.user_id,coalesce(a.display_name,a.username,'Integração / não autenticado') as user_name,
            a.username,e.source,e.method,e.route,e.action,e.status,e.duration_ms,e.error_type,e.error_reason
            from application_events e left join auth_accounts a on a.user_id=e.user_id
            """;
    public Map<String,Object> events(Query q) {
        var f=filter(q);f.params().put("limit",q.size());f.params().put("offset",q.page()*q.size());
        var rows=jdbc.queryForList(VIEW+" where "+f.sql()+" order by e.occurred_at desc,e.id limit :limit offset :offset",f.params());
        var total=jdbc.queryForObject("select count(*) from application_events e where "+f.sql(),f.params(),Long.class);
        return Map.of("items",rows,"total",total,"page",q.page(),"size",q.size());
    }
    public Map<String,Object> detail(UUID id) {
        var rows=jdbc.queryForList(VIEW.replace("e.error_reason","e.error_reason,e.stack_summary")+" where e.id=:id",Map.of("id",id));
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Evento não encontrado.");
        return rows.getFirst();
    }
    public List<Map<String,Object>> users() {
        return jdbc.queryForList("select user_id as id,coalesce(display_name,username) as name,username from auth_accounts order by name",Map.of());
    }
}
