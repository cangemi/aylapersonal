package ayla.finance;

import static ayla.finance.FinanceData.*;
import ayla.chat.session.ChatUserRepository;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class FinanceService {
    public static final long MAX_CENTS = 9_007_199_254_740_991L;
    public static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private final JdbcTemplate jdbc;
    private final ChatUserRepository users;
    private final ayla.chat.session.ChatIdentity identity;
    public FinanceService(JdbcTemplate jdbc, ChatUserRepository users, ayla.chat.session.ChatIdentity identity) { this.jdbc=jdbc; this.users=users; this.identity=identity; }
    private static FinanceException invalid(String message) { return new FinanceException(400, message); }
    private static final RowMapper<Entry> ENTRY = (r,n) -> new Entry(r.getObject("id",UUID.class),
            Type.valueOf(r.getString("type")),r.getLong("amount_cents"),"BRL",r.getString("description"),
            r.getString("category"),r.getTimestamp("occurred_at").toInstant().atZone(ZONE).toOffsetDateTime(),
            r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant(),r.getLong("version"));

    public Entry get(String user, UUID id) {
        return jdbc.query("select * from financial_entries where user_id=? and id=?",ENTRY,user,id)
                .stream().findFirst().orElseThrow(() -> new FinanceException(404,"Lançamento não encontrado."));
    }

    @Transactional
    public Entry create(String user, Input input, String requestKey) {
        // The per-user lock serializes receipt checks, including simultaneous retries.
        users.ensureUser(user,user.equals(identity.ownerId())?"default":null);
        users.lockUser(user).orElseThrow();
        Input value = normalize(input);
        String fingerprint = fingerprint(input, value);
        if (requestKey != null) {
            if (!requestKey.matches("[A-Za-z0-9_.:-]{1,160}")) throw invalid("Idempotency-Key inválida (até 160 caracteres).");
            var receipts = jdbc.queryForList("select fingerprint,entry_id from financial_receipts where user_id=? and request_key=?",user,requestKey);
            if (!receipts.isEmpty()) {
                if (!fingerprint.equals(receipts.getFirst().get("fingerprint")))
                    throw new FinanceException(409,"Essa chave já foi usada para outro lançamento.");
                return get(user,(UUID) receipts.getFirst().get("entry_id"));
            }
        }
        UUID id=UUID.randomUUID();
        jdbc.update("insert into financial_entries(id,user_id,type,amount_cents,description,category,occurred_at) values (?,?,?,?,?,?,?)",
                id,user,value.type().name(),value.amountCents(),value.description(),value.category(),Timestamp.from(value.occurredAt().toInstant()));
        if (requestKey != null) jdbc.update("insert into financial_receipts(user_id,request_key,fingerprint,entry_id) values (?,?,?,?)",user,requestKey,fingerprint,id);
        return get(user,id);
    }

    @Transactional
    public Entry update(String user, UUID id, Edit edit) {
        if (edit == null || edit.version() == null || edit.version()<0) throw invalid("Informe a versão atual do lançamento.");
        get(user,id);
        Input value=normalize(edit.entry());
        if (jdbc.update("update financial_entries set type=?,amount_cents=?,description=?,category=?,occurred_at=?,updated_at=CURRENT_TIMESTAMP,version=version+1 where user_id=? and id=? and version=?",
                value.type().name(),value.amountCents(),value.description(),value.category(),Timestamp.from(value.occurredAt().toInstant()),user,id,edit.version())==0)
            throw new FinanceException(409,"O lançamento foi alterado. Consulte-o novamente antes de editar.");
        return get(user,id);
    }

    @Transactional
    public void delete(String user, UUID id, long version) {
        get(user,id);
        if (jdbc.update("delete from financial_entries where user_id=? and id=? and version=?",user,id,version)==0)
            throw new FinanceException(409,"O lançamento foi alterado. Consulte-o novamente antes de excluir.");
    }

    public Entries list(String user, LocalDate from, LocalDate to, Type type, String category, int page, int size) {
        if (page<0 || page>1_000_000 || size<1 || size>100) throw invalid("Página deve ser positiva e tamanho entre 1 e 100.");
        if (from!=null && to!=null && to.isBefore(from)) throw invalid("Período inválido.");
        StringBuilder where=new StringBuilder(" where user_id=?");
        List<Object> args=new ArrayList<>(); args.add(user);
        if (from!=null) { where.append(" and occurred_at>=?"); args.add(start(from)); }
        if (to!=null) { where.append(" and occurred_at<?"); args.add(start(to.plusDays(1))); }
        if (type!=null) { where.append(" and type=?"); args.add(type.name()); }
        if (category!=null) { where.append(" and lower(category)=lower(?)"); args.add(category.strip()); }
        long count=jdbc.queryForObject("select count(*) from financial_entries"+where,Long.class,args.toArray());
        args.add(size); args.add((long)page*size);
        var items=jdbc.query("select * from financial_entries"+where+" order by occurred_at desc,id limit ? offset ?",ENTRY,args.toArray());
        return new Entries(items,count,page,size);
    }

    public Summary summary(String user, LocalDate from, LocalDate to, Instant asOf, Group group) {
        return summary(user, from, to, asOf, group, null);
    }

    public Summary summary(String user, LocalDate from, LocalDate to, Instant asOf, Group group, String category) {
        Instant now=Instant.now();
        Instant cutoff=asOf==null?now:asOf;
        if (cutoff.isAfter(now)) throw invalid("asOf não pode estar no futuro.");
        LocalDate today=cutoff.atZone(ZONE).toLocalDate();
        LocalDate first=from==null?today.withDayOfMonth(1):from;
        LocalDate last=to==null?today:to;
        if (last.isBefore(first) || ChronoUnit.DAYS.between(first,last)>3660) throw invalid("Use um período de até 10 anos, com início anterior ao fim.");
        Group grouping=group==null?Group.DAY:group;
        Timestamp end=start(last.plusDays(1));
        Timestamp stop=Timestamp.from(cutoff);
        boolean filtered=category!=null && !category.isBlank();
        String categoryFilter=filtered?" and lower(category)=lower(?)":"";
        Object[] args=filtered?new Object[]{user,start(first),end,stop,category.strip()}:new Object[]{user,start(first),end,stop};
        var period=jdbc.queryForObject("select coalesce(sum(amount_cents) filter(where type='INCOME'),0) income, coalesce(sum(amount_cents) filter(where type='EXPENSE'),0) expense from financial_entries where user_id=? and occurred_at>=? and occurred_at<? and occurred_at<=?"+categoryFilter,
                (r,n)->totals(r),args);
        long balance=jdbc.queryForObject("select coalesce(sum(case when type='INCOME' then amount_cents else -amount_cents end),0) from financial_entries where user_id=? and occurred_at<=?",(r,n)->checked(r.getString(1)),user,stop);
        String expression=switch(grouping) {
            case DAY -> "to_char(occurred_at AT TIME ZONE 'America/Sao_Paulo','YYYY-MM-DD')";
            case MONTH -> "to_char(occurred_at AT TIME ZONE 'America/Sao_Paulo','YYYY-MM')";
            case CATEGORY -> "lower(category)";
        };
        var buckets=jdbc.query("select "+expression+" label, coalesce(sum(amount_cents) filter(where type='INCOME'),0) income, coalesce(sum(amount_cents) filter(where type='EXPENSE'),0) expense from financial_entries where user_id=? and occurred_at>=? and occurred_at<? and occurred_at<=?"+categoryFilter+" group by 1 order by 1",
                (r,n)->{var t=totals(r);return new Bucket(r.getString("label"),t.incomeCents(),t.expenseCents(),t.netCents());},args);
        return new Summary("BRL",ZONE.getId(),first,last,cutoff,balance,period,grouping,buckets);
    }

    private static Totals totals(ResultSet r) throws SQLException {
        long income=checked(r.getString("income")),expense=checked(r.getString("expense"));
        return new Totals(income,expense,checked(BigInteger.valueOf(income).subtract(BigInteger.valueOf(expense)).toString()));
    }
    private static long checked(String integer) {
        BigInteger value=new BigInteger(integer);
        if (value.abs().compareTo(BigInteger.valueOf(MAX_CENTS))>0)
            throw new FinanceException(422,"Total financeiro fora do limite suportado em centavos.");
        return value.longValueExact();
    }
    // Keep historical idempotency fingerprints valid after converting V5 rows to cents.
    private static String legacyAmount(long cents) {
        return (cents/100)+"."+String.format(java.util.Locale.ROOT,"%02d",cents%100);
    }
    private static Timestamp start(LocalDate date) { return Timestamp.from(date.atStartOfDay(ZONE).toInstant()); }
    private static Input normalize(Input input) {
        if (input==null || input.type()==null || input.amountCents()==null || input.amountCents()<=0 || input.amountCents()>MAX_CENTS)
            throw invalid("Informe tipo INCOME/EXPENSE e amountCents inteiro positivo, até 9007199254740991 centavos.");
        if (input.description()==null || input.description().isBlank() || input.description().length()>300
                || input.category()==null || input.category().isBlank() || input.category().length()>80)
            throw invalid("Informe descrição (até 300 caracteres) e categoria (até 80).");
        var date=input.occurredAt()==null?OffsetDateTime.now(ZONE):input.occurredAt();
        if (date.getYear()<1900 || date.getYear()>2200) throw invalid("Data fora do intervalo de 1900 a 2200.");
        return new Input(input.type(),input.amountCents(),input.description().strip(),input.category().strip(),date);
    }
    private static String fingerprint(Input original, Input normalized) {
        // Length-prefix each field to avoid collisions from separators inside descriptions.
        var fields=List.of(normalized.type().name(),legacyAmount(normalized.amountCents()),normalized.description(),normalized.category(),
                original.occurredAt()==null?"NOW":original.occurredAt().toInstant().toString());
        StringBuilder data=new StringBuilder(); fields.forEach(v->data.append(v.length()).append(':').append(v));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data.toString().getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
