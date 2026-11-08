package ayla.hermes;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import ayla.chat.dto.ChatAudio;

/** Backend-only authenticated access to Hermes' native STT and disposable cache files. */
@Component
public class HermesDashboardClient {
    private final HttpClient client;
    private final ProfileVoiceLocks voiceLocks=new ProfileVoiceLocks();
    private final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
    private final JsonMapper json=JsonMapper.builder().build();
    private final String baseUrl, username, password, dataHome;
    @Value("${ayla.finance.bridge-key:}") private String bridgeKey="";
    private ayla.cluster.ClusterOperations cluster;
    @org.springframework.beans.factory.annotation.Autowired
    public void setCluster(ayla.cluster.ClusterOperations cluster){this.cluster=cluster;}
    public HermesDashboardClient(@Value("${ayla.hermes.dashboard-url:http://127.0.0.1:9119}") String baseUrl,
            @Value("${HERMES_DASHBOARD_USERNAME:ayla}") String username,
            @Value("${HERMES_DASHBOARD_PASSWORD:}") String password,
            @Value("${ayla.hermes.data-home:/opt/data}") String dataHome) {
        this.baseUrl=baseUrl.replaceAll("/$","");this.username=username;this.password=password;this.dataHome=dataHome.replaceAll("/$","");
        // The Hermes dashboard rejects the JDK HTTP/2 cleartext upgrade during login.
        client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).cookieHandler(cookies).build();
    }
    private synchronized void login() throws Exception {
        if(password.isBlank()) throw new HermesException(503,"Configure HERMES_DASHBOARD_PASSWORD na API para acessar o painel do Hermes.");
        var response=raw("POST","/auth/password-login",Map.of("provider","basic","username",username,"password",password));
        if(response.statusCode()!=200) throw new HermesException(503,"O painel do Hermes recusou o login da integração. Confira usuário e senha do painel.");
    }
    private HttpResponse<String> raw(String method,String path,Map<String,Object> body) throws Exception {
        int timeout=path.equals("/api/plugins/ayla-finance/session-state")?5:path.startsWith("/api/providers/")?("GET".equals(method)?10:30):path.startsWith("/api/plugins/ayla-finance/")?25:path.startsWith("/auth/")?10:180;
        var request=HttpRequest.newBuilder(URI.create(baseUrl+path)).timeout(Duration.ofSeconds(timeout)).header("Content-Type","application/json");
        if(path.equals("/api/plugins/ayla-finance/account")||path.equals("/api/plugins/ayla-finance/session-state"))request.header("X-Ayla-Bridge-Key",bridgeKey);
        return client.send(request.method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    private Map<String,Object> call(String method,String path,Map<String,Object> body,boolean missingAllowed) {
        return call(method,path,body,missingAllowed,"O Hermes não conseguiu concluir a operação de mídia agora.");
    }
    /** Administrative dashboard call (profiles, env, OAuth). */
    public Map<String,Object> admin(String method,String path,Map<String,Object> body) {
        return call(method,path,body==null?Map.of():body,false,"O Hermes não conseguiu concluir a operação agora.");
    }
    public Map<String,Object> accountSnapshot(String profile){
        return accountSnapshot(profile,false);
    }
    public void requireSessionReady(String profile,String session){
        profileQuery(profile);
        if(session==null||!session.matches("[A-Za-z0-9_-]{1,128}"))throw new HermesException(400,"Sessão Hermes inválida.");
        if(bridgeKey.length()<32)throw new HermesException(503,"Configure a chave interna da Ayla para verificar a sessão Hermes.");
        var state=admin("POST","/api/plugins/ayla-finance/session-state",Map.of("profile",profile,"session",session));
        if("PAUSED".equals(state.get("state")))throw new HermesSessionPausedException();
        if(!"READY".equals(state.get("state")))throw new HermesException(503,"Não foi possível confirmar o estado desta conversa no Hermes.");
    }
    public Map<String,Object> accountSnapshot(String profile,boolean fresh){
        profileQuery(profile);
        if(bridgeKey.length()<32)throw new HermesException(503,"Configure a chave interna da Ayla para consultar a cota Nous.");
        return admin("POST","/api/plugins/ayla-finance/account",Map.of("profile",profile,"fresh",fresh));
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> call(String method,String path,Map<String,Object> body,boolean missingAllowed,String failure) {
        try {
            if(cookies.getCookieStore().getCookies().isEmpty()) login();
            var response=raw(method,path,body);
            if(response.statusCode()==401) { login(); response=raw(method,path,body); }
            if(missingAllowed && response.statusCode()==404) return Map.of();
            if(response.statusCode()<200 || response.statusCode()>=300) throw new HermesException(503,failure+" HTTP "+response.statusCode()+" no painel Hermes.");
            return json.readValue(response.body(),Map.class);
        } catch(HermesException e) {throw e;}
        catch(java.net.http.HttpTimeoutException e) {
            throw new HermesException(504,failure+" O painel Hermes excedeu o tempo de resposta. Aguarde a inicialização do serviço e tente novamente.");
        }
        catch(java.net.ConnectException e) {
            throw new HermesException(503,failure+" A conexão com o painel Hermes foi recusada. Verifique se o serviço terminou de iniciar e se HERMES_DASHBOARD_URL aponta para o painel correto.");
        }
        catch(tools.jackson.core.JacksonException e) {
            throw new HermesException(502,failure+" O painel Hermes retornou uma resposta inválida.");
        }
        catch(Exception e) {
            if(e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new HermesException(503,failure+" O painel local do Hermes não respondeu; verifique o serviço e a URL da integração.");
        }
    }
    private String profileQuery(String profile) {
        if(profile==null || !profile.matches("[a-zA-Z0-9_-]{1,64}")) throw new HermesException(503,"Perfil de mídia inválido.");
        return profile.equals("default")?"":"?profile="+profile;
    }
    public String transcribe(String profile,ChatAudio audio) {
        var result=call("POST","/api/audio/transcribe"+profileQuery(profile),
                Map.of("data_url","data:"+audio.mimeType()+";base64,"+audio.data(),"mime_type",audio.mimeType()),false);
        if(!Boolean.TRUE.equals(result.get("ok")) || !(result.get("transcript") instanceof String text) || text.isBlank())
            throw new HermesException(422,"Não consegui entender o áudio. Pode repetir ou escrever a mensagem?");
        if(!"local".equals(result.get("provider")) && !"local_command".equals(result.get("provider")))
            throw new HermesException(503,"A transcrição deve estar configurada como local no Hermes.");
        return text.strip();
    }
    public ChatAudio speak(String profile,String text,VoiceSettings settings) {
        return voiceOperation(profile,()->speakConfigured(profile,text,settings));
    }
    private ChatAudio voiceOperation(String profile,java.util.function.Supplier<ChatAudio> work){
        return voiceLocks.run(profile,()->cluster==null?work.get():cluster.exclusive("voice:"+profile,work));
    }
    private ChatAudio speakConfigured(String profile,String text,VoiceSettings settings){
        // Native config is merged, not replaced. Reapply the database source of truth after restarts.
        var result=call("PUT","/api/config"+profileQuery(profile),Map.of("config",Map.of("tts",
                Map.of("provider","edge","edge",Map.of("voice",settings.voice(),"speed",settings.speed(),"pitch",settings.edgePitch())))),false);
        if(!Boolean.TRUE.equals(result.get("ok"))) throw new HermesException(503,"Não consegui aplicar a configuração de voz no Hermes.");
        return synthesize(profile,text);
    }
    public ChatAudio speak(String profile,String text) {
        return voiceOperation(profile,()->synthesize(profile,text));
    }
    private ChatAudio synthesize(String profile,String text) {
        var result=call("POST","/api/audio/speak"+profileQuery(profile),Map.of("text",text),false);
        return audioResult(result);
    }
    public ChatAudio preview(VoiceSettings settings) {
        return audioResult(call("POST","/api/audio/ayla-preview",Map.of("voice",settings.voice(),"speed",settings.speed(),"pitchHz",settings.pitchHz()),false));
    }
    private ChatAudio audioResult(Map<String,Object> result) {
        if(!Boolean.TRUE.equals(result.get("ok")) || !"edge".equals(result.get("provider"))
                || !(result.get("data_url") instanceof String url) || !(result.get("mime_type") instanceof String mime)
                || !url.startsWith("data:"+mime+";base64,")) throw new HermesException(503,"Não consegui gerar a resposta em áudio.");
        var audio=new ChatAudio(mime,url.substring(url.indexOf(',')+1));
        ChatAudio.validate(List.of(audio));
        return audio;
    }
    public void deleteImageCache(String profile,String name) {
        if(!name.matches("api_[a-f0-9]{32}\\.(png|jpg|webp)")) throw new IllegalArgumentException("Arquivo temporário inválido");
        profileQuery(profile);
        String root=profile.equals("default")?dataHome:dataHome+"/profiles/"+profile;
        call("DELETE","/api/files"+profileQuery(profile),Map.of("path",root+"/cache/images/"+name,"recursive",false),true);
    }
}

