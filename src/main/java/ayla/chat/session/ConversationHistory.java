package ayla.chat.session;

import java.util.*;
import org.springframework.stereotype.Service;
import ayla.hermes.*;
import ayla.chat.service.SpontaneousVoice;

/** Read the actual owned Hermes session; never expose tool calls, reasoning or system messages. */
@Service
public class ConversationHistory {
    private final ConversationService conversations;
    private final HermesClient hermes;
    public ConversationHistory(ConversationService conversations,HermesClient hermes) {
        this.conversations=conversations;this.hermes=hermes;
    }
    public record Attachment(String name,String kind) {}
    public record Message(String id,String role,String text,List<Attachment> files) {}
    public record Page(List<Message> items,boolean hasMore,int nextOffset) {}
    public Page list(String user,UUID conversation,int limit,int offset) {
        if(limit<1 || limit>200 || offset<0 || offset>1_000_000)throw new HermesException(400,"Paginação de mensagens inválida.");
        // Resolve checks ownership and the user's configured profile before any network request.
        var selected=conversations.resolve(user,conversation.toString(),null);
        Map<String,Object> response;
        try {
            response=hermes.get(selected.profile(),"/api/sessions/"+selected.sessionId()+"/messages?limit="+limit+"&offset="+offset+"&order=latest&include_compacted=true");
        } catch(HermesException error) {
            if(error.status()==404)return new Page(List.of(),false,offset); // Session is created only on the first message.
            throw error;
        }
        if(!(response.get("data") instanceof List<?> rows))throw new HermesException(502,"O Hermes retornou um histórico inválido.");
        var visible=new ArrayList<Message>();
        for(int i=0;i<rows.size();i++) {
            if(!(rows.get(i) instanceof Map<?,?> row))continue;
            String role=Objects.toString(row.get("role"),"");
            if(!Set.of("user","assistant").contains(role) || "hidden".equals(row.get("display_kind")))continue;
            if("assistant".equals(role) && ("tool_calls".equals(row.get("finish_reason"))
                    || row.get("tool_calls") instanceof Collection<?> calls && !calls.isEmpty()))continue;
            var files=new ArrayList<Attachment>();
            String text=text(row.get("content"),files);
            if("user".equals(role))text=cleanUser(text);
            else text=SpontaneousVoice.clean(text).replaceAll("(?s)<think>.*?(?:</think>|$)","").strip();
            if(text.isBlank() && files.isEmpty())continue;
            String id=Objects.toString(row.get("id"),selected.sessionId()+":"+(offset+i));
            visible.add(new Message(id,role,text,List.copyOf(files)));
        }
        return new Page(List.copyOf(visible),rows.size()==limit,offset+rows.size());
    }
    private static String text(Object content,List<Attachment> files) {
        if(content instanceof String text)return text;
        if(!(content instanceof List<?> parts))return "";
        var text=new ArrayList<String>();
        for(var part:parts)if(part instanceof Map<?,?> value) {
            String type=Objects.toString(value.get("type"),"");
            if(Set.of("text","input_text","output_text").contains(type) && value.get("text") instanceof String chunk)text.add(chunk);
            else if(Set.of("image_url","input_image").contains(type))files.add(new Attachment("Imagem enviada","images"));
            else if(Set.of("input_audio","audio").contains(type))files.add(new Attachment("Áudio enviado","audios"));
        }
        return String.join("\n",text);
    }
    private static String cleanUser(String text) {
        text=text.replaceFirst("^\\[AYLA_SEARCH_REQUIRED]\\s*","");
        for(String marker:List.of("\n[Análise visual das imagens enviadas NESTE turno.","\n[Documento fornecido para análise:","\n[Pesquisa real deste turno]")) {
            int start=text.indexOf(marker);
            if(start>=0)text=text.substring(0,start);
        }
        return text.replace("[Transcrição automática de áudio enviado neste turno; peça esclarecimento se houver ambiguidade]","").strip();
    }
}
