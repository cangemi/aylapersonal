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
    public record Event(UUID id,Instant occurredAt,String userId,String source,String method,String route,String action,int status,long durationMs,String errorType,String errorReason,String stackSummary,String requestKey) {
        public Event(UUID id,Instant occurredAt,String userId,String source,String method,String route,String action,int status,long durationMs,String errorType,String errorReason,String stackSummary){
            this(id,occurredAt,userId,source,method,route,action,status,durationMs,errorType,errorReason,stackSummary,null);
        }
    }
    private static final String KEY="[A-Za-z0-9_-]{1,128}";
    public void record(Event e) {
        String key=e.requestKey()!=null&&e.requestKey().matches(KEY)?e.requestKey():null;
        var p=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("id",e.id()).addValue("time",java.sql.Timestamp.from(e.occurredAt())).addValue("user",e.userId())
                .addValue("source",e.source()).addValue("method",e.method()).addValue("route",e.route()).addValue("action",e.action())
                .addValue("status",e.status()).addValue("duration",e.durationMs()).addValue("type",e.errorType()).addValue("reason",e.errorReason())
                .addValue("stack",e.stackSummary()).addValue("key",key);
        jdbc.update("""
                insert into application_events(id,occurred_at,user_id,source,method,route,action,status,duration_ms,error_type,error_reason,stack_summary,request_key)
                values(:id,:time,:user,:source,:method,:route,:action,:status,:duration,:type,:reason,:stack,:key)
                """,p);
    }
    public record Query(Instant from,Instant to,String userId,String source,String result,String q,int page,int size) {}
    private record Filter(String sql,Map<String,Object> params) {}
    private Filter filter(Query q){return filter(q,true);}
    private Filter filter(Query q,boolean withText) {
        Instant until=q.to()==null?Instant.now():q.to(),since=q.from()==null?until.minus(Duration.ofDays(1)):q.from();
        if(since.isAfter(until)||Duration.between(since,until).compareTo(Duration.ofDays(31))>0||q.page()<0||q.page()>10000||q.size()<1||q.size()>100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Informe um período de até 31 dias e paginação válida.");
        var p=new HashMap<String,Object>();p.put("from",java.sql.Timestamp.from(since));p.put("to",java.sql.Timestamp.from(until));
        var sql=new StringBuilder(" e.occurred_at>=:from and e.occurred_at<=:to and e.source <> 'HERMES'");
        if(q.userId()!=null&&!q.userId().isBlank()){p.put("user",q.userId());sql.append(" and e.user_id=:user");}
        if(q.source()!=null&&!q.source().isBlank()){
            if(!Set.of("WEB","WHATSAPP","HERMES","ANONYMOUS").contains(q.source()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Origem inválida.");
            p.put("source",q.source());sql.append(" and e.source=:source");
        }
        sql.append(switch(q.result()==null?"ALL":q.result()){
            case "ALL",""->"";case "SUCCESS"->" and e.status<400";case "ERROR"->" and e.status>=400";
            case "CLIENT_ERROR"->" and e.status between 400 and 499";case "SERVER_ERROR"->" and e.status>=500";
            default->throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Resultado inválido.");});
        if(withText&&q.q()!=null&&!q.q().isBlank()){
            p.put("q",text(q.q()));
            sql.append(" and (e.action ilike :q or e.route ilike :q or e.error_type ilike :q)");
        }
        return new Filter(sql.toString(),p);
    }
    static String text(String value){
        if(value.length()>120)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Busca muito longa.");
        return "%"+value.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
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
    /** Activity and AI usage in a single chronological feed, so one call to the Ayla appears in one place. */
    public Map<String,Object> feed(Query q) {
        var base=filter(q,false);var p=base.params();
        String apiWhere=base.sql(),iaWhere=base.sql();
        if(q.q()!=null&&!q.q().isBlank()){
            p.put("q",text(q.q()));
            apiWhere+=" and (e.action ilike :q or e.route ilike :q or e.error_type ilike :q)";
            iaWhere+=" and (coalesce(e.model,'') ilike :q or coalesce(e.provider,'') ilike :q or e.purpose ilike :q)";
        }
        p.put("limit",q.size());p.put("offset",q.page()*q.size());
        var rows=jdbc.queryForList("""
                select * from (
                  select e.id,e.occurred_at,e.user_id,
                         coalesce(a.display_name,a.username,'Integração / não autenticado') as user_name,a.username,e.source,
                         'api' as kind,e.method,e.route,e.action,e.status,e.duration_ms,e.error_type,e.error_reason,
                         e.request_key,null::varchar as model,null::varchar as provider,null::varchar as purpose,
                         null::bigint as input_tokens,null::bigint as output_tokens,null::bigint as total_tokens
                  from application_events e left join auth_accounts a on a.user_id=e.user_id where %s
                  union all
                  select e.id,e.occurred_at,e.user_id,
                         coalesce(a.display_name,a.username,'Usuário não identificado') as user_name,a.username,e.source,
                         'ia' as kind,null::varchar as method,'/hermes/chat' as route,
                         case when e.purpose='VISION' then 'Analisar imagem com a Ayla' else 'Conversar com a Ayla' end as action,
                         e.status,e.duration_ms,null::varchar as error_type,null::varchar as error_reason,
                         e.request_id as request_key,e.model,e.provider,e.purpose,e.input_tokens,e.output_tokens,e.total_tokens
                  from ai_usage_events e left join auth_accounts a on a.user_id=e.user_id where %s
                ) t order by t.occurred_at desc,t.id limit :limit offset :offset
                """.formatted(apiWhere,iaWhere),p);
        Long total=jdbc.queryForObject("""
                select (select count(*) from application_events e where %s)+(select count(*) from ai_usage_events e where %s)
                """.formatted(apiWhere,iaWhere),p,Long.class);
        return Map.of("items",rows,"total",total==null?0:total,"page",q.page(),"size",q.size());
    }
    /** Timeline of one call to the Ayla: received, queued, answered by the AI and delivered. */
    public Map<String,Object> trace(String reference) {
        if(reference==null||!reference.matches("[A-Za-z0-9_-]{1,128}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Referência inválida.");
        var ref=Map.of("ref",reference);
        String where=isUuid(reference)?"j.id=cast(:ref as uuid) or j.request_key=:ref":"j.request_key=:ref";
        var jobs=jdbc.queryForList("""
                select j.id,j.user_id,coalesce(a.display_name,a.username) as user_name,a.username,j.source,j.status,j.request_key,
                j.created_at,j.started_at,j.finished_at,j.delivered_at,j.attempts
                from chat_jobs j left join auth_accounts a on a.user_id=j.user_id
                where %s order by j.created_at desc limit 1
                """.formatted(where),ref);
        if(jobs.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Chamada não encontrada.");
        var job=jobs.getFirst();
        UUID id=(UUID)job.get("id");
        var ai=jdbc.queryForList("""
                select e.id,e.occurred_at,e.purpose,e.model,e.provider,e.status,e.duration_ms,e.input_tokens,e.output_tokens,e.total_tokens
                from ai_usage_events e where e.request_id=:id order by e.occurred_at
                """,Map.of("id",id.toString()));
        var related=new ArrayList<Map<String,Object>>(jdbc.queryForList("""
                select e.id,e.occurred_at,e.method,e.route,e.action,e.status,e.duration_ms,e.error_type,e.error_reason
                from application_events e where e.request_key=:key and e.id<>:id order by e.occurred_at
                """,Map.of("key",job.get("request_key"),"id",id)));
        if(job.get("user_id") instanceof String userId && job.get("created_at") instanceof java.sql.Timestamp created) {
            Object end=job.get("finished_at") instanceof java.sql.Timestamp x?x
                    :job.get("delivered_at") instanceof java.sql.Timestamp y?y
                    :job.get("started_at") instanceof java.sql.Timestamp z?z:created;
            long start=created.getTime(); long stop=(end instanceof java.sql.Timestamp ts?ts.getTime():created.getTime())+1000;
            if(stop<start)stop=start+1000;
            var ids=new HashSet<Object>(); for(var r:related)ids.add(r.get("id"));
            var window=jdbc.queryForList("""
                    select e.id,e.occurred_at,e.method,e.route,e.action,e.status,e.duration_ms,e.error_type,e.error_reason
                    from application_events e where e.user_id=:user and e.source='HERMES'
                    and e.occurred_at>=:start and e.occurred_at::timestamp<=:stop order by e.occurred_at
                    """,Map.of("user",userId,"start",new java.sql.Timestamp(start),"stop",new java.sql.Timestamp(stop)));
            for(var row:window) if(!ids.contains(row.get("id"))) related.add(row);
            related.sort(Comparator.comparing(o->Objects.toString(o.get("occurred_at"))));
        }
        Map<String,Object> result=new LinkedHashMap<>(job);
        result.put("ai_calls",ai);result.put("related",related);
        result.put("duration_ms",totalDuration(job));
        return result;
    }
    private static boolean isUuid(String value){
        try {UUID.fromString(value);return true;} catch(IllegalArgumentException invalid){return false;}
    }
    private Long totalDuration(Map<String,Object> job){
        Object created=job.get("created_at");Object end=job.get("delivered_at")!=null?job.get("delivered_at"):job.get("finished_at");
        if(!(created instanceof java.sql.Timestamp start))return null;
        java.sql.Timestamp stop=end instanceof java.sql.Timestamp value?value:new java.sql.Timestamp(Instant.now().toEpochMilli());
        return Math.max(0,stop.getTime()-start.getTime());
    }
    /** Recent calls to the Ayla with their queue and delivery status. */
    public Map<String,Object> traces(Query q) {
        Instant until=q.to()==null?Instant.now():q.to(),since=q.from()==null?until.minus(Duration.ofDays(1)):q.from();
        if(since.isAfter(until)||Duration.between(since,until).compareTo(Duration.ofDays(31))>0||q.page()<0||q.page()>10000||q.size()<1||q.size()>100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Informe um período de até 31 dias e paginação válida.");
        var p=new HashMap<String,Object>();p.put("from",java.sql.Timestamp.from(since));p.put("to",java.sql.Timestamp.from(until));
        var where=new StringBuilder(" j.created_at>=:from and j.created_at<=:to");
        if(q.userId()!=null&&!q.userId().isBlank()){p.put("user",q.userId());where.append(" and j.user_id=:user");}
        if(q.source()!=null&&!q.source().isBlank()){
            if(!Set.of("WEB","WHATSAPP").contains(q.source()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Origem inválida para chamadas.");
            p.put("source",q.source());where.append(" and j.source=:source");
        }
        // The outcome filter keeps its meaning for calls: completed, failed or still running.
        where.append(switch(q.result()==null?"ALL":q.result()){
            case "ALL",""->"";case "SUCCESS"->" and j.status='COMPLETED'";case "ERROR"->" and j.status in ('FAILED','NEEDS_REVIEW')";
            case "CLIENT_ERROR","SERVER_ERROR"->" and j.status in ('FAILED','NEEDS_REVIEW')";
            default->throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Resultado inválido.");});
        p.put("limit",q.size());p.put("offset",q.page()*q.size());
        var rows=jdbc.queryForList("""
                select j.id,j.user_id,coalesce(a.display_name,a.username) as user_name,a.username,j.source,j.status,j.attempts,
                j.created_at,j.started_at,j.finished_at,j.delivered_at,
                coalesce(u.calls,0) as ai_calls,coalesce(u.total_tokens,0) as total_tokens
                from chat_jobs j left join auth_accounts a on a.user_id=j.user_id
                left join (select request_id,count(*) as calls,sum(total_tokens) as total_tokens from ai_usage_events
                           where request_id is not null group by request_id) u on u.request_id=j.id::varchar
                where %s order by j.created_at desc limit :limit offset :offset
                """.formatted(where),p);
        Long total=jdbc.queryForObject("select count(*) from chat_jobs j where "+where,p,Long.class);
        for(var row:rows){
            Object created=row.get("created_at"); Object end=row.get("delivered_at")!=null?row.get("delivered_at"):row.get("finished_at");
            if(created instanceof java.sql.Timestamp s){
                java.sql.Timestamp stop=end instanceof java.sql.Timestamp t?t:null;
                row.put("duration_ms", stop==null?null:Math.max(0,stop.getTime()-s.getTime()));
            }else row.put("duration_ms",null);
        }
        return Map.of("items",rows,"total",total==null?0:total,"page",q.page(),"size",q.size());
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
