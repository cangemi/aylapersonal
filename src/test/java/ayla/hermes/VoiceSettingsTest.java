package ayla.hermes;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class VoiceSettingsTest {
    @Test void frontendCanSaveSettingsButInvalidValuesNeverReachPersistence() throws Exception {
        var service=mock(VoiceSettingsService.class);
        var settings=new VoiceSettings("pt-BR-FranciscaNeural",new BigDecimal("1.02"),2);
        when(service.update(any())).thenAnswer(call->call.getArgument(0));
        when(service.get()).thenReturn(settings);
        var api=MockMvcBuilders.standaloneSetup(new VoiceController(service))
                .setControllerAdvice(new HermesExceptionHandler()).build();
        api.perform(get("/api/v1/hermes/voice")).andExpect(status().isOk()).andExpect(jsonPath("$.pitchHz").value(2));
        api.perform(get("/api/v1/hermes/voice/voices")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        api.perform(put("/api/v1/hermes/voice").contentType("application/json")
                .content("{\"voice\":\"pt-BR-FranciscaNeural\",\"speed\":1.02,\"pitchHz\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.voice").value(settings.voice()));
        verify(service).update(settings);
        when(service.preview(settings)).thenReturn(new ayla.chat.dto.ChatAudio("audio/mpeg","SUQzAA=="));
        clearInvocations(service);
        api.perform(post("/api/v1/hermes/voice/preview").contentType("application/json")
                .content("{\"voice\":\"pt-BR-FranciscaNeural\",\"speed\":1.02,\"pitchHz\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mimeType").value("audio/mpeg"));
        verify(service).preview(settings);
        verify(service,never()).update(any());
        clearInvocations(service);
        for(String body:List.of("{}",
                "{\"voice\":\"paid-voice\",\"speed\":1,\"pitchHz\":0}",
                "{\"voice\":\"pt-BR-FranciscaNeural\",\"speed\":2,\"pitchHz\":0}",
                "{\"voice\":\"pt-BR-FranciscaNeural\",\"speed\":1,\"pitchHz\":51}"))
            {
                api.perform(put("/api/v1/hermes/voice").contentType("application/json").content(body)).andExpect(status().isBadRequest());
                api.perform(post("/api/v1/hermes/voice/preview").contentType("application/json").content(body)).andExpect(status().isBadRequest());
            }
        verifyNoInteractions(service);
    }

    @Test void synthesisAppliesOnlyVoiceConfigBeforeSpeakingAndStopsOnConfigFailure() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var json=JsonMapper.builder().build();
        var paths=new ArrayList<String>();
        var requests=new ArrayList<Map>();
        var fail=new java.util.concurrent.atomic.AtomicBoolean();
        server.createContext("/",e->{
            paths.add(e.getRequestURI().toString());
            requests.add(json.readValue(e.getRequestBody().readAllBytes(),Map.class));
            String path=e.getRequestURI().getPath();
            Object response;
            if(path.equals("/auth/password-login")) {
                e.getResponseHeaders().add("Set-Cookie","session=test; Path=/");
                response=Map.of("ok",true);
            } else {
                assertThat(e.getRequestHeaders().getFirst("Cookie")).contains("session=test");
                response=path.equals("/api/config") ? Map.of("ok",!fail.get()) :
                        Map.of("ok",true,"provider","edge","mime_type","audio/ogg","data_url","data:audio/ogg;base64,T2dnUw==");
            }
            byte[] bytes=json.writeValueAsString(response).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.sendResponseHeaders(200,bytes.length); e.getResponseBody().write(bytes); e.close();
        });
        server.start();
        try {
            var dashboard=new HermesDashboardClient("http://127.0.0.1:"+server.getAddress().getPort(),"test","password","/opt/data");
            var settings=new VoiceSettings("pt-BR-FranciscaNeural",new BigDecimal("1.02"),-2);
            assertThat(dashboard.speak("other-profile","Oi!",settings).mimeType()).isEqualTo("audio/ogg");
            assertThat(paths).containsExactly("/auth/password-login","/api/config?profile=other-profile","/api/audio/speak?profile=other-profile");
            assertThat(requests.get(1)).isEqualTo(Map.of("config",Map.of("tts",Map.of("provider","edge","edge",
                    Map.of("voice",settings.voice(),"speed",1.02,"pitch","-2Hz")))));
            fail.set(true);
            assertThatThrownBy(()->dashboard.speak("default","Oi!",settings)).isInstanceOf(HermesException.class);
            assertThat(paths.getLast()).isEqualTo("/api/config");
            int previous=paths.size();
            dashboard.preview(settings);
            assertThat(paths.subList(previous,paths.size())).containsExactly("/api/audio/ayla-preview");
            assertThat(requests.getLast()).isEqualTo(Map.of("voice",settings.voice(),"speed",1.02,"pitchHz",-2));
        } finally {server.stop(0);}
    }
    @Test void previewUsesOnlyTemporaryParametersAndNeverWritesTheDatabase() {
        var jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var dashboard=mock(HermesDashboardClient.class);
        var service=new VoiceSettingsService(jdbc,dashboard);
        var temporary=new VoiceSettings("pt-BR-AntonioNeural",new BigDecimal("0.95"),-3);
        when(dashboard.preview(temporary)).thenReturn(new ayla.chat.dto.ChatAudio("audio/mpeg","SUQzAA=="));
        assertThat(service.preview(temporary).mimeType()).isEqualTo("audio/mpeg");
        verifyNoInteractions(jdbc);
        verify(dashboard).preview(temporary);
        verifyNoMoreInteractions(dashboard);
    }
}
