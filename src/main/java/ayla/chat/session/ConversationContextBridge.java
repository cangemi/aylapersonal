package ayla.chat.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Hidden;
import ayla.hermes.HermesException;

@Hidden @RestController
@RequestMapping("/internal/hermes/conversation-context")
public class ConversationContextBridge {
    private final ConversationContext context;
    private final byte[] key;
    public ConversationContextBridge(ConversationContext context,@Value("${ayla.finance.bridge-key:}") String key){this.context=context;this.key=key.getBytes(StandardCharsets.UTF_8);}
    public record Search(@NotBlank @Size(max=300) String query) {}
    @PostMapping("/search")
    public Object search(@RequestHeader(value="Authorization",defaultValue="") String auth,
                         @RequestHeader(value="X-Ayla-Session",defaultValue="") String session,
                         @Valid @RequestBody Search input) {
        if(key.length<32 || !auth.startsWith("Bearer ") || !MessageDigest.isEqual(key,auth.substring(7).getBytes(StandardCharsets.UTF_8)))
            throw new HermesException(401,"Integração não autorizada.");
        var scope=context.requirePrimary(session);
        ayla.monitoring.MonitoringContext.user(scope.userId());
        return Map.of("items",context.search(scope,input.query()));
    }
}
