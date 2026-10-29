package ayla.hermes;

import com.ibm.icu.text.RuleBasedNumberFormat;
import com.ibm.icu.util.ULocale;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Presentation only: the ledger and the visible reply retain their original values/text. */
@Component
public class SpeechText {
    private static final Pattern MONEY=Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])(?:(?<sign>[+−-]\\h*)?(?:R\\$|BRL)\\h*(?<prefix>[+−-]?\\h*\\d+(?:[.,]\\d+)*)(?:\\h+rea(?:l|is))?|(?<suffix>[+−-]?\\d+(?:[.,]\\d+)*)\\h+(?<unit>rea(?:l|is)|centavos?))\\b");
    private record Pronunciation(Pattern name,String spoken) {}
    private final List<Pronunciation> pronunciations;
    public SpeechText(@Value("${ayla.hermes.voice.pronunciations:}") String configuration) {
        var entries=new ArrayList<Pronunciation>();
        for(String entry:configuration.split(";")) {
            String[] pair=entry.split("=",2);
            if(pair.length==2 && !pair[0].isBlank() && !pair[1].isBlank())
                entries.add(new Pronunciation(Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])"+Pattern.quote(pair[0].strip())+"(?![\\p{L}\\p{N}])"),pair[1].strip()));
        }
        pronunciations=List.copyOf(entries);
    }
    public String format(String text) {
        // ICU owns the Portuguese spellout rules. Only integer quantities are formatted.
        var words=new RuleBasedNumberFormat(new ULocale("pt_BR"),RuleBasedNumberFormat.SPELLOUT);
        String result=MONEY.matcher(text).replaceAll(match->Matcher.quoteReplacement(amount(match,words)));
        for(var p:pronunciations) result=p.name().matcher(result).replaceAll(Matcher.quoteReplacement(p.spoken()));
        return result;
    }
    private static String amount(MatchResult match,RuleBasedNumberFormat words) {
        try {
            boolean centavos=match.group("unit")!=null && match.group("unit").toLowerCase(Locale.ROOT).startsWith("centavo");
            String raw=match.group("prefix");
            if(raw==null) raw=match.group("suffix");
            if(match.group("sign")!=null) raw=match.group("sign")+raw;
            long value=decimal(raw).movePointRight(centavos?0:2).longValueExact();
            if(value==Long.MIN_VALUE) return match.group();
            long positive=Math.abs(value);
            String spoken;
            if(centavos) spoken=words.format(positive)+(positive==1?" centavo":" centavos");
            else {
                long reais=positive/100,cents=positive%100;
                var parts=new ArrayList<String>();
                if(reais!=0 || cents==0) parts.add(words.format(reais)+(reais==1?" real":reais>=1000000 && reais%1000000==0?" de reais":" reais"));
                if(cents!=0) parts.add(words.format(cents)+(cents==1?" centavo":" centavos"));
                spoken=String.join(" e ",parts);
            }
            return (value<0?"menos ":"")+spoken;
        } catch(IllegalArgumentException|ArithmeticException e) {
            // Never guess an ambiguous separator or round fractional cents.
            return match.group();
        }
    }
    private static BigDecimal decimal(String input) {
        String number=input.replaceAll("\\h","").replace('−','-');
        if(number.matches("[+-]?\\d+(?:,\\d{1,2})?")) return new BigDecimal(number.replace(',','.'));
        if(number.matches("[+-]?\\d{1,3}(?:\\.\\d{3})+(?:,\\d{1,2})?")) return new BigDecimal(number.replace(".","").replace(',','.'));
        if(number.matches("[+-]?\\d+\\.\\d{1,2}")) return new BigDecimal(number);
        if(number.matches("[+-]?\\d{1,3}(?:,\\d{3})+\\.\\d{1,2}")) return new BigDecimal(number.replace(",",""));
        throw new IllegalArgumentException("Ambiguous money token");
    }
}

