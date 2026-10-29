package ayla.hermes;
import ayla.chat.dto.*;
import ayla.chat.service.AudioReplyPolicy;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class AudioReplyPolicyTest {
    @Test void onlyCurrentRequestOrReceivedVoiceEnablesAudioAndTextRequestsOverrideIt() {
        for(String text:List.of("Me manda um áudio","Responda por voz","Quero a resposta em áudio"))
            assertThat(AudioReplyPolicy.wantsAudio(new ChatRequest(text,null),text)).isTrue();
        for(String text:List.of("Oi","O que é áudio?","Como enviar um áudio?","Não mande áudio","Me responda só em texto"))
            assertThat(AudioReplyPolicy.wantsAudio(new ChatRequest(text,null),text)).isFalse();
        var audio=new ChatAudio("audio/ogg","T2dnUw==");
        var voice=new ChatRequest("Áudio recebido",null,null,null,null,List.of(audio));
        assertThat(AudioReplyPolicy.wantsAudio(voice,"Gastei dez reais no café")).isTrue();
        assertThat(AudioReplyPolicy.wantsAudio(voice,"Não responda por áudio")).isFalse();
        assertThat(AudioReplyPolicy.wantsAudio(new ChatRequest("Oi",null,null,null,null,null,true),"Oi")).isTrue();
        assertThat(AudioReplyPolicy.wantsAudio(new ChatRequest("Voz",null,null,null,null,List.of(audio),false),"Oi")).isFalse();
        var referenced=new ChatRequest("Analise o áudio citado",null,null,null,null,List.of(audio),null,null,
                new ChatContext(List.of(),null,10,"",true));
        assertThat(AudioReplyPolicy.wantsAudio(referenced,"Analise o áudio citado")).isFalse();
        assertThat(AudioReplyPolicy.wantsAudio(referenced,"Me responda por voz")).isTrue();
    }
    @Test void failedSpeechNeverReplaysAgentOrLosesTextAndMarkdownIsNotReadAloud() {
        var dashboard=mock(HermesDashboardClient.class);
        when(dashboard.speak(eq("default"),anyString())).thenThrow(new HermesException(503,"offline"));
        var media=new HermesMediaService(mock(HermesClient.class),dashboard,mock(HermesMediaCleanup.class));
        assertThat(media.speak("default","**Olá**! [Fonte](https://example.org)")).isNull();
        verify(dashboard).speak("default","Olá! Fonte");
    }
}
