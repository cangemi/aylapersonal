package ayla.hermes;

import java.util.*;
import java.security.MessageDigest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.*;
import org.slf4j.LoggerFactory;
import ayla.chat.dto.ChatImage;

@Service
@EnableScheduling
public class HermesMediaCleanup {
    private final JdbcTemplate jdbc;
    private final HermesClient client;
    private final HermesDashboardClient dashboard;
    public HermesMediaCleanup(JdbcTemplate jdbc,HermesClient client,HermesDashboardClient dashboard) {
        this.jdbc=jdbc;this.client=client;this.dashboard=dashboard;
    }
    public List<String> track(String profile,String session,List<ChatImage> images) {
        var names=new ArrayList<String>();
        for(var image:images) {
            try {
                String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(image.data())));
                String extension=switch(image.mimeType()) {case "image/jpeg"->"jpg";case "image/png"->"png";default->"webp";};
                names.add("api_"+digest.substring(0,32)+"."+extension);
            }catch(java.security.NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
        }
        if(jdbc!=null) jdbc.update("insert into hermes_media_cleanup(session_id,profile,cache_names) values (?,?,?)",session,profile,String.join(",",names));
        return names;
    }
    public void clean(String profile,String session,List<String> names) {
        if(!session.matches("ayla-media-[a-f0-9-]{36}")) throw new IllegalArgumentException("Sessão temporária inválida");
        try {
            try {client.delete(profile,"/api/sessions/"+session);} catch(HermesException e) {if(e.status()!=404) throw e;}
            for(String name:names) dashboard.deleteImageCache(profile,name);
            if(jdbc!=null) jdbc.update("delete from hermes_media_cleanup where session_id=?",session);
        } catch(RuntimeException e) {
            // Durable job remains; never discard the analysis/financial result due to cleanup downtime.
            LoggerFactory.getLogger(getClass()).warn("Limpeza de mídia pendente; será repetida automaticamente. tipo={}",e.getClass().getSimpleName());
        }
    }
    @Scheduled(fixedDelay=60000,initialDelay=60000)
    public void retry() {
        if(jdbc==null) return;
        for(var row:jdbc.queryForList("select session_id,profile,cache_names from hermes_media_cleanup where created_at<CURRENT_TIMESTAMP-INTERVAL '10 minutes' order by created_at limit 20"))
            clean((String)row.get("profile"),(String)row.get("session_id"),Arrays.asList(((String)row.get("cache_names")).split(",")));
    }
}
