package ayla.hermes;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Extract only public provider metadata. Never retain error text or raw bodies. */
public final class ProviderLimitParser {
    private static final JsonMapper JSON=JsonMapper.builder().enable(tools.jackson.core.json.JsonReadFeature.ALLOW_SINGLE_QUOTES).build();
    public record Limit(boolean providerConfirmed,Long seconds,List<String> alternates,String model) {}
    public static Limit parse(String raw,String retryAfter,Instant now){
        Map<?,?> data=Map.of();
        try{if(raw!=null&&raw.length()<=65536)data=JSON.readValue(raw,Map.class);}catch(RuntimeException ignored){}
        var merged=new HashMap<Object,Object>();merged.putAll(data);
        if(data.get("error") instanceof Map<?,?> nested)merged.putAll(nested);
        // Hermes may wrap the SDK's Python-style dict repr in an error message.
        for(Object value:List.of(Objects.toString(merged.get("message"),""),Objects.toString(merged.get("error"),""))){
            String text=value.toString();int start=text.indexOf('{'),end=text.lastIndexOf('}');
            if(text.length()<=16000&&start>=0&&end>start)try{
                Map<?,?> nested=JSON.readValue(text.substring(start,end+1),Map.class);
                for(String field:List.of("reason","code","retry_after","retry_after_seconds","alternates","model"))
                    if(nested.containsKey(field))merged.put(field,nested.get(field));
            }catch(RuntimeException ignored){}
        }
        String reason=Objects.toString(merged.get("reason"),Objects.toString(merged.get("code"),""));
        Object delay=merged.getOrDefault("retry_after",merged.get("retry_after_seconds"));
        Long seconds=seconds(delay,now);if(seconds==null)seconds=seconds(retryAfter,now);
        var alternates=new ArrayList<String>();
        if(merged.get("alternates") instanceof List<?> list)for(Object item:list)
            if(item instanceof String value&&ModelAvailabilityService.identifier(value)!=null&&alternates.size()<12)alternates.add(value);
        return new Limit(Set.of("rate_limited","rate_limit","rate_limit_exceeded","quota_exceeded").contains(reason)||delay!=null,
            seconds,List.copyOf(alternates),ModelAvailabilityService.identifier(Objects.toString(merged.get("model"),null)));
    }
    static Long seconds(Object value,Instant now){
        if(value==null||value instanceof Boolean)return null;
        try{double n=Double.parseDouble(value.toString());return Double.isFinite(n)&&n>=0&&n<=31536000?(long)Math.ceil(n):null;}
        catch(NumberFormatException invalid){try{return Math.min(31536000,Math.max(0,Duration.between(now,ZonedDateTime.parse(value.toString(),DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).getSeconds()));}catch(RuntimeException ignored){return null;}}
    }
}
