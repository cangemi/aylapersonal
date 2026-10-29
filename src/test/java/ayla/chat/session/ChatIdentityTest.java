package ayla.chat.session;

import ayla.hermes.HermesException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ChatIdentityTest {
    @Test void authenticatedPrincipalAlwaysSelectsItsOwnUser() {
        var identity = new ChatIdentity("owner");
        assertThat(identity.resolve(() -> "alice")).isEqualTo("alice");
        assertThat(identity.resolve(() -> "bob")).isEqualTo("bob");
    }
    @Test void multiuserModeRequiresAuthentication() {
        var identity = new ChatIdentity("owner");
        assertThatThrownBy(() -> identity.resolve(null)).isInstanceOfSatisfying(HermesException.class,
                e -> assertThat(e.status()).isEqualTo(401));
    }
}
