package ayla.hermes;

import ayla.chat.session.ChatIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** A missing or shared profile must never turn into access to the owner's profile. */
@Service
public class HermesUserScope {
    private final JdbcTemplate jdbc;
    private final ChatIdentity identity;
    public HermesUserScope(JdbcTemplate jdbc,ChatIdentity identity){this.jdbc=jdbc;this.identity=identity;}
    public boolean owner(String user){return user!=null&&identity.ownerId().equals(user);}
    public String profile(String user,boolean required){
        if(user==null||user.isBlank())throw new HermesException(403,"Usuário inválido para o Hermes.");
        var rows=jdbc.queryForList("select hermes_profile from chat_users where user_id=?",String.class,user);
        String profile=rows.isEmpty()?null:rows.getFirst();
        if(profile==null||profile.isBlank()){
            if(required)throw new HermesException(409,"Crie o perfil Hermes desta conta para continuar.");
            return null;
        }
        if(!profile.matches("[a-zA-Z0-9_-]{1,64}")||("default".equalsIgnoreCase(profile)&&!owner(user))
                ||"ayla-internal-template".equals(profile))throw new HermesException(403,"Este perfil Hermes não pode ser usado por esta conta.");
        Long owners=jdbc.queryForObject("select count(*) from chat_users where hermes_profile=?",Long.class,profile);
        if(owners==null||owners!=1L)throw new HermesException(409,"O perfil Hermes deve pertencer a uma única conta.");
        return profile;
    }
}
