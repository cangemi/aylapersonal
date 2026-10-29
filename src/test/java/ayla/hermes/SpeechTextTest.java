package ayla.hermes;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class SpeechTextTest {
    @Test void moneyIsSpokenAsExactReaisAndCentavosIncludingZeroSingularGroupingAndNegativeBalances() {
        var speech=new SpeechText("");
        var cases=Map.ofEntries(
            Map.entry("R$ 0,12","doze centavos"),Map.entry("R$ 0,01","um centavo"),
            Map.entry("R$ 1,00","um real"),Map.entry("R$ 2,01","dois reais e um centavo"),
            Map.entry("R$ 50,50","cinquenta reais e cinquenta centavos"),Map.entry("R$ 0,00","zero reais"),
            Map.entry("R$ 1.234,56","mil duzentos e trinta e quatro reais e cinquenta e seis centavos"),
            Map.entry("R$ 1.000.000,00","um milhão de reais"),Map.entry("-R$ 1,12","menos um real e doze centavos"),
            Map.entry("R$0.12","doze centavos"),Map.entry("BRL 1,234.56","mil duzentos e trinta e quatro reais e cinquenta e seis centavos"),
            Map.entry("0,12 reais","doze centavos"),Map.entry("12 centavos","doze centavos"),Map.entry("1 centavo","um centavo"),
            Map.entry("1 real","um real"),Map.entry("10 reais","dez reais"),Map.entry("150 centavos","cento e cinquenta centavos"));
        cases.forEach((input,expected)->assertThat(speech.format(input)).as(input).isEqualTo(expected));
        assertThat(speech.format("Total: R$ 10,00. Restam R$ 0,12!")).isEqualTo("Total: dez reais. Restam doze centavos!");
        assertThat(speech.format("US$ 10,00; 1,50 centavos; R$ 0,001")).isEqualTo("US$ 10,00; 1,50 centavos; R$ 0,001");
        assertThat(speech.format("R$ 90.071.992.547.409,91")).endsWith("reais e noventa e um centavos");
        var dashboard=mock(HermesDashboardClient.class);
        new HermesMediaService(null,dashboard,null,speech).speak("default","Total: **R$ 0,12**.");
        verify(dashboard).speak("default","Total: doze centavos.");
    }
    @Test void pronunciationOverridesOnlyWholeNamesAndOnlyTheSpeechCopy() {
        var speech=new SpeechText("Zora=Zóra");
        String original="Zora gastou R$ 0,12. Zorana chegou.";
        assertThat(speech.format(original)).isEqualTo("Zóra gastou doze centavos. Zorana chegou.");
        assertThat(original).isEqualTo("Zora gastou R$ 0,12. Zorana chegou.");
    }
}

