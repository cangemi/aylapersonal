package ayla.monitoring;

import io.micrometer.common.KeyValue;
import org.springframework.http.client.observation.*;

/** Avoid unbounded metric labels and exposing session/contact IDs in outbound trace URLs. */
public final class SafeClientObservation extends DefaultClientRequestObservationConvention {
    @Override protected KeyValue uri(ClientRequestObservationContext context){return KeyValue.of("uri",route(context));}
    @Override protected KeyValue requestUri(ClientRequestObservationContext context){return KeyValue.of("http.url",route(context));}
    private static String route(ClientRequestObservationContext context){
        String path=context.getCarrier()==null?"unknown":context.getCarrier().getURI().getPath();
        if(path.startsWith("/p/"))path=path.replaceFirst("^/p/[^/]+", "/p/{profile}");
        path=path.replaceAll("/api/sessions/[^/]+", "/api/sessions/{session}");
        path=path.replaceAll("/chats/[^/]+", "/chats/{chat}");
        return path;
    }
}
