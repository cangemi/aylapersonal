package ayla.chat.service;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** The model chooses an occasional lighthearted voice; SQL enforces a per-conversation cooldown. */
@Service
public class SpontaneousVoice {
    public static final String MARKER="[[AYLA_CASUAL_VOICE]]";
    private static final Pattern SERIOUS=Pattern.compile("\\b(?:financeir\\w*|gastei|recebi|salarios?|saude|triste|morreu|dor|saldo|gastos?|despesas?|receitas?|paguei|pagamentos?|dinheiro|reais|centavos?|recibos?|comprovantes?|boletos?|faturas?|extratos?|documentos?|laudos?|exames?|hospital|doenca|luto|morte|dividas?|invoice|receipt)\\b|r\\$|\\bnota\\s+fiscal\\b");
    private final JdbcTemplate jdbc;
    public SpontaneousVoice(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public boolean available(String user,UUID conversation) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select last_spontaneous_voice_at is null or last_spontaneous_voice_at < CURRENT_TIMESTAMP - INTERVAL '15 minutes' from chat_conversations where id=? and user_id=?",Boolean.class,conversation,user));
    }
    public boolean reserve(String user,UUID conversation) {
        return jdbc.update("update chat_conversations set last_spontaneous_voice_at=CURRENT_TIMESTAMP where id=? and user_id=? and (last_spontaneous_voice_at is null or last_spontaneous_voice_at < CURRENT_TIMESTAMP - INTERVAL '15 minutes')",conversation,user)==1;
    }
    public static boolean lighthearted(String message,String reply) {
        String text=Normalizer.normalize(message+"\n"+reply,Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT);
        return !SERIOUS.matcher(text).find();
    }
    public static boolean requestedByModel(String response) {return response.stripLeading().startsWith(MARKER);}
    public static String clean(String response) {return response.replace(MARKER,"").strip();}
}
