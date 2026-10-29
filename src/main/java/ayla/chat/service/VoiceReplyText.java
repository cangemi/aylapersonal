package ayla.chat.service;

/** Separates spoken prose from a small, copyable WhatsApp companion message. */
record VoiceReplyText(String speech, String supplement) {
    private static final String MARKER="[[AYLA_TEXT]]";
    static VoiceReplyText split(String content) {
        int marker=content.indexOf(MARKER);
        String speech=(marker<0?content:content.substring(0,marker)).trim();
        String supplement=marker<0?"":content.substring(marker+MARKER.length()).replace(MARKER,"").trim();
        if(supplement.isBlank()) {
            var urls=java.util.regex.Pattern.compile("https?://[^\\s<>\\[\\]\\\"]+").matcher(speech);
            var unique=new java.util.LinkedHashSet<String>();
            while(urls.find()) unique.add(urls.group().replaceFirst("[).,;!?]+$",""));
            supplement=String.join("\n",unique);
        }
        speech=speech.replaceAll("\\[([^\\]]+)\\]\\(https?://[^\\s)]+\\)","$1")
                .replaceAll("https?://[^\\s<>\\[\\]\\\"]+","").trim();
        return new VoiceReplyText(speech.isBlank()?"Deixei as informações por escrito para você.":speech,
                supplement.isBlank()?null:supplement);
    }
    static String clean(String content) {return content.replace(MARKER,"\n\n").trim();}
}
