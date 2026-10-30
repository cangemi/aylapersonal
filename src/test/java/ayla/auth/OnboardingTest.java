package ayla.auth;

import ayla.chat.session.ChatUserRepository;
import ayla.hermes.HermesAccountService;
import ayla.whatsapp.WhatsappGateway;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OnboardingTest {
    @Test void completesWithoutWhatsappButStillRequiresProfilePasswordAndHermes() {
        var jdbc = mock(JdbcTemplate.class);
        var profiles = mock(UserProfileService.class);
        var hermes = mock(HermesAccountService.class);
        var whatsapp = mock(WhatsappGateway.class);
        var service = spy(new AuthService(jdbc, mock(ChatUserRepository.class), profiles, hermes, whatsapp, "owner", ""));
        var identity = new AuthData.Identity("user", "alice", "USER", UUID.randomUUID());
        var saved = new AuthData.User("user", "alice", "USER", "Alice", false, false);
        doReturn(saved).when(service).me(identity);
        assertThatThrownBy(() -> service.completeOnboarding(identity)).isInstanceOf(AuthException.class).hasMessageContaining("nome");
        when(profiles.name("user")).thenReturn("Alice");
        when(jdbc.queryForObject("select password_temporary from auth_accounts where user_id=?", Boolean.class, "user")).thenReturn(true);
        assertThatThrownBy(() -> service.completeOnboarding(identity)).hasMessageContaining("senha");
        when(jdbc.queryForObject("select password_temporary from auth_accounts where user_id=?", Boolean.class, "user")).thenReturn(false);
        assertThatThrownBy(() -> service.completeOnboarding(identity)).hasMessageContaining("Hermes");
        when(hermes.ready("user")).thenReturn(true);
        assertThat(service.completeOnboarding(identity)).isEqualTo(saved);
        verify(jdbc).update("update auth_accounts set onboarded=true,updated_at=now() where user_id=?", "user");
        verifyNoInteractions(whatsapp);
    }
}
