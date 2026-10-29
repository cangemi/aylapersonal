package ayla.cluster;

import ayla.hermes.*;
import ayla.chat.session.*;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
@Timeout(30)
class ClusterDatabaseTest {
    @Container static final PostgreSQLContainer<?> database=new PostgreSQLContainer<>("postgres:17-alpine");
    DriverManagerDataSource dataSource;
    @BeforeEach void connect(){dataSource=new DriverManagerDataSource(database.getJdbcUrl(),database.getUsername(),database.getPassword());}

    @Test void replicasRejectOverlappingTurnsButDifferentConversationsRunTogetherAndLocksAreReleased()throws Exception{
        var first=new ClusterOperations(dataSource,1);var second=new ClusterOperations(dataSource,1);
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var running=executor.submit(()->first.chat("same",()->{
                started.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("Timed out");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}return "done";
            }));
            try{
                assertThat(started.await(10,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->second.chat("same",()->"must not execute")).isInstanceOfSatisfying(HermesException.class,e->assertThat(e.status()).isEqualTo(409));
                assertThatThrownBy(()->first.chat("other",()->"must not execute")).isInstanceOfSatisfying(HermesException.class,e->assertThat(e.status()).isEqualTo(429));
                assertThat(second.chat("other",()->"independent")).isEqualTo("independent");
            }finally{release.countDown();}
            assertThat(running.get(10,TimeUnit.SECONDS)).isEqualTo("done");
        }
        assertThatThrownBy(()->first.chat("same",()->{throw new IllegalStateException("provider unavailable");})).isInstanceOf(IllegalStateException.class);
        assertThat(second.chat("same",()->"released after failure")).isEqualTo("released after failure");
    }
    @Test void oauthStartedOnOneReplicaCanOnlyBePolledByItsOwnerFromAnotherReplica(){
        var jdbc=new JdbcTemplate(dataSource);
        jdbc.execute("create table if not exists chat_users(user_id varchar(255) primary key,hermes_profile varchar(64),created_at timestamptz)");
        jdbc.execute("create table if not exists hermes_oauth_sessions(user_id varchar(255) primary key references chat_users(user_id),session_id varchar(128),profile varchar(64),expires_at timestamptz)");
        var scope=mock(HermesUserScope.class);when(scope.profile("alice",false)).thenReturn("alice-profile");
        var dashboard=mock(HermesDashboardClient.class);
        when(dashboard.admin("POST","/api/providers/oauth/nous/start?profile=alice-profile",null)).thenReturn(Map.of("session_id","oauth-session-123"));
        when(dashboard.admin("GET","/api/providers/oauth/nous/poll/oauth-session-123?profile=alice-profile",null)).thenReturn(Map.of("status","pending"));
        var one=new HermesAccountService(jdbc,mock(ChatUserRepository.class),new ChatIdentity("owner"),dashboard,mock(HermesClient.class),mock(HermesModels.class),scope);
        var two=new HermesAccountService(jdbc,mock(ChatUserRepository.class),new ChatIdentity("owner"),dashboard,mock(HermesClient.class),mock(HermesModels.class),scope);
        one.startLogin("alice");
        assertThat(two.pollLogin("alice","oauth-session-123")).containsEntry("status","pending");
        assertThatThrownBy(()->two.pollLogin("bob","oauth-session-123")).isInstanceOfSatisfying(HermesException.class,e->assertThat(e.status()).isEqualTo(403));
        jdbc.update("update hermes_oauth_sessions set expires_at=now()-interval '1 second' where user_id='alice'");
        assertThatThrownBy(()->two.pollLogin("alice","oauth-session-123")).isInstanceOf(HermesException.class);
    }
}
