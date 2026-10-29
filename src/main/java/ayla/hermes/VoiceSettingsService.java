package ayla.hermes;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ayla.chat.dto.ChatAudio;

@Service
public class VoiceSettingsService {
    private final JdbcTemplate jdbc;
    private final HermesDashboardClient dashboard;
    private HermesUserScope scope;
    @org.springframework.beans.factory.annotation.Autowired
    void setScope(HermesUserScope scope){this.scope=scope;}
    public VoiceSettingsService(JdbcTemplate jdbc,HermesDashboardClient dashboard) {
        this.jdbc=jdbc; this.dashboard=dashboard;
    }
    public VoiceSettings get() {
        return jdbc.queryForObject("select voice,speed,pitch_hz from voice_settings where id=1",
                (rs,n)->new VoiceSettings(rs.getString(1),rs.getBigDecimal(2),rs.getInt(3)));
    }
    public VoiceSettings update(VoiceSettings settings) {
        jdbc.update("update voice_settings set voice=?,speed=?,pitch_hz=? where id=1",
                settings.voice(),settings.speed(),settings.pitchHz());
        return settings;
    }
    private boolean personal(String userId) {
        if(scope==null||userId==null)throw new HermesException(503,"Isolamento de voz indisponível.");
        return !scope.owner(userId);
    }
    public VoiceSettings get(String userId) {
        if(!personal(userId)) return get();
        var rows=jdbc.query("select voice,speed,pitch_hz from user_agent_settings where user_id=? and voice is not null",
                (rs,n)->new VoiceSettings(rs.getString(1),rs.getBigDecimal(2),rs.getInt(3)),userId);
        return rows.isEmpty()?new VoiceSettings("pt-BR-FranciscaNeural",new java.math.BigDecimal("1.02"),2):rows.getFirst();
    }
    public VoiceSettings update(String userId,VoiceSettings settings) {
        if(!personal(userId)) return update(settings);
        jdbc.update("""
                insert into user_agent_settings(user_id,voice,speed,pitch_hz) values (?,?,?,?)
                on conflict (user_id) do update set voice=excluded.voice,speed=excluded.speed,pitch_hz=excluded.pitch_hz""",
                userId,settings.voice(),settings.speed(),settings.pitchHz());
        return settings;
    }
    public ChatAudio preview(String userId,VoiceSettings settings) {
        return dashboard.preview(settings==null?get(userId):settings);
    }
    public ChatAudio speak(String profile,String text) {
        var rows=profile==null?java.util.List.<String>of():jdbc.queryForList("select user_id from chat_users where hermes_profile=?",String.class,profile);
        if(rows.size()!=1||scope==null||!profile.equals(scope.profile(rows.getFirst(),true)))throw new HermesException(409,"Perfil de voz sem vínculo exclusivo com uma conta.");
        return dashboard.speak(profile,text,get(rows.getFirst()));
    }
    public ChatAudio preview(VoiceSettings settings) {
        return dashboard.preview(settings==null?get():settings);
    }
}
