package ayla.chat.dto;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record ChatContext(@Size(max=100) List<@NotNull @Valid Message> messages,@Valid Message quoted,
                          @Min(0) @Max(100) int requestedCount,@Size(max=500) String warning,Boolean referenceAudio,
                          @Min(0) @Max(2) Integer referenceAudioIndex) {
    public record Message(@Size(max=200) String id,@Size(max=80) String author,boolean owner,
                          @Size(max=6000) String text,@Size(max=30) String type,long timestamp) {}
    public ChatContext(List<Message> messages,Message quoted,int requestedCount,String warning) {this(messages,quoted,requestedCount,warning,false);}
    public ChatContext(List<Message> messages,Message quoted,int requestedCount,String warning,Boolean referenceAudio) {this(messages,quoted,requestedCount,warning,referenceAudio,null);}
    public ChatContext {
        referenceAudio=Boolean.TRUE.equals(referenceAudio);
        messages=messages==null?List.of():List.copyOf(messages);
        long size=messages.stream().mapToLong(m->m.text()==null?0:m.text().length()).sum()+(quoted==null||quoted.text()==null?0:quoted.text().length());
        if(size>24000)throw new ayla.hermes.HermesException(400,"O contexto excede 24000 caracteres.");
    }
}
