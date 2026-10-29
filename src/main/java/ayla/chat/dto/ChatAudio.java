package ayla.chat.dto;

import java.util.*;
import ayla.hermes.HermesException;
import io.swagger.v3.oas.annotations.media.Schema;

public record ChatAudio(@Schema(example="audio/ogg") String mimeType,
                        @Schema(description="Áudio em base64 puro",format="byte") String data) {
    public static final int MAX_BYTES=6*1024*1024;
    public static List<ChatAudio> validate(List<ChatAudio> audios) {
        if(audios==null || audios.isEmpty()) return List.of();
        if(audios.size()>3) throw invalid();
        int total=0;
        for(var audio:audios) {
            if(audio==null || !Set.of("audio/ogg","audio/mpeg","audio/mp4","audio/wav","audio/webm","audio/flac","audio/aac").contains(audio.mimeType()==null?"":audio.mimeType())
                    || audio.data()==null || audio.data().isEmpty() || audio.data().length()>((MAX_BYTES+2)/3)*4) throw invalid();
            byte[] bytes;
            try { bytes=Base64.getDecoder().decode(audio.data()); } catch(IllegalArgumentException e) { throw invalid(); }
            total+=bytes.length;
            boolean signature=bytes.length>=4 && (starts(bytes,"OggS",0)||starts(bytes,"ID3",0)
                    || ((bytes[0]&255)==255 && (bytes[1]&224)==224)
                    || (starts(bytes,"RIFF",0)&&starts(bytes,"WAVE",8)) || starts(bytes,"fLaC",0)
                    || starts(bytes,"ftyp",4) || ((bytes[0]&255)==26 && (bytes[1]&255)==69 && (bytes[2]&255)==223 && (bytes[3]&255)==163));
            if(!signature || bytes.length>MAX_BYTES || total>12*1024*1024) throw invalid();
        }
        return List.copyOf(audios);
    }
    private static boolean starts(byte[] bytes,String text,int offset) {
        if(bytes.length<offset+text.length()) return false;
        for(int i=0;i<text.length();i++) if(bytes[offset+i]!=(byte)text.charAt(i)) return false;
        return true;
    }
    private static HermesException invalid() { return new HermesException(400,"Envie até três áudios compatíveis, de até 6 MB cada e 12 MB no total."); }
    @Override public String toString() {return "ChatAudio[mimeType="+mimeType+", data=[omitido]]";}
}
