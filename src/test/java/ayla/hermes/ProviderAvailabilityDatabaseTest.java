package ayla.hermes;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;
import static ayla.hermes.ProviderUsageData.*;

@Testcontainers(disabledWithoutDocker=true)
class ProviderAvailabilityDatabaseTest {
    @Container static final PostgreSQLContainer<?> database=new PostgreSQLContainer<>("postgres:17-alpine");
    JdbcTemplate jdbc;
    ModelAvailabilityService service;
    final Instant now=Instant.parse("2026-11-04T22:00:00Z");
    @BeforeEach void setup() throws Exception {
        jdbc=new JdbcTemplate(new DriverManagerDataSource(database.getJdbcUrl(),database.getUsername(),database.getPassword()));
        jdbc.execute("drop schema public cascade; create schema public");
        jdbc.execute("create table chat_users(user_id varchar(255) primary key,hermes_profile varchar(64)); create table chat_jobs(id uuid primary key)");
        jdbc.execute("insert into chat_users values ('alice','u-alice'),('bob','u-bob'),('carol','u-carol')");
        try(var input=getClass().getResourceAsStream("/db/migration/V56__provider_availability.sql")){jdbc.execute(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
        service=new ModelAvailabilityService(jdbc,Clock.fixed(now,ZoneOffset.UTC));
    }
    Event event(String profile,String scope,String model,String state,Long retry){return new Event(UUID.randomUUID().toString(),profile,scope.repeat(64),"nous",model,state,now,now,retry,null,List.of(),null,null,null);}
    @Test void sharedAccountSharesSameModelButNotOtherModelsOrDifferentAccounts(){
        service.observe("alice",event("u-alice","a","model-one","RATE_LIMITED",1645L));
        service.observe("bob",event("u-bob","a","model-one","REQUEST",null));
        service.observe("carol",event("u-carol","c","model-one","AVAILABLE",null));
        assertThat(service.models("bob","u-bob")).singleElement().extracting(Availability::status).isEqualTo("RATE_LIMITED");
        assertThat(service.models("carol","u-carol")).singleElement().extracting(Availability::status).isEqualTo("AVAILABLE");
        service.observe("bob",event("u-bob","a","model-two","AVAILABLE",null));
        assertThat(service.models("alice","u-alice")).hasSize(1); // Other user's model history is private.
        assertThat(service.models("bob","u-bob")).extracting(Availability::status).containsExactly("RATE_LIMITED","AVAILABLE");
        assertThatThrownBy(()->service.check("u-bob","model-one")).isInstanceOf(Limited.class);
        assertThatCode(()->service.check("u-bob","model-two")).doesNotThrowAnyException();
    }
    @Test void databaseSurvivesServiceRestartAndExpiryIsNotSuccess(){
        service.observe("alice",event("u-alice","a","model-one","RATE_LIMITED",60L));
        var restarted=new ModelAvailabilityService(jdbc,Clock.fixed(now.plusSeconds(61),ZoneOffset.UTC));
        assertThat(restarted.models("alice","u-alice")).singleElement().extracting(Availability::status).isEqualTo("PENDING_CONFIRMATION");
        var observed=now.plusSeconds(62);
        restarted.observe("alice",new Event(UUID.randomUUID().toString(),"u-alice","a".repeat(64),"nous","model-one","AVAILABLE",observed,observed,null,null,List.of(),null,null,null));
        assertThat(restarted.models("alice","u-alice")).singleElement().extracting(Availability::status).isEqualTo("AVAILABLE");
    }
    @Test void lateEventsCannotReplaceNewCredentialBindingAndDuplicateEventsAreIdempotent(){
        var event=event("u-alice","a","model-one","RATE_LIMITED",60L);
        service.observe("alice",event);service.observe("alice",event);
        assertThat(jdbc.queryForObject("select count(*) from provider_telemetry_receipts",Integer.class)).isEqualTo(1);
        var newer=now.plusSeconds(1);
        service.observe("alice",new Event(UUID.randomUUID().toString(),"u-alice","b".repeat(64),"nous","model-one","AVAILABLE",newer,newer,null,null,List.of(),null,null,null));
        service.observe("alice",event("u-alice","a","model-one","RATE_LIMITED",120L));
        assertThat(service.models("alice","u-alice")).singleElement().extracting(Availability::status).isEqualTo("AVAILABLE");
    }
}
