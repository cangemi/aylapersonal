package ayla;

import static ayla.finance.FinanceData.*;
import static org.assertj.core.api.Assertions.*;
import ayla.finance.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class FinanceDatabaseTests {
    @Autowired FinanceService finance;
    @Autowired FinanceAgentLedger agent;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;
    private String user() { return "finance-test-"+UUID.randomUUID(); }
    private Input input(Type type,String amount,String at) {
        return new Input(type,Long.parseLong(amount),"Mercado","Alimentação",at==null?null:OffsetDateTime.parse(at));
    }
    private static void status(int code,org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(FinanceException.class,e->assertThat(e.status()).isEqualTo(code));
    }
    @Test void integerCentTotalsRespectLocalDayAndExcludeFutureMovements() {
        String user=user();
        finance.create(user,input(Type.INCOME,"100000","2026-01-01T00:00:00-03:00"),null);
        finance.create(user,input(Type.EXPENSE,"10","2026-01-31T23:59:59-03:00"),null);
        finance.create(user,input(Type.EXPENSE,"20","2026-02-01T00:00:00-03:00"),null);
        finance.create(user,input(Type.INCOME,"900000",OffsetDateTime.now().plusDays(3).toString()),null);
        var day=finance.summary(user,LocalDate.parse("2026-01-31"),LocalDate.parse("2026-01-31"),Instant.parse("2026-02-01T02:59:59.999Z"),Group.DAY);
        assertThat(day.period().expenseCents()).isEqualTo(10L);
        assertThat(day.balanceCents()).isEqualTo(99990L);
        assertThat(day.series()).extracting(Bucket::label).containsExactly("2026-01-31");
        var now=finance.summary(user,LocalDate.parse("2026-01-01"),LocalDate.now(),null,Group.MONTH);
        assertThat(now.balanceCents()).isEqualTo(99970L);
        assertThat(now.period().expenseCents()).isEqualTo(30L);
        assertThat(now.series()).extracting(Bucket::label).containsExactly("2026-01","2026-02");
        assertThat(finance.list(user,LocalDate.parse("2026-01-31"),LocalDate.parse("2026-01-31"),Type.EXPENSE,"alimentação",0,10).total()).isEqualTo(1);
    }
    @Test void crudChecksOwnershipVersionsAndPreservesReceiptAfterDeletion() {
        String alice=user(),bob=user();
        var input=input(Type.EXPENSE,"4530",null);
        var entry=finance.create(alice,input,"crud");
        assertThat(finance.create(alice,input,"crud").id()).isEqualTo(entry.id());
        status(409,()->finance.create(alice,input(Type.EXPENSE,"50",null),"crud"));
        status(404,()->finance.get(bob,entry.id()));
        status(404,()->finance.update(bob,entry.id(),new Edit(0L,input)));
        status(404,()->finance.delete(bob,entry.id(),0));
        assertThat(finance.list(bob,null,null,null,null,0,50).total()).isZero();
        var changed=finance.update(alice,entry.id(),new Edit(entry.version(),input(Type.EXPENSE,"4020",entry.occurredAt().toString())));
        assertThat(changed.version()).isEqualTo(1);
        assertThat(changed.amountCents()).isEqualTo(4020L);
        status(409,()->finance.update(alice,entry.id(),new Edit(0L,input)));
        status(409,()->finance.delete(alice,entry.id(),0));
        finance.delete(alice,entry.id(),changed.version());
        status(404,()->finance.create(alice,input,"crud"));
        assertThat(finance.list(alice,null,null,null,null,0,10).total()).isZero();
    }
    @Test void concurrentRetriesHaveExactlyOneReceiptAndOneExpense() throws Exception {
        String user=user(); var value=input(Type.EXPENSE,"1999",null);
        try(var pool=Executors.newFixedThreadPool(4)) {
            var start=new CountDownLatch(1); List<Future<Entry>> results=new ArrayList<>();
            for(int i=0;i<4;i++) results.add(pool.submit(()->{start.await();return finance.create(user,value,"same-request");}));
            start.countDown(); UUID id=results.getFirst().get(20,TimeUnit.SECONDS).id();
            for(var result:results) assertThat(result.get(20,TimeUnit.SECONDS).id()).isEqualTo(id);
        }
        assertThat(finance.list(user,null,null,null,null,0,50).total()).isEqualTo(1);
    }
    @Test void summariesAndChartsAreIsolatedAndEmptyBalanceIsZero() {
        String alice=user(),bob=user();
        finance.create(alice,input(Type.EXPENSE,"987","2026-01-02T12:00:00-03:00"),null);
        var aliceTotals=finance.summary(alice,LocalDate.parse("2026-01-01"),LocalDate.parse("2026-01-31"),null,Group.CATEGORY);
        assertThat(aliceTotals.balanceCents()).isEqualTo(-987L);
        assertThat(aliceTotals.series().getFirst().label()).isEqualTo("alimentação");
        var bobTotals=finance.summary(bob,null,null,null,null);
        assertThat(bobTotals.balanceCents()).isEqualTo(0L);
        assertThat(bobTotals.series()).isEmpty();
    }
    @Test void simpleAgentToolsValidateMissingFactsAndUseCurrentDatabaseValues() {
        String user=user(); UUID conversation=dialogConversation(user);
        agent.begin(user,conversation,"gastei 12");
        status(400,()->agent.record(user,conversation,new FinanceAgentLedger.RecordInput(Type.EXPENSE,"12",null,"Bala",null,null)));
        status(400,()->agent.record(user,conversation,new FinanceAgentLedger.RecordInput(Type.EXPENSE,"12",FinanceAgentLedger.Unit.CENTAVOS,null,null,null)));
        status(400,()->agent.record(user,conversation,new FinanceAgentLedger.RecordInput(Type.EXPENSE,"0.005",FinanceAgentLedger.Unit.REAIS,"Bala",null,null)));
        assertThat(finance.list(user,null,null,null,null,0,50).total()).isZero();
        UUID turn=agent.begin(user,conversation,"centavos, comprei uma bala");
        assertThat(agent.recorded(user,turn)).isFalse();
        var input=new FinanceAgentLedger.RecordInput(Type.EXPENSE,"12",FinanceAgentLedger.Unit.CENTAVOS,"Bala",null,null);
        var saved=agent.record(user,conversation,input);
        assertThat(agent.recorded(user,turn)).isTrue();
        assertThat(agent.record(user,conversation,input).id()).isEqualTo(saved.id());
        assertThat(saved.amountCents()).isEqualTo(12L);
        // A CRUD entry outside the conversation is included in live totals and can be edited by the agent.
        var external=finance.create(user,new Input(Type.EXPENSE,1000L,"Padaria","Alimentação",null),null);
        assertThat(finance.summary(user,null,null,null,null).period().expenseCents()).isEqualTo(1012L);
        var changed=agent.update(user,external.id(),external.version(),new FinanceAgentLedger.RecordInput(null,"20",FinanceAgentLedger.Unit.REAIS,null,null,null));
        assertThat(changed.description()).isEqualTo("Padaria");
        assertThat(changed.occurredAt()).isEqualTo(external.occurredAt());
        assertThat(finance.summary(user,null,null,null,null).period().expenseCents()).isEqualTo(2012L);
        status(400,()->agent.update(user,changed.id(),changed.version(),new FinanceAgentLedger.RecordInput(null,"2",null,null,null,null)));
        finance.delete(user,changed.id(),changed.version());
        assertThat(finance.summary(user,null,null,null,null).period().expenseCents()).isEqualTo(12L);
        agent.begin(user,conversation,"gastei quinze reais e cinquenta no café");
        assertThat(agent.record(user,conversation,new FinanceAgentLedger.RecordInput(Type.EXPENSE,"15,50",FinanceAgentLedger.Unit.REAIS,"Café",null,null)).amountCents()).isEqualTo(1550L);
    }
    @Test void conversationConfirmationDoesNotNeedToRepeatTheCurrencyWord() {
        String user=user(); UUID conversation=dialogConversation(user);
        agent.begin(user,conversation,"Comprei uma caneta por 30");
        // The agent asks "Foram 30 centavos?" and interprets the user's affirmation.
        UUID turn=agent.begin(user,conversation,"isso mesmo");
        var input=new FinanceAgentLedger.RecordInput(Type.EXPENSE,"30",FinanceAgentLedger.Unit.CENTAVOS,"Caneta",null,null);
        var entry=agent.record(user,conversation,input);
        assertThat(entry.amountCents()).isEqualTo(30L);
        assertThat(agent.recorded(user,turn)).isTrue();
        assertThat(agent.record(user,conversation,input).id()).isEqualTo(entry.id());
        assertThat(finance.list(user,null,null,null,null,0,50).total()).isEqualTo(1);
    }
    private UUID dialogConversation(String user) {
        UUID id=UUID.randomUUID();
        jdbc.update("insert into chat_users(user_id,created_at) values (?,CURRENT_TIMESTAMP) on conflict(user_id) do nothing",user);
        jdbc.update("insert into chat_conversations(id,user_id,hermes_session_id,title,created_at) values (?,?,?,'Financeiro',CURRENT_TIMESTAMP)",id,user,id.toString());
        return id;
    }

    @Test void migrationConvertsExistingReaisToCentsExactly() {
        String schema="finance_migration_"+UUID.randomUUID().toString().replace("-","");
        var old=org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("5").load();
        try {
            old.migrate();
            jdbc.update("insert into "+schema+".chat_users(user_id,created_at) values ('legacy',CURRENT_TIMESTAMP)");
            UUID reais=UUID.randomUUID(),cents=UUID.randomUUID();
            jdbc.update("insert into "+schema+".financial_entries(id,user_id,type,amount,description,category,occurred_at) values (?,'legacy','INCOME',15.50,'Teste','Teste',CURRENT_TIMESTAMP),(?,'legacy','EXPENSE',0.15,'Teste','Teste',CURRENT_TIMESTAMP)",reais,cents);
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
            assertThat(jdbc.queryForObject("select amount_cents from "+schema+".financial_entries where id=?",Long.class,reais)).isEqualTo(1550L);
            assertThat(jdbc.queryForObject("select amount_cents from "+schema+".financial_entries where id=?",Long.class,cents)).isEqualTo(15L);
            assertThat(jdbc.queryForObject("select data_type from information_schema.columns where table_schema=? and table_name='financial_entries' and column_name='amount_cents'",String.class,schema)).isEqualTo("bigint");
        } finally {
            jdbc.execute("drop schema if exists "+schema+" cascade");
        }
    }

    @Test void invalidMoneyDatesAndPaginationAreRejectedWithoutWriting() {
        String user=user();
        for(String amount:List.of("0","-1","9007199254740992")) status(400,()->finance.create(user,input(Type.INCOME,amount,null),null));
        status(400,()->finance.list(user,null,null,null,null,-1,50));
        status(400,()->finance.list(user,null,null,null,null,0,101));
        status(400,()->finance.summary(user,LocalDate.parse("2026-02-01"),LocalDate.parse("2026-01-01"),null,null));
        status(400,()->finance.summary(user,null,null,Instant.now().plusSeconds(3600),null));
        assertThat(finance.list(user,null,null,null,null,0,50).total()).isZero();
        finance.create(user,input(Type.INCOME,Long.toString(FinanceService.MAX_CENTS),null),null);
        finance.create(user,input(Type.INCOME,"1",null),null);
        status(422,()->finance.summary(user,null,null,null,null));
    }
}
