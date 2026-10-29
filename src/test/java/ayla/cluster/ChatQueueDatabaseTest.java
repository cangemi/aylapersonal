package ayla.cluster;

import ayla.chat.queue.*;
import ayla.chat.dto.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class ChatQueueDatabaseTest {
    @Container static final PostgreSQLContainer<?> database=new PostgreSQLContainer<>("postgres:17-alpine");
    JdbcTemplate jdbc;
    ChatQueueStore one,two;
    UUID conversationA,conversationB;
    @BeforeEach void setup() throws Exception {
        var source=new DriverManagerDataSource(database.getJdbcUrl(),database.getUsername(),database.getPassword());
        jdbc=new JdbcTemplate(source);
        jdbc.execute("drop table if exists chat_jobs,chat_conversations,auth_accounts cascade");
        jdbc.execute("create table auth_accounts(user_id varchar(255) primary key,enabled boolean default true)");
        jdbc.execute("create table chat_conversations(id uuid primary key)");
        jdbc.execute("insert into auth_accounts(user_id) values ('alice'),('bob')");
        conversationA=UUID.randomUUID();conversationB=UUID.randomUUID();
        jdbc.update("insert into chat_conversations values (?),(?)",conversationA,conversationB);
        try(var file=getClass().getResourceAsStream("/db/migration/V31__durable_chat_queue.sql")){
            jdbc.execute(new String(Objects.requireNonNull(file).readAllBytes(),StandardCharsets.UTF_8));
        }
        var transactions=new DataSourceTransactionManager(source);
        one=new ChatQueueStore(jdbc,transactions);two=new ChatQueueStore(jdbc,transactions);
    }
    UUID add(UUID conversation,String key){return one.insert("alice",conversation,key,"a".repeat(64),"WEB",null,new ChatRequest("Oi",null,conversation.toString()));}
    @Test void replicasProcessOneHeadPerConversationInOrderAndDeduplicateRetries() throws Exception {
        UUID first=add(conversationA,"first"),second=add(conversationA,"second"),other=add(conversationB,"other");
        assertThat(add(conversationA,"first")).isEqualTo(first);
        assertThatThrownBy(()->one.existing("alice","first","b".repeat(64),"WEB",null)).isInstanceOf(ayla.hermes.HermesException.class);
        List<ChatQueueStore.Work> claimed;
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var a=executor.submit(one::claim);var b=executor.submit(two::claim);
            claimed=List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        }
        assertThat(claimed.stream().map(ChatQueueStore.Work::id)).containsExactlyInAnyOrder(first,other);
        assertThat(one.claim()).isNull();
        var firstWork=claimed.stream().filter(work->work.id().equals(first)).findFirst().orElseThrow();
        one.waitAgain(firstWork);
        assertThat(one.claim()).isNull();
        jdbc.update("update chat_jobs set available_at=now() where id=?",first);
        var retry=two.claim();assertThat(retry.id()).isEqualTo(first);
        one.finish(retry,"COMPLETED",new ChatResponse("Resposta","session",conversationA.toString()),null);
        assertThat(two.claim().id()).isEqualTo(second);
        assertThat(one.get("alice",first,null).result().response()).isEqualTo("Resposta");
        one.acknowledge("alice",first,null);
        assertThat(one.pending("alice",conversationA,null).stream().map(ChatQueueStore.Job::id)).doesNotContain(first);
        assertThatThrownBy(()->two.get("bob",first,null)).isInstanceOf(ayla.hermes.HermesException.class);
        assertThat(jdbc.queryForObject("select request_json is null from chat_jobs where id=?",Boolean.class,first)).isTrue();
    }
    @Test void restartRecoversOnlyUndispatchedWorkAndNeverReplaysAnUncertainMutation(){
        UUID safe=add(conversationA,"safe"),uncertain=add(conversationB,"uncertain");
        var safeWork=one.claim();var uncertainWork=one.claim();
        one.dispatched(uncertainWork);
        jdbc.update("update chat_jobs set lease_until=now()-interval '1 second'");
        two.recover();
        assertThat(two.get("alice",safe,null).status()).isEqualTo("QUEUED");
        assertThat(two.get("alice",uncertain,null).status()).isEqualTo("NEEDS_REVIEW");
        assertThat(two.claim().id()).isEqualTo(safe);
        assertThat(two.claim()).isNull();
        assertThatThrownBy(()->one.dispatched(safeWork)).isInstanceOf(ayla.hermes.HermesException.class);
        assertThat(one.allowed(uncertainWork)).isTrue();
        jdbc.update("update auth_accounts set enabled=false where user_id='alice'");
        assertThat(one.allowed(uncertainWork)).isFalse();
    }
}
