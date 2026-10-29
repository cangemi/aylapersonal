package ayla.chat.session;

import ayla.hermes.HermesException;
import java.security.Principal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Authenticated stable user ID; anonymous calls never inherit the owner's data. */
@Component
public class ChatIdentity {
    private final String ownerId;
    public ChatIdentity(@Value("${ayla.chat.owner-id:local-owner}") String ownerId) {
        this.ownerId = ownerId;
    }
    public String resolve(Principal principal) {
        if (principal != null && principal.getName() != null && !principal.getName().isBlank()) {
            if (principal.getName().length() > 255) throw new HermesException(400, "Identificador de usuário inválido.");
            return principal.getName();
        }
        throw new HermesException(401, "Autentique-se para acessar suas conversas.");
    }
    public String ownerId() { return ownerId; }
}
