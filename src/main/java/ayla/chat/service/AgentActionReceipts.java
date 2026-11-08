package ayla.chat.service;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** A fluent answer is never evidence that a tool changed the user's data. */
@Service
public class AgentActionReceipts {
    private final JdbcTemplate jdbc;
    public AgentActionReceipts(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static final Map<String,Set<String>> MUTATIONS=Map.of(
        "finance",Set.of("record","update","delete"),
        "investment",Set.of("save_position","event","update_event","delete_event","delete_position"),
        "household",Set.of("apply","checkout"),
        "calendar",Set.of("save","apply","cancel","cancel_many","remind","google_disconnect","google_sync"),
        "alarm",Set.of("save","delete","confirm"),
        "memory",Set.of("save","delete","clear"));
    public static boolean mutation(String domain,String operation){return MUTATIONS.getOrDefault(domain,Set.of()).contains(operation);}
    public void record(String session,String domain,String operation){
        if(!mutation(domain,operation)||session==null||session.isBlank())return;
        jdbc.update("""
            insert into agent_action_receipts(id,turn_id,domain,operation)
            select ?,t.id,?,? from finance_agent_turns t join chat_conversations c on c.id=t.conversation_id
            where c.hermes_session_id=? and c.user_id=t.user_id order by t.sequence desc limit 1
            """,UUID.randomUUID(),domain,operation,session);
    }
    public Set<String> missingDomains(String user,UUID turn,String message,String response){
        var claimed=claimedDomains(response);
        String normalized=Normalizer.normalize(Objects.toString(response,""),Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT);
        if(claimed.isEmpty()&&normalized.length()<160&&normalized.matches("(?s)^\\s*(pronto|feito|anotei|salvei|registrei|concluido)\\b[^?]*")
                &&!normalized.matches("(?s).*\\b(nao|vou|preciso|falta)\\b.*")){
            String request=Normalizer.normalize(Objects.toString(message,""),Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT);
            var inferred=new LinkedHashSet<String>();
            DOMAINS.forEach((domain,pattern)->{if(pattern.matcher(request).find())inferred.add(domain);});
            if(request.matches("(?s).*\\b(gastei|paguei|recebi|ganhei)\\b.*"))inferred.add("finance");
            claimed=inferred;
        }
        if(claimed.isEmpty())return Set.of();
        var actual=jdbc.queryForList("""
            select distinct r.domain from agent_action_receipts r join finance_agent_turns t on t.id=r.turn_id
            where t.user_id=? and t.id=?
            """,String.class,user,turn);
        var missing=new LinkedHashSet<>(claimed);missing.removeAll(actual);return missing;
    }
    private static final Pattern DONE=Pattern.compile("\\b(anotei|registrei|salvei|cadastrei|adicionei|inclui|criei|atualizei|alterei|removi|exclui|apaguei|agendei|cancelei|configurei|resgatei|retirei|apliquei|transferi|marquei|memorizei|guardei|efetuei|realizei|conclui|concluid[oa]s?|registrad[oa]s?|salv[oa]s?|agendad[oa]s?|cancelad[oa]s?|atualizad[oa]s?|removid[oa]s?|excluid[oa]s?|efetuad[oa]s?|realizad[oa]s?|criad[oa]s?|adicionad[oa]s?|configurad[oa]s?)\\b");
    private static final Map<String,Pattern> DOMAINS=Map.of(
        "finance",Pattern.compile("r\\$|\\b(despesa|receita|gasto|lancamento|financeiro)\\b"),
        "investment",Pattern.compile("\\b(caixinha|resgate|aporte|investimento|aplicacao|carteira)\\b"),
        "household",Pattern.compile("\\b(lista|carrinho|despensa|estoque|compra de mercado)\\b"),
        "calendar",Pattern.compile("\\b(compromisso|evento|reuniao|agenda|lembrete)\\b"),
        "alarm",Pattern.compile("\\b(alarme|despertador)\\b"),
        "memory",Pattern.compile("\\b(memoria|preferencia)\\b"));
    static Set<String> claimedDomains(String response){
        String text=Normalizer.normalize(Objects.toString(response,""),Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT);
        var domains=new LinkedHashSet<String>();
        for(String sentence:text.split("[.!?\\n]+")){
            if(!DONE.matcher(sentence).find()||sentence.matches("(?s).*\\b(nao|ainda nao|vou|posso|poderia|preciso|para|se)\\b.{0,70}"+DONE.pattern()+".*"))continue;
            var sentenceDomains=new LinkedHashSet<String>();
            DOMAINS.forEach((domain,pattern)->{if(pattern.matcher(sentence).find())sentenceDomains.add(domain);});
            // A resgate/caixinha result may include reais; require the investment receipt,
            // rather than a separate finance write that would duplicate the movement.
            if((sentenceDomains.contains("investment")||sentenceDomains.contains("household"))
                    &&!sentence.matches("(?s).*\\b(despesa|receita|gasto|lancamento|financeiro)\\b.*"))sentenceDomains.remove("finance");
            domains.addAll(sentenceDomains);
        }
        return domains;
    }
}
