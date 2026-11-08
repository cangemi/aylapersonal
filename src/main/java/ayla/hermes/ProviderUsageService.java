package ayla.hermes;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ProviderUsageService {
    private final JdbcTemplate jdbc;
    private final HermesUserScope scope;
    private final HermesSettingsService settings;
    private final HermesClient hermes;
    private final ModelAvailabilityService availability;
    private final JsonMapper json=JsonMapper.builder().findAndAddModules().build();
    private HermesDashboardClient dashboard;
    private HermesAccountService accounts;
    @org.springframework.beans.factory.annotation.Autowired
    public void setQuotaReader(HermesDashboardClient dashboard,HermesAccountService accounts){this.dashboard=dashboard;this.accounts=accounts;}
    private final java.util.concurrent.ConcurrentMap<String,Long> quotaAttempts=new java.util.concurrent.ConcurrentHashMap<>();
    public ProviderUsageService(JdbcTemplate jdbc,HermesUserScope scope,HermesSettingsService settings,HermesClient hermes,ModelAvailabilityService availability){
        this.jdbc=jdbc;this.scope=scope;this.settings=settings;this.hermes=hermes;this.availability=availability;
    }
    private String selected(String user,String profile){
        var configured=settings.get(user).getModel();
        if(configured!=null&&!configured.isBlank())return configured;
        var rows=jdbc.queryForList("select last_model from provider_connections where user_id=? and provider='nous' and profile=?",String.class,user,profile);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public void check(String user){
        String profile=scope.profile(user,false);
        // Automatic mode selects another free model before dispatch; last_model is only telemetry.
        if(profile!=null){String configured=settings.get(user).getModel();if(configured!=null&&!configured.isBlank())availability.check(profile,configured);}
    }
    public record UsageWindow(String period,Instant from,Instant to,String timeZone,String label){}
    static UsageWindow usageWindow(String period,Instant now){
        var zone=java.time.ZoneId.of("America/Sao_Paulo");var local=now.atZone(zone);
        return switch(Objects.toString(period,"24H").toUpperCase(Locale.ROOT)){
            case "DAY" -> new UsageWindow("DAY",local.toLocalDate().atStartOfDay(zone).toInstant(),now,zone.getId(),"Hoje, no horário de Brasília");
            case "24H" -> new UsageWindow("24H",now.minus(java.time.Duration.ofHours(24)),now,zone.getId(),"Últimas 24 horas");
            case "7D" -> new UsageWindow("7D",now.minus(java.time.Duration.ofDays(7)),now,zone.getId(),"Últimos 7 dias");
            case "MONTH" -> new UsageWindow("MONTH",local.toLocalDate().withDayOfMonth(1).atStartOfDay(zone).toInstant(),now,zone.getId(),"Mês atual, no horário de Brasília");
            default -> throw new HermesException(400,"Período de consumo inválido.");
        };
    }
    public Map<String,Object> view(String user){return view(user,"24H");}
    public Map<String,Object> view(String user,String period){
        long started=System.nanoTime();
        var window=usageWindow(period,Instant.now());
        String profile=scope.profile(user,false);
        var result=new LinkedHashMap<String,Object>();
        result.put("serverNow",Instant.now());result.put("provider","nous");
        result.put("automaticFallback",false);result.put("quotaSource","API oficial Nous, consultada pelo perfil Hermes; sem scraping");
        result.put("quotaStatus","UNAVAILABLE");result.put("quotas",List.of());result.put("models",List.of());result.put("model",null);
        result.put("status","UNKNOWN");result.put("connected",false);result.put("sharedCredentials",false);
        result.put("usageWindow",window);
        result.put("recordedUsage",jdbc.queryForMap("""
            select count(*) as calls,count(*) filter(where total_tokens is null) as unreported_calls,
            sum(input_tokens) as input_tokens,sum(output_tokens) as output_tokens,sum(total_tokens) as total_tokens,
            count(*) filter(where status>=400) as failures
            from ai_usage_events where user_id=? and occurred_at>=? and occurred_at<=? and (provider='nous' or provider is null)
            """,user,java.sql.Timestamp.from(window.from()),java.sql.Timestamp.from(window.to())));
        result.put("recordedUsageByModel",jdbc.queryForList("""
            select model,count(*) as calls,count(*) filter(where total_tokens is null) as unreported_calls,
            sum(input_tokens) as input_tokens,sum(output_tokens) as output_tokens,sum(total_tokens) as total_tokens,
            count(*) filter(where status>=400) as failures,max(occurred_at) as last_call
            from ai_usage_events where user_id=? and occurred_at>=? and occurred_at<=? and (provider='nous' or provider is null)
            group by model order by total_tokens desc nulls last,model limit 100
            """,user,java.sql.Timestamp.from(window.from()),java.sql.Timestamp.from(window.to())));
        result.put("recordedUsagePeriod",window.label()+" nesta conta Ayla. Inclui as chamadas registradas pela Ayla, não o consumo externo nem o saldo da cota Nous.");
        if(profile==null){result.put("message","Conecte seu perfil Hermes para monitorar a disponibilidade.");return responseTime(result,started);}
        boolean loginRequired=false;
        try{
            if(accounts!=null){loginRequired=!accounts.loggedInForUsage(user);if(!loginRequired)refreshQuota(user,profile);}
        }catch(HermesException unavailable){result.put("quotaQueryMessage","Não foi possível consultar a cota oficial agora. Verifique a autorização e o painel do Hermes.");}
        try{hermes.get(profile,"/health");result.put("connected",true);}
        catch(HermesException failure){result.put("status","ERROR");result.put("message","Não foi possível consultar a saúde do Hermes.");}
        String selected=selected(user,profile);result.put("model",selected);
        var states=availability.models(user,profile,selected);result.put("models",states);
        var active=states.stream().filter(state->Objects.equals(selected,state.model())).findFirst().orElse(null);
        if(Boolean.TRUE.equals(result.get("connected"))){
            result.put("status",active==null?"UNKNOWN":active.status());
            result.put("message",active==null?"Hermes conectado; disponibilidade do modelo ainda não confirmada.":"Estado baseado na última observação da integração.");
        }
        result.put("availability",active==null?null:availability.publicNotice(active));
        var snapshots=jdbc.queryForList("""
            select q.status,q.quotas_json,q.observed_at,
              (select count(*)>1 from provider_connections shared where shared.provider=c.provider and shared.credential_scope=c.credential_scope) as shared
            from provider_connections c left join provider_quota_snapshots q on q.provider=c.provider and q.credential_scope=c.credential_scope
            where c.user_id=? and c.provider='nous' and c.profile=?
            """,user,profile);
        if(!loginRequired&&!snapshots.isEmpty()){
            var row=snapshots.getFirst();Instant at=ModelAvailabilityService.time(row.get("observed_at"));
            result.put("sharedCredentials",Boolean.TRUE.equals(row.get("shared")));result.put("quotaUpdatedAt",at);
            String status=Objects.toString(row.get("status"),"UNAVAILABLE");
            if(at!=null&&at.isBefore(Instant.now().minusSeconds(180)))status="STALE";
            result.put("quotaStatus",status);
            if("KNOWN".equals(status)&&row.get("quotas_json") instanceof String raw){
                try{result.put("quotas",json.readValue(raw,List.class));}catch(RuntimeException invalid){result.put("quotaStatus","ERROR");}
            }
        }
        if(loginRequired){result.put("quotaStatus","LOGIN_REQUIRED");result.put("quotas",List.of());}
        if(result.containsKey("quotaQueryMessage")&&"UNAVAILABLE".equals(result.get("quotaStatus")))result.put("quotaStatus","ERROR");
        return responseTime(result,started);
    }
    private void refreshQuota(String user,String profile){
        if(dashboard==null)return;
        var observed=jdbc.queryForList("""
            select q.observed_at from provider_connections c join provider_quota_snapshots q
            on q.provider=c.provider and q.credential_scope=c.credential_scope
            where c.user_id=? and c.profile=? and c.provider='nous'
            """,user,profile);
        if(!observed.isEmpty()){
            Instant at=ModelAvailabilityService.time(observed.getFirst().get("observed_at"));
            if(at!=null&&at.isAfter(Instant.now().minusSeconds(25)))return;
        }
        long now=System.nanoTime();
        Long previous=quotaAttempts.get(profile);
        if(!observed.isEmpty()&&previous!=null&&now-previous<java.util.concurrent.TimeUnit.SECONDS.toNanos(25))return;
        if(quotaAttempts.size()>512)quotaAttempts.entrySet().removeIf(e->now-e.getValue()>java.util.concurrent.TimeUnit.MINUTES.toNanos(5));
        quotaAttempts.put(profile,now);
        var response=dashboard.accountSnapshot(profile,observed.isEmpty());
        if(!(response.get("event") instanceof Map<?,?> raw))throw new HermesException(502,"O Hermes não retornou a cota oficial.");
        var event=json.readValue(json.writeValueAsString(raw),ProviderUsageData.Event.class);
        if(!profile.equals(event.profile())||!"QUOTA".equals(event.state())||!"nous".equals(event.provider()))
            throw new HermesException(502,"O Hermes não confirmou o perfil da consulta de cota.");
        availability.observe(user,event);
    }
    private Map<String,Object> responseTime(Map<String,Object> result,long started){
        Instant now=Instant.now();result.put("serverNow",now);result.put("serverProcessingMillis",(System.nanoTime()-started)/1_000_000);
        if(result.get("availability") instanceof Map<?,?> meta)((Map<String,Object>)meta).put("serverNow",now);
        return result;
    }
}
