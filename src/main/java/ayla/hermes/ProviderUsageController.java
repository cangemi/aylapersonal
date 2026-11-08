package ayla.hermes;

import ayla.chat.session.ChatIdentity;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
public class ProviderUsageController {
    private final ProviderUsageService usage;
    private final ModelAvailabilityService availability;
    private final JdbcTemplate jdbc;
    private final ChatIdentity identity;
    public ProviderUsageController(ProviderUsageService usage,ModelAvailabilityService availability,JdbcTemplate jdbc,ChatIdentity identity){
        this.usage=usage;this.availability=availability;this.jdbc=jdbc;this.identity=identity;
    }
    @GetMapping("/api/v1/me/hermes/usage")
    public ResponseEntity<Map<String,Object>> view(Principal principal,@RequestParam(defaultValue="24H") String period){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(usage.view(identity.resolve(principal),period));}

    @PostMapping("/internal/hermes/provider-events") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void event(@Valid @RequestBody ProviderUsageData.Event event){
        if("ayla-internal-template".equals(event.profile()))throw new HermesException(403,"Perfil não monitorável.");
        var users=jdbc.queryForList("select u.user_id from chat_users u join auth_accounts a on a.user_id=u.user_id where u.hermes_profile=? and a.enabled",String.class,event.profile());
        if(users.size()!=1)throw new HermesException(403,"Perfil sem proprietário ativo exclusivo.");
        if("default".equals(event.profile())&&!identity.ownerId().equals(users.getFirst()))throw new HermesException(403,"Perfil inválido.");
        availability.observe(users.getFirst(),event);
    }
}
