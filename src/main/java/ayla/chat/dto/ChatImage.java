package ayla.chat.dto;

import java.util.*;
import ayla.hermes.HermesException;
import io.swagger.v3.oas.annotations.media.Schema;

public record ChatImage(
        @Schema(example="image/jpeg") String mimeType,
        @Schema(description="Imagem em base64 puro, sem prefixo data:",format="byte") String data) {
    public static final int MAX_BYTES=4*1024*1024, MAX_TOTAL_BYTES=6*1024*1024;
    public static List<ChatImage> validate(List<ChatImage> images) {
        if (images==null || images.isEmpty()) return List.of();
        if (images.size()>3) throw invalid();
        int total=0;
        for(var image:images) {
            if(image==null || !Set.of("image/jpeg","image/png","image/webp").contains(image.mimeType()==null?"":image.mimeType())
                    || image.data()==null || image.data().isEmpty() || image.data().length()>((MAX_BYTES+2)/3)*4) throw invalid();
            byte[] bytes;
            try { bytes=Base64.getDecoder().decode(image.data()); } catch(IllegalArgumentException e) { throw invalid(); }
            total+=bytes.length;
            boolean signature=switch(image.mimeType()) {
                case "image/jpeg" -> bytes.length>=3 && (bytes[0]&255)==255 && (bytes[1]&255)==216 && (bytes[2]&255)==255;
                case "image/png" -> bytes.length>=8 && Arrays.equals(Arrays.copyOf(bytes,8),new byte[]{(byte)137,80,78,71,13,10,26,10});
                default -> bytes.length>=12 && bytes[0]=='R' && bytes[1]=='I' && bytes[2]=='F' && bytes[3]=='F'
                        && bytes[8]=='W' && bytes[9]=='E' && bytes[10]=='B' && bytes[11]=='P';
            };
            if(!signature || bytes.length>MAX_BYTES || total>MAX_TOTAL_BYTES) throw invalid();
        }
        return List.copyOf(images);
    }
    public Map<String,Object> contentPart() {
        return Map.of("type","image_url","image_url",Map.of("url","data:"+mimeType+";base64,"+data));
    }
    private static HermesException invalid() { return new HermesException(400,"Envie até 3 imagens JPEG, PNG ou WebP, de até 4 MB cada e 6 MB no total."); }
    @Override public String toString() { return "ChatImage[mimeType="+mimeType+", data=[omitida]]"; }
}

