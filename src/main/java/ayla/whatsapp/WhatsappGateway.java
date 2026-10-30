package ayla.whatsapp;

import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;

@Service
public class WhatsappGateway {
    private RestClient client;
    private final String baseUrl;
    private final SimpleClientHttpRequestFactory requestFactory;
    @org.springframework.beans.factory.annotation.Autowired
    void observe(RestClient.Builder builder){client=builder.clone().baseUrl(baseUrl).requestFactory(requestFactory).observationConvention(new ayla.monitoring.SafeClientObservation()).build();}
    private final String key;
    public WhatsappGateway(@Value("${ayla.whatsapp.base-url:http://127.0.0.1:4000}") String baseUrl,
            @Value("${ayla.whatsapp.api-key:}") String key) {
        this.key=key;this.baseUrl=baseUrl;
        var factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));factory.setReadTimeout(Duration.ofSeconds(45));
        this.requestFactory=factory;
        client=RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }
    public Object get(String path) {return call("GET",path,null,null);}
    public Object post(String path) {return call("POST",path,Map.of(),null);}
    public Object write(String method,String path,Map<String,Object> body) {return call(method,path,body,null);}
    public Object get(String path,String user) {return call("GET",path,null,user);}
    public Object post(String path,String user) {return call("POST",path,Map.of(),user);}
    public Object write(String method,String path,Map<String,Object> body,String user) {return call(method,path,body,user);}
    private record RecoveryPayload(String userId,String registeredNumber,String password,String requestId) {
        @Override public String toString(){return "RecoveryPayload[redacted]";}
    }
    public Object sendRecovery(String userId,String registeredNumber,String password,String requestId) {return call("POST","/internal/password-recovery",new RecoveryPayload(userId,registeredNumber,password,requestId),null);}
    private Object call(String method,String path,Object body,String user) {
        if (key.length()<32) throw new WhatsappException(503,"Configure AYLA_WHATSAPP_API_KEY para conectar o agentNode.");
        try {
            var request=client.method(org.springframework.http.HttpMethod.valueOf(method)).uri(path).headers(h->{h.setBearerAuth(key);if(user!=null)h.set("X-Ayla-User",user);});
            if (body!=null) request.body(body);
            return request.retrieve().body(new ParameterizedTypeReference<Object>() {});
        } catch(RestClientResponseException e) {
            if(path.equals("/personal-chat")) {
                int code=e.getStatusCode().value();
                throw new WhatsappException(code==409?409:503,code==409
                        ?"Conclua as conexões do WhatsApp para preparar sua conversa com a Ayla. No modo exclusivo, conecte os dois números."
                        :"Não foi possível preparar a conversa. Confira as conexões e se a conversa Ayla já apareceu no WhatsApp antes de tentar novamente.");
            }
            String message=switch(e.getStatusCode().value()) {
                case 400 -> "Dados da conversa inválidos. Informe telefone com país/DDD ou chatId, nome e habilitação.";
                case 404 -> "Conversa não encontrada.";
                case 409 -> "A conexão está ocupada ou o número já foi cadastrado.";
                default -> "O agentNode recusou a operação. Consulte seu status.";
            };
            int status=Set.of(400,404,409).contains(e.getStatusCode().value())?e.getStatusCode().value():503;
            throw new WhatsappException(status,"WhatsApp "+method+" "+path+": "+message);
        } catch(ResourceAccessException e) {throw new WhatsappException(503,"O agentNode não respondeu. Verifique o serviço WhatsApp.");}
    }
}
