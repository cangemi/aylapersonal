package ayla.investment;

import static ayla.investment.PortfolioData.*;
import ayla.chat.session.ChatIdentity;
import ayla.chat.session.ChatUserRepository;
import ayla.finance.FinanceException;
import ayla.finance.FinanceService;
import ayla.finance.InvestmentCategories;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PortfolioService {
    private final JdbcTemplate jdbc;
    private final CdiService cdi;
    private final InvestmentService legacy;
    private final ChatUserRepository users;
    private final ChatIdentity identity;
    private final Validator validator;

    public PortfolioService(JdbcTemplate jdbc,CdiService cdi,InvestmentService legacy,ChatUserRepository users,ChatIdentity identity,Validator validator) {
        this.jdbc=jdbc;this.cdi=cdi;this.legacy=legacy;this.users=users;this.identity=identity;this.validator=validator;
    }
    private static final RowMapper<Position> POSITION = (r,n) -> new Position(r.getObject("id",UUID.class),r.getString("name"),
        r.getString("institution"),AssetType.valueOf(r.getString("asset_type")),r.getString("ticker"),r.getObject("opened_on",LocalDate.class),
        r.getLong("opening_balance_cents"),r.getLong("opening_invested_cents"),new Rate(RateType.valueOf(r.getString("rate_type")),
        r.getBigDecimal("rate_percent"),DayBasis.valueOf(r.getString("day_basis"))),r.getObject("maturity_date",LocalDate.class),r.getString("notes"),r.getLong("version"));

    public Portfolio portfolio(String user) {
        LocalDate today=today();
        var positions=jdbc.query("select * from investment_positions where user_id=? order by created_at,id",POSITION,user);
        var events=new HashMap<UUID,List<Event>>();
        for(var position:positions)events.put(position.id(),events(position.id()));
        var snapshot=snapshot(positions,events,today);
        var views=positions.stream().map(position->PortfolioEngine.replay(position,events.get(position.id()),snapshot,today)).toList();
        var warnings=new ArrayList<String>();
        Legacy previous=null;
        if(Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists(select 1 from investment_settings where user_id=? and baseline_date is not null)
            or exists(select 1 from financial_entries where user_id=? and lower(category) in (
            """+InvestmentCategories.SQL_LIST+"))",Boolean.class,user,user))) {
            try {
                var old=legacy.overview(user);
                previous=new Legacy(old.balanceCents(),old.netInvestedCents(),old.gainCents(),old.monthYieldCents());
                if(old.cdi().stale())warnings.add("A caixinha existente usa um CDI desatualizado.");
            } catch(FinanceException unavailable) {
                warnings.add("Não foi possível calcular a caixinha existente. Ela está preservada e não foi incluída nos totais desta consulta.");
            }
        }
        long balance=previous==null?0:previous.balanceCents(),net=previous==null?0:previous.netInvestedCents(),
            gain=previous==null?0:previous.gainCents(),month=previous==null?0:previous.monthGainCents(),income=0;
        for(var view:views){balance=Math.addExact(balance,view.balanceCents());net=Math.addExact(net,view.netInvestedCents());
            gain=Math.addExact(gain,view.gainCents());month=Math.addExact(month,view.monthGainCents());income=Math.addExact(income,view.incomeCents());}
        return new Portfolio(today,balance,net,gain,income,month,views,previous,warnings);
    }

    public View get(String user,UUID id) {
        var position=owned(user,id,false);
        var events=events(id);
        return PortfolioEngine.replay(position,events,snapshot(List.of(position),Map.of(id,events),today()),today());
    }

    public InvestmentAnalytics.Dashboard analytics(String user,int year) {
        LocalDate today=today();
        if(year==0)year=today.getYear();
        if(year<1990||year>today.getYear())throw error(400,"Escolha um ano entre 1990 e o ano atual.");
        var positions=new ArrayList<>(jdbc.query("select * from investment_positions where user_id=? order by created_at,id",POSITION,user));
        var allEvents=new HashMap<UUID,List<Event>>();
        positions.forEach(position->allEvents.put(position.id(),events(position.id())));
        var warnings=new ArrayList<String>();
        UUID legacyId=UUID.nameUUIDFromBytes(("legacy-investment:"+user).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var baseline=legacy.baseline(user).orElse(null);
        var legacyEvents=jdbc.query("select id,type,amount_cents,occurred_at from financial_entries where user_id=? and lower(category) in ("
            +InvestmentCategories.SQL_LIST+") and occurred_at<=? order by occurred_at,id",(r,n)->new Event(r.getObject("id",UUID.class),
            "EXPENSE".equals(r.getString("type"))?EventType.DEPOSIT:EventType.WITHDRAWAL,
            r.getTimestamp("occurred_at").toInstant().atZone(FinanceService.ZONE).toLocalDate(),r.getLong("amount_cents"),"",null),
            user,java.sql.Timestamp.from(java.time.Instant.now()));
        if(baseline!=null||!legacyEvents.isEmpty()){
            LocalDate from=baseline==null?legacyEvents.getFirst().date():baseline.date();
            positions.add(new Position(legacyId,"Caixinha Nubank · cadastro anterior","Nubank",AssetType.CAIXINHA,"",from,
                baseline==null?0:baseline.balanceCents(),baseline==null?0:baseline.investedCents(),
                new Rate(RateType.CDI,legacy.percent(user),DayBasis.BUSINESS),null,"",0));
            allEvents.put(legacyId,baseline==null?legacyEvents:legacyEvents.stream().filter(event->event.date().isAfter(baseline.date())).toList());
            if(baseline!=null)warnings.add("Caixinha anterior: os gráficos por período começam na data do saldo de partida. Ganhos já embutidos nesse saldo não são distribuídos retroativamente por dia, mês ou ano.");
        }
        var snapshot=snapshot(positions,allEvents,today);
        var holdings=new ArrayList<InvestmentAnalytics.Holding>();
        for(var position:positions){
            try{holdings.add(PortfolioEngine.analyze(position.id().equals(legacyId)?"legacy":position.id().toString(),
                position,allEvents.get(position.id()),snapshot,today,year));}
            catch(FinanceException unavailable){warnings.add("Aplicação fora dos gráficos desta consulta: "+position.name()+". "+unavailable.getMessage());}
        }
        if(snapshot==null&&positions.stream().anyMatch(position->position.rate().type()==RateType.CDI))
            warnings.add("CDI indisponível: períodos sem dados suficientes ficam sem rendimento calculado.");
        else if(snapshot!=null&&snapshot.stale())warnings.add("O CDI está desatualizado. O painel não preenche taxas ausentes com projeções.");
        int firstYear=positions.stream().mapToInt(position->position.openedOn().getYear()).min().orElse(today.getYear());
        return new InvestmentAnalytics.Dashboard(year,firstYear,today,snapshot==null?null:snapshot.latestDate(),List.copyOf(holdings),List.copyOf(warnings));
    }

    @Transactional
    public View create(String user,Input input) {
        return create(user,input,null);
    }

    @Transactional
    public View create(String user,Input input,String requestKey) {
        validate(input);
        if(requestKey!=null&&!requestKey.matches("[A-Za-z0-9_.:-]{1,160}"))throw error(400,"Idempotency-Key inválida.");
        users.ensureUser(user,user.equals(identity.ownerId())?"default":null);
        // Serialize the account limit as well as concurrent portfolio changes.
        jdbc.queryForList("select user_id from chat_users where user_id=? for update",user);
        String fingerprint=fingerprint(input);
        if(requestKey!=null){
            var receipts=jdbc.queryForList("select id,request_fingerprint from investment_positions where user_id=? and request_key=?",user,requestKey);
            if(!receipts.isEmpty()){
                if(!fingerprint.equals(receipts.getFirst().get("request_fingerprint")))throw error(409,"Esta chave já foi usada para outra aplicação.");
                return get(user,(UUID)receipts.getFirst().get("id"));
            }
        }
        if(jdbc.queryForObject("select count(*) from investment_positions where user_id=?",Long.class,user)>=100)
            throw error(400,"A carteira aceita até 100 aplicações.");
        UUID id=UUID.randomUUID();
        jdbc.update("""
            insert into investment_positions(id,user_id,name,institution,asset_type,ticker,opened_on,opening_balance_cents,
            opening_invested_cents,rate_type,rate_percent,day_basis,maturity_date,notes,request_key,request_fingerprint) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,user,input.name().strip(),input.institution().strip(),input.assetType().name(),clean(input.ticker()),input.openedOn(),
            input.openingBalanceCents(),input.openingInvestedCents(),input.rateType().name(),input.ratePercent(),basis(input).name(),input.maturityDate(),clean(input.notes()),requestKey,fingerprint);
        return get(user,id);
    }

    @Transactional
    public View update(String user,UUID id,Input input) {
        validate(input);
        var previous=owned(user,id,true);
        requireVersion(previous,input.version());
        if(!previous.openedOn().equals(input.openedOn()) || previous.openingBalanceCents()!=input.openingBalanceCents()
            || previous.openingInvestedCents()!=input.openingInvestedCents())
            throw error(400,"Para corrigir o saldo atual, registre uma atualização de saldo. O saldo de partida e a data original são preservados.");
        var existing=events(id);
        Rate current=existing.stream().filter(event->event.type()==EventType.RATE_CHANGE).reduce((a,b)->b).map(Event::rate).orElse(previous.rate());
        if(current.type()!=input.rateType() || current.percent().compareTo(input.ratePercent())!=0 || current.dayBasis()!=basis(input))
            jdbc.update("""
                insert into investment_position_events(id,position_id,event_type,occurred_on,amount_cents,rate_type,rate_percent,day_basis,notes)
                values (?,?,'RATE_CHANGE',?,0,?,?,?,'Alteração da regra de rendimento')
                """,UUID.randomUUID(),id,today(),input.rateType().name(),input.ratePercent(),basis(input).name());
        jdbc.update("""
            update investment_positions set name=?,institution=?,asset_type=?,ticker=?,maturity_date=?,notes=?,version=version+1,updated_at=now()
            where id=? and user_id=?
            """,input.name().strip(),input.institution().strip(),input.assetType().name(),clean(input.ticker()),input.maturityDate(),clean(input.notes()),id,user);
        return get(user,id);
    }

    @Transactional
    public View addEvent(String user,UUID id,EventInput input) {
        valid(input);
        var position=owned(user,id,true);
        requireVersion(position,input.version());
        if(input.type()==EventType.RATE_CHANGE)throw error(400,"Altere a regra de rendimento pela edição da aplicação.");
        if(input.date().isBefore(position.openedOn())||input.date().isAfter(today()))throw error(400,"A movimentação deve estar entre a data inicial e hoje.");
        if(input.type()!=EventType.VALUATION && input.amountCents()==0)throw error(400,"Informe um valor maior que zero.");
        jdbc.update("insert into investment_position_events(id,position_id,event_type,occurred_on,amount_cents,notes) values (?,?,?,?,?,?)",
            UUID.randomUUID(),id,input.type().name(),input.date(),input.amountCents(),clean(input.notes()));
        jdbc.update("update investment_positions set version=version+1,updated_at=now() where id=?",id);
        return get(user,id); // Replay validates every withdrawal; failure rolls back the event.
    }

    @Transactional
    public View updateEvent(String user,UUID id,UUID eventId,EventInput input) {
        valid(input);
        var position=owned(user,id,true);requireVersion(position,input.version());
        if(input.type()==EventType.RATE_CHANGE)throw error(400,"Altere a taxa pela edição da aplicação.");
        if(input.date().isBefore(position.openedOn())||input.date().isAfter(today()))throw error(400,"A movimentação deve estar entre a data inicial e hoje.");
        if(input.type()!=EventType.VALUATION&&input.amountCents()==0)throw error(400,"Informe um valor maior que zero.");
        if(jdbc.update("update investment_position_events set event_type=?,occurred_on=?,amount_cents=?,notes=? where id=? and position_id=? and event_type<>'RATE_CHANGE'",
            input.type().name(),input.date(),input.amountCents(),clean(input.notes()),eventId,id)==0)throw error(404,"Movimentação editável não encontrada.");
        jdbc.update("update investment_positions set version=version+1,updated_at=now() where id=?",id);
        return get(user,id);
    }

    @Transactional
    public View deleteEvent(String user,UUID id,UUID eventId,long version) {
        var position=owned(user,id,true);requireVersion(position,version);
        if(jdbc.update("delete from investment_position_events where id=? and position_id=?",eventId,id)==0)throw error(404,"Movimentação não encontrada.");
        jdbc.update("update investment_positions set version=version+1,updated_at=now() where id=?",id);
        return get(user,id);
    }

    @Transactional
    public void delete(String user,UUID id,long version) {
        var position=owned(user,id,true);requireVersion(position,version);
        jdbc.update("delete from investment_positions where id=? and user_id=?",id,user);
    }

    private Position owned(String user,UUID id,boolean lock) {
        return jdbc.query("select * from investment_positions where user_id=? and id=?"+(lock?" for update":""),POSITION,user,id)
            .stream().findFirst().orElseThrow(()->error(404,"Aplicação não encontrada."));
    }
    private List<Event> events(UUID id) {
        return jdbc.query("select * from investment_position_events where position_id=? order by occurred_on,created_at,id",(r,n)->new Event(
            r.getObject("id",UUID.class),EventType.valueOf(r.getString("event_type")),r.getObject("occurred_on",LocalDate.class),r.getLong("amount_cents"),
            r.getString("notes"),r.getString("rate_type")==null?null:new Rate(RateType.valueOf(r.getString("rate_type")),r.getBigDecimal("rate_percent"),
            DayBasis.valueOf(r.getString("day_basis")))),id);
    }
    private CdiService.Snapshot snapshot(List<Position> positions,Map<UUID,List<Event>> events,LocalDate today) {
        LocalDate first=positions.stream().filter(p->p.rate().type()==RateType.CDI || events.get(p.id()).stream()
            .anyMatch(e->e.rate()!=null && e.rate().type()==RateType.CDI)).map(Position::openedOn).min(LocalDate::compareTo).orElse(null);
        if(first==null)return null;
        try{return cdi.load(first,today);}catch(FinanceException unavailable){return null;}
    }
    private void validate(Input input) {
        valid(input);
        if(input.openedOn().isBefore(LocalDate.of(1990,1,1))||input.openedOn().isAfter(today()))throw error(400,"Informe uma data inicial entre 1990 e hoje.");
        if(input.maturityDate()!=null&&input.maturityDate().isBefore(input.openedOn()))throw error(400,"O vencimento deve ser igual ou posterior à data inicial.");
    }
    private void valid(Object input){if(input==null||!validator.validate(input).isEmpty())throw error(400,"Confira os campos, valores e taxas da aplicação.");}
    private static DayBasis basis(Input input){return input.rateType()==RateType.CDI?DayBasis.BUSINESS:input.dayBasis();}
    private static String clean(String value){return value==null?"":value.strip();}
    private static String fingerprint(Input input){
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static void requireVersion(Position position,Long version){if(version==null||version!=position.version())throw error(409,"A aplicação mudou. Atualize a carteira antes de salvar novamente.");}
    private static LocalDate today(){return LocalDate.now(FinanceService.ZONE);}
    private static FinanceException error(int status,String message){return new FinanceException(status,message);}
}
