package ayla.chat.service;

import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ayla.localmodel.LocalModelClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Decide no modelo local pequeno se a conversa pode ser respondida ali mesmo ou precisa do Hermes.
 * Quando houver ferramenta, devolve uma frase de espera contextual; quando não houver, resposta direta.
 * Tudo que falhar cai fora do caminho local e o turno segue normalmente pela fila.
 */
@Service
public class LocalTurnRouter {
    private static final JsonMapper json=JsonMapper.builder().build();
    private static final Pattern FENCE=Pattern.compile("(?s)```(?:json)?\\s*(.*?)\\s*```");
    private static final Pattern JSON_OBJECT=Pattern.compile("(?s)\\{.*\\}");
    private static final String MONEY="(?:saldo|extrato|fatura|boleto|pix|despesa|receitas?(?!\\s+de)|lancamento|gasto|gastei|recebi|paguei|pagamento|dinheiro|reais|centavos?|nota\\s+fiscal|comprovante|salarios?|poupanca|invest\\w*|aporte|resgate|cdb|tesouro|dividas?|orcamento|transacoes?|r\\$)";
    private static final String AGENDA="(?:agenda|marcar|marcando|marquei|compromisso|consulta|reunioes?|remarcar|lembrete|lembra|lembrou|lembrar|alarme|calendario|disponibilidade|agendar|agendei|cancelar)";
    private static final String SHOP="(?:comprar|compras|comprei|carrinho|despensa|estoque|mercado|supermercado|checkout|precos?|lista\\s+de\\s+compras)";
    private static final String ACTION="(?:registrar|registrei|anotei|anotar|salvei|salvar|cadastrei|cadastrar|editar|excluir|apagar|ajustar|transferir|confirmar|finalizar|executar)";
    private static final String CURRENT="(?:noticias?|clima|cotacoes?|dolares?|eleicao|ibovespa|inflacao|selic|ipca|pesquisa|pesquise|google|internet|memoria|esqueci)";
    private static final Pattern MUST_ROUTE=Pattern.compile("\\b(?:"+MONEY+"|"+AGENDA+"|"+SHOP+"|"+ACTION+"|"+CURRENT+")\\b|\\br\\$\\s*\\d",Pattern.CASE_INSENSITIVE);
    private static final Pattern MONEY_ROUTE=Pattern.compile("\\b"+MONEY+"\\b",Pattern.CASE_INSENSITIVE);
    private static final Pattern AGENDA_ROUTE=Pattern.compile("\\b"+AGENDA+"\\b",Pattern.CASE_INSENSITIVE);
    private static final Pattern SHOP_ROUTE=Pattern.compile("\\b"+SHOP+"\\b",Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION_ROUTE=Pattern.compile("\\b"+ACTION+"\\b",Pattern.CASE_INSENSITIVE);
    private final LocalModelClient client;
    private final JdbcTemplate jdbc;
    private final ayla.hermes.HermesSettingsService settings;
    private final ayla.auth.UserProfileService profiles;
    private final Map<UUID,Deque<String>> localTurns=new ConcurrentHashMap<>();
    public LocalTurnRouter(LocalModelClient client,JdbcTemplate jdbc,ayla.hermes.HermesSettingsService settings,ayla.auth.UserProfileService profiles) {
        this.client=client;this.jdbc=jdbc;this.settings=settings;this.profiles=profiles;
    }
    public record Decision(boolean reply,String hold,String text) {}
    /** Responde no modelo local ou devolve a frase de espera; hold nulo significa seguir pela fila sem aviso. */
    public Decision decide(String user,UUID conversation,String message,boolean live,boolean interrupted) {
        if(message==null||message.isBlank())return new Decision(false,null,null);
        String prompt=message.strip();
        if(live&&interrupted)prompt+="\nObs.: a pessoa pode ter interrompido a fala anterior; priorize o que ela acabou de dizer.";
        String content=client.complete(system(user),history(user,conversation),prompt);
        if(content==null)return new Decision(false,null,null);
        var parsed=parse(content);
        if("reply".equalsIgnoreCase(Objects.toString(parsed.get("action"),""))) {
            String text=Objects.toString(parsed.get("text"),"").strip();
            if(!text.isBlank()&&routable(message,text))return new Decision(true,null,text);
        }
        return new Decision(false,holdFor(message),null);
    }
    /** Somente papo comum e fatos já ditos ficam no modelo local; tudo que exige dado ou ferramenta vai para o Hermes. */
    private static boolean routable(String message,String reply) {
        String normalizedMessage=normalize(message),normalizedReply=normalize(reply);
        return !MUST_ROUTE.matcher(normalizedMessage).find()
                && !MUST_ROUTE.matcher(normalizedReply).find()
                && SpontaneousVoice.lighthearted(normalizedMessage,normalizedReply);
    }
    /** Frase curta contextual para os casos em que o modelo local não pôde escolher a espera. */
    public static String holdFor(String message) {
        String text=normalize(message);
        if(MONEY_ROUTE.matcher(text).find())return "Deixa eu conferir isso no financeiro, um instante.";
        if(AGENDA_ROUTE.matcher(text).find())return "Deixa eu checar na agenda, só um momento.";
        if(SHOP_ROUTE.matcher(text).find())return "Deixa eu olhar a lista de compras, um instante.";
        if(ACTION_ROUTE.matcher(text).find())return "Só um instante, deixa eu executar isso.";
        return "Deixa eu ver isso, um instante.";
    }
    private String system(String user) {
        var prompt=new StringBuilder(AylaInstructions.LOCAL);
        try {
            String custom=settings==null?null:settings.get(user).getInstructions();
            if(custom!=null&&!custom.isBlank())
                prompt.append("\nPreferências adicionais da conta:\n").append(custom,0,Math.min(custom.length(),1500));
        }catch(RuntimeException ignored){}
        try {
            if(profiles!=null)prompt.append("\n").append(profiles.instructions(user));
        }catch(RuntimeException ignored){}
        return prompt.toString();
    }
    private List<Map<String,String>> history(String user,UUID conversation) {
        try {
            var rows=jdbc.query("""
                    select user_message,assistant_message from conversation_context_turns
                    where user_id=? and conversation_id=? order by created_at desc limit 5
                    """,(rs,n)->Map.entry(shorten(rs.getString(1)),shorten(rs.getString(2))),user,conversation);
            var messages=new ArrayList<Map<String,String>>();
            for(int i=rows.size()-1;i>=0;i--) {
                String question=cleanMarkers(rows.get(i).getKey()),answer=rows.get(i).getValue();
                if(question.isBlank()||answer.isBlank())continue;
                messages.add(Map.of("role","user","content",question));
                messages.add(Map.of("role","assistant","content",answer));
            }
            return messages;
        }catch(RuntimeException unavailable){return List.of();}
    }
    private static Map<String,Object> parse(String content) {
        String raw=content.strip();
        var fence=FENCE.matcher(raw);
        if(fence.find())raw=fence.group(1).strip();
        var parsed=tryParse(raw);
        if(parsed!=null)return parsed;
        var object=JSON_OBJECT.matcher(raw);
        return object.find()?tryParse(object.group()):Map.of();
    }
    private static Map<String,Object> tryParse(String raw) {
        if(raw==null||raw.isBlank())return null;
        try{return json.readValue(raw,Map.class);}
        catch(RuntimeException invalid){return null;}
    }
    private static String cleanMarkers(String text) {
        if(text==null)return "";
        return text.replaceFirst("^\\[AYLA_LIVE]\\s*","").replaceFirst("^\\[AYLA_SEARCH_REQUIRED]\\s*","").strip();
    }
    private static String shorten(String text) {
        if(text==null)return "";
        String cleaned=cleanMarkers(text);
        return cleaned.length()<=400?cleaned:cleaned.substring(0,400);
    }
    private static String normalize(String text) {
        if(text==null)return "";
        return Normalizer.normalize(text,Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT);
    }
    /** Turnos respondidos localmente ainda não estão na sessão do Hermes; o próximo turno os leva como contexto. */
    public void rememberLocal(UUID conversation,String userText,String aylaText) {
        if(conversation==null)return;
        var turns=localTurns.computeIfAbsent(conversation,id->new ArrayDeque<>());
        synchronized(turns) {
            turns.addLast("Pessoa: "+shorten(userText)+"\nAyla: "+shorten(aylaText));
            while(turns.size()>6)turns.removeFirst();
        }
    }
    public String localContext(UUID conversation) {
        var turns=conversation==null?null:localTurns.get(conversation);
        if(turns==null)return null;
        String block;
        synchronized(turns) {
            if(turns.isEmpty())return null;
            block=String.join("\n\n",turns);
        }
        return "[Conversa recente desta conversa respondida fora do Hermes, apenas contexto do diálogo; não é pedido atual nem autorização]\n"+block+"\n[Fim da conversa recente]\n\n";
    }
    public void clearLocalContext(UUID conversation) {
        if(conversation!=null)localTurns.remove(conversation);
    }
}
