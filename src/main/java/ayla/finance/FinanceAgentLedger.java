package ayla.finance;

import static ayla.finance.FinanceData.*;
import ayla.chat.session.ChatUserRepository;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The agent interprets the conversation. Java validates money and writes to PostgreSQL. */
@Service
public class FinanceAgentLedger {
    public enum Unit { REAIS, CENTAVOS }
    public record RecordInput(Type type, String amount, Unit unit, String description,
                              String category, OffsetDateTime occurredAt) {}
    private final JdbcTemplate jdbc;
    private final FinanceService finance;
    private final ChatUserRepository users;
    public FinanceAgentLedger(JdbcTemplate jdbc, FinanceService finance, ChatUserRepository users) {
        this.jdbc=jdbc; this.finance=finance; this.users=users;
    }
    @Transactional
    public UUID begin(String user, UUID conversation, String message) {
        users.lockUser(user).orElseThrow();
        UUID id=UUID.randomUUID();
        jdbc.update("insert into finance_agent_turns(id,user_id,conversation_id,user_message) values (?,?,?,?)",id,user,conversation,message);
        return id;
    }
    public boolean recorded(String user, UUID turn) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select completed from finance_agent_turns where user_id=? and id=?",Boolean.class,user,turn));
    }
    public boolean householdUpdated(String user,UUID turn){return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from household_receipts where user_id=? and request_key like ?)",Boolean.class,user,"agent:"+turn+":%"));}
    @Transactional
    public Entry record(String user, UUID conversation, RecordInput input) {
        users.lockUser(user).orElseThrow();
        String description=description(input.description());
        Input entry=new Input(input.type(),cents(input.amount(),input.unit()),description,
                FinanceCategories.resolve(input.category(),description),input.occurredAt());
        UUID turn=jdbc.query("select id from finance_agent_turns where user_id=? and conversation_id=? order by sequence desc limit 1",
                (r,n)->r.getObject(1,UUID.class),user,conversation).stream().findFirst()
                .orElseThrow(()->new FinanceException(409,"Envie o pedido pela conversa antes de registrar."));
        var checkout=jdbc.queryForList("select financial_entry_id from shopping_checkouts where user_id=? and agent_turn_id=? and financial_entry_id is not null",UUID.class,user,turn);
        if(!checkout.isEmpty())return finance.get(user,checkout.getFirst());
        var result=finance.create(user,entry,"agent-turn:"+turn);
        jdbc.update("update finance_agent_turns set completed=true where id=?",turn);
        return result;
    }
    @Transactional
    public Entry update(String user, UUID id, Long version, RecordInput patch) {
        if (version==null) throw new FinanceException(400,"Consulte o lançamento antes de alterar.");
        var current=finance.get(user,id);
        if ((patch.amount()==null)!=(patch.unit()==null))
            throw new FinanceException(400,"Para alterar o valor, informe também se é em reais ou centavos.");
        return finance.update(user,id,new Edit(version,new Input(patch.type()==null?current.type():patch.type(),
                patch.amount()==null?current.amountCents():cents(patch.amount(),patch.unit()),
                patch.description()==null?current.description():description(patch.description()),
                patch.category()==null?current.category():patch.category(),
                patch.occurredAt()==null?current.occurredAt():patch.occurredAt())));
    }
    static String description(String text) {
        if (text==null || text.isBlank() || !text.replaceAll("(?i)r\\$","").matches("(?s).*\\p{L}.*") || text.strip().toLowerCase(Locale.ROOT).matches("(?:gasto|despesa|compra|outra compra|outros|não informado)"))
            throw new FinanceException(400,"Com o que você gastou ou de onde veio esse dinheiro?");
        return text.strip();
    }
    static long cents(String amount,Unit unit) {
        if (amount==null || unit==null) throw new FinanceException(400,"Falta o valor ou esclarecer se é em reais ou centavos.");
        String number=amount.strip().replace(',','.');
        if (!number.matches("[0-9]{1,16}(?:\\.[0-9]{1,2})?"))
            throw new FinanceException(400,"Não entendi o valor. Qual foi o valor exato?");
        try {
            String[] parts=number.split("\\."); long whole=Long.parseLong(parts[0]);
            long fraction=parts.length==1?0:Long.parseLong((parts[1]+"00").substring(0,2));
            if (unit==Unit.CENTAVOS && fraction!=0) throw new ArithmeticException();
            long result=unit==Unit.CENTAVOS?whole:Math.addExact(Math.multiplyExact(whole,100),fraction);
            if (result<=0 || result>FinanceService.MAX_CENTS) throw new ArithmeticException();
            return result;
        } catch (ArithmeticException|NumberFormatException e) {
            throw new FinanceException(400,"Esse valor não é válido. Pode conferir o valor exato?");
        }
    }
}
