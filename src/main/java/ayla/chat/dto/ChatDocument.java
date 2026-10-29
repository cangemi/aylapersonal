package ayla.chat.dto;

import java.util.*;
import ayla.hermes.HermesException;

public record ChatDocument(String mimeType,String name,String data) {
    public static final String DOCX="application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    public static List<ChatDocument> validate(List<ChatDocument> values) {
        if(values==null)return List.of();
        if(values.size()>2)throw new HermesException(400,"Envie até dois documentos por mensagem.");
        long total=0;
        for(var value:values) {
            if(value==null || value.data()==null || value.data().length()>8388608 || value.name()==null || value.name().length()>160
                    || value.mimeType()==null || !Set.of("application/pdf",DOCX,"application/msword","text/plain").contains(value.mimeType()) || !value.data().matches("[A-Za-z0-9+/]+={0,2}"))
                throw new HermesException(400,"Envie PDF, DOCX, DOC ou TXT válido, com até 6 MB.");
            byte[] bytes;
            try {bytes=Base64.getDecoder().decode(value.data());}catch(IllegalArgumentException e){throw new HermesException(400,"Documento inválido.");}
            total+=bytes.length;
            if(bytes.length==0 || bytes.length>6*1024*1024 || total>12*1024*1024)throw new HermesException(400,"Documentos excedem o limite de tamanho.");
            boolean valid=switch(value.mimeType()) {
                case "application/pdf" -> new String(bytes,0,Math.min(5,bytes.length),java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-");
                case DOCX -> bytes.length>4 && bytes[0]=='P'&&bytes[1]=='K';
                case "application/msword" -> bytes.length>8 && (bytes[0]&255)==208 && (bytes[1]&255)==207 && bytes[2]==17 && (bytes[3]&255)==224;
                default -> true;
            };
            if(!valid)throw new HermesException(400,"O conteúdo do documento não corresponde ao formato informado.");
        }
        return List.copyOf(values);
    }
}
