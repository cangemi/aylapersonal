package ayla.hermes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HermesAutomaticModelTest {
    static final String PREFERRED="stepfun/step-3.7-flash:free";
    private HermesModels models(ModelAvailabilityService availability){
        var client=mock(HermesClient.class);
        when(client.get(eq("profile-a"),anyString())).thenReturn(Map.of("providers",List.of(Map.of(
            "slug","nous","authenticated",true,"models",List.of(PREFERRED,"alternative:free","paid-model"),
            "pricing",Map.of(PREFERRED,Map.of("free",true,"input","free","output","free"),
                "alternative:free",Map.of("free",true,"input","free","output","free"),
                "paid-model",Map.of("free",false,"input","1","output","1"))))));
        var models=new HermesModels(client);models.setAvailability(availability);return models;
    }
    @Test void automaticSelectionSkipsLimitedModelBeforeDispatchAndNeverSelectsPaidModel(){
        var availability=mock(ModelAvailabilityService.class);
        when(availability.modelsForProfile("profile-a")).thenReturn(List.of(new ProviderUsageData.Availability(
            "nous",PREFERRED,"RATE_LIMITED",Instant.now(),5812L,Instant.now().plusSeconds(5812),null,List.of("paid-model"),null)));
        assertThat(models(availability).select(null,"profile-a").model()).isEqualTo("alternative:free");
    }
    @Test void manualSelectionRemainsExplicit(){
        var availability=mock(ModelAvailabilityService.class);
        assertThat(models(availability).select(PREFERRED,"profile-a").model()).isEqualTo(PREFERRED);
        verifyNoInteractions(availability);
    }
    @Test void automaticConversationStaysOnTheWorkingModelAfterThePreferredCooldownExpires(){
        var availability=mock(ModelAvailabilityService.class);var now=Instant.now();
        when(availability.modelsForProfile("profile-a")).thenReturn(List.of(
            new ProviderUsageData.Availability("nous",PREFERRED,"PENDING_CONFIRMATION",now.minusSeconds(100),60L,now.minusSeconds(40),null,List.of(),null),
            new ProviderUsageData.Availability("nous","alternative:free","AVAILABLE",null,null,null,null,List.of(),now.minusSeconds(2))));
        assertThat(models(availability).select(null,"profile-a").model()).isEqualTo("alternative:free");
    }
    @Test void anObservedSuccessfulPaidOrMissingModelNeverOverridesFreeEligibility(){
        var availability=mock(ModelAvailabilityService.class);
        when(availability.modelsForProfile("profile-a")).thenReturn(List.of(
            new ProviderUsageData.Availability("nous","paid-model","AVAILABLE",null,null,null,null,List.of(),Instant.now())));
        assertThat(models(availability).select(null,"profile-a").model()).isEqualTo(PREFERRED);
    }
    @Test void automaticSelectionCanRecheckAModelWhenAllUnknownDeadlinesWereObserved(){
        var availability=mock(ModelAvailabilityService.class);
        when(availability.modelsForProfile("profile-a")).thenReturn(List.of(
            new ProviderUsageData.Availability("nous",PREFERRED,"RATE_LIMITED",Instant.now(),null,null,null,List.of(),null),
            new ProviderUsageData.Availability("nous","alternative:free","RATE_LIMITED",Instant.now(),null,null,null,List.of(),null)));
        assertThat(models(availability).select(null,"profile-a").model()).isEqualTo(PREFERRED);
    }
    @Test void automaticQueueAdmissionDoesNotBlockTheLastUsedModelBeforeSelectingAnAlternative(){
        var jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);var scope=mock(HermesUserScope.class);
        var settings=mock(HermesSettingsService.class);var availability=mock(ModelAvailabilityService.class);
        when(scope.profile("alice",false)).thenReturn("profile-a");when(settings.get("alice")).thenReturn(new HermesConfiguration());
        new ProviderUsageService(jdbc,scope,settings,mock(HermesClient.class),availability).check("alice");
        verifyNoInteractions(availability);
    }
}
