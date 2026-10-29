package ayla.hermes;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import ayla.chat.session.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class HermesUserIsolationTest {
    @Test void unprovisionedUserNeverReadsOrWritesTheOwnersSettings() {
        var jdbc=mock(JdbcTemplate.class);var repository=mock(HermesConfigurationRepository.class);var models=mock(HermesModels.class);
        var scope=new HermesUserScope(jdbc,new ChatIdentity("owner"));
        var settings=new HermesSettingsService(repository,models);settings.setJdbc(jdbc);settings.setScope(scope);
        assertThat(settings.get("alice").getInstructions()).isNullOrEmpty();
        settings.update("alice",null,"Preferência apenas da Alice");
        verifyNoInteractions(repository,models);
        var voice=new VoiceSettingsService(jdbc,mock(HermesDashboardClient.class));voice.setScope(scope);
        assertThat(voice.get("alice").voice()).isEqualTo("pt-BR-FranciscaNeural");
        assertThat(mockingDetails(jdbc).getInvocations().stream().anyMatch(call->call.getArguments().length>0&&String.valueOf(call.getArguments()[0]).contains("from voice_settings"))).isFalse();
    }
    @Test void defaultAndSharedProfilesAreRejectedForOtherAccounts() {
        var jdbc=mock(JdbcTemplate.class);var scope=new HermesUserScope(jdbc,new ChatIdentity("owner"));
        when(jdbc.queryForList("select hermes_profile from chat_users where user_id=?",String.class,"alice")).thenReturn(List.of("default"));
        assertThatThrownBy(()->scope.profile("alice",true)).isInstanceOf(HermesException.class);
        when(jdbc.queryForList("select hermes_profile from chat_users where user_id=?",String.class,"alice")).thenReturn(List.of("profile-a"));
        when(jdbc.queryForObject("select count(*) from chat_users where hermes_profile=?",Long.class,"profile-a")).thenReturn(2L);
        assertThatThrownBy(()->scope.profile("alice",true)).isInstanceOf(HermesException.class);
    }
    @Test void oauthPollingIsBoundToTheUserWhoStartedItAndNewProfilesUseANeutralTemplate() {
        var jdbc=mock(JdbcTemplate.class);var dashboard=mock(HermesDashboardClient.class);var client=mock(HermesClient.class);var scope=mock(HermesUserScope.class);
        var service=new HermesAccountService(jdbc,mock(ChatUserRepository.class),new ChatIdentity("owner"),dashboard,client,mock(HermesModels.class),scope);
        when(client.get(anyString(),eq("/health"))).thenReturn(Map.of("status","ok"));
        service.provision("alice");
        verify(dashboard).admin(eq("POST"),eq("/api/profiles"),argThat(body->"ayla-internal-template".equals(body.get("clone_from"))&&Boolean.FALSE.equals(body.get("clone_from_default"))));
        when(scope.profile("alice",false)).thenReturn("profile-a");
        when(dashboard.admin("POST","/api/providers/oauth/nous/start?profile=profile-a",null)).thenReturn(Map.of("session_id","login-session-123"));
        service.startLogin("alice");
        assertThatThrownBy(()->service.pollLogin("bob","login-session-123")).isInstanceOf(HermesException.class);
        verify(dashboard,never()).admin(eq("GET"),contains("/poll/"),any());
    }
}
