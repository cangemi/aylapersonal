package ayla.monitoring;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.security.core.context.SecurityContextHolder;
import ayla.auth.AuthData.Identity;
import io.swagger.v3.oas.annotations.Operation;

public class MonitoringFilter extends OncePerRequestFilter {
    private static final Logger log=LoggerFactory.getLogger(MonitoringFilter.class);
    private final MonitoringService service;
    public MonitoringFilter(MonitoringService service){this.service=service;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request){
        String uri=request.getRequestURI();return request.getMethod().equals("OPTIONS")||!(uri.startsWith("/api/v1/")||uri.startsWith("/internal/hermes/"));
    }
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        UUID id=UUID.randomUUID();Instant at=Instant.now();long start=System.nanoTime();Throwable escaped=null;
        String previous=MDC.get("requestId");MDC.put("requestId",id.toString());res.setHeader("X-Ayla-Request-Id",id.toString());
        try {chain.doFilter(req,res);}
        catch(IOException|ServletException|RuntimeException e){escaped=e;throw e;}
        finally {
            try {
                int status=escaped==null?res.getStatus():500;
                String uri=req.getRequestURI();
                boolean noise=uri.startsWith("/api/v1/monitoring")||uri.startsWith("/api/v1/chat/jobs")
                        ||(req.getMethod().equals("GET")&&Set.of("/api/v1/calendar/reminders","/api/v1/calendar/google").contains(uri))
                        ||Set.of("/api/v1/whatsapp/status","/api/v1/whatsapp/qr","/api/v1/hermes/status").contains(uri);
                if(status>=400||!noise) {
                    var auth=SecurityContextHolder.getContext().getAuthentication();
                    Object principal=auth==null?null:auth.getPrincipal();
                    String user=req.getAttribute(MonitoringContext.USER) instanceof String value?value:principal instanceof Identity value?value.userId():null;
                    String source=principal instanceof Identity?"WEB":"ANONYMOUS";
                    if(auth!=null&&auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_WHATSAPP")))source="WHATSAPP";
                    if(auth!=null&&auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_HERMES")))source="HERMES";
                    if(user!=null&&source.equals("ANONYMOUS"))source="WEB";
                    String route=req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) instanceof String value?value:safeFallback(uri);
                    String action=action(req,route);
                    Throwable error=escaped!=null?escaped:req.getAttribute(MonitoringContext.ERROR) instanceof Throwable value?value:null;
                    String type=null,reason=null,frames=null;
                    if(status>=400){
                        type=error==null?(status==403?"AccessDenied":status==401?"AuthenticationRequired":"HttpError"):error.getClass().getSimpleName();
                        reason=reason(status);frames=frames(error);
                        if(status>=500)log.warn("Falha monitorada requestId={} action={} status={} type={} locations={}",id,action,status,type,frames);
                    }
                    service.record(new MonitoringService.Event(id,at,user,source,req.getMethod(),cut(route,240),cut(action,180),status,
                            Math.max(0,(System.nanoTime()-start)/1_000_000),cut(type,120),reason,frames));
                }
            }catch(Exception e){log.warn("Não foi possível persistir monitoramento requestId={} type={}",id,e.getClass().getSimpleName());}
            finally{if(previous==null)MDC.remove("requestId");else MDC.put("requestId",previous);}
        }
    }
    private static String cut(String value,int size){return value==null?null:value.substring(0,Math.min(value.length(),size));}
    private static String safeFallback(String uri){
        for(String area:List.of("auth","chat","conversations","finance","investments","pantry","shopping-list","memories","whatsapp","hermes","monitoring","calendar"))
            if(uri.equals("/api/v1/"+area)||uri.startsWith("/api/v1/"+area+"/"))return "/api/v1/"+area+"/[acesso não concluído]";
        return uri.startsWith("/internal/")?"/internal/hermes/[acesso não concluído]":"/api/v1/[rota não encontrada]";
    }
    private static String action(HttpServletRequest req,String route){
        if(req.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE) instanceof HandlerMethod handler){
            Operation op=handler.getMethodAnnotation(Operation.class);
            if(op!=null&&!op.summary().isBlank())return op.summary();
        }
        String area=route.contains("/calendar")?"agenda":route.contains("/finance")?"financeiro":route.contains("/investment")?"investimentos":route.contains("/memory")||route.contains("/memories")?"memória":route.contains("/household")||route.contains("/pantry")?"despensa":route.contains("/shopping-list")?"lista de compras":route.contains("/auth")?"conta":route.contains("/whatsapp")?"WhatsApp":route.contains("/chat")||route.contains("/conversation")?"conversas":"aplicação";
        String verb=switch(req.getMethod()){case "GET"->"Consultar";case "POST"->"Executar";case "PUT","PATCH"->"Atualizar";case "DELETE"->"Excluir";default->"Acessar";};
        String action=verb+" · "+area;
        if(req.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?,?> vars&&vars.get("operation") instanceof String op
                &&Set.of("record","get","list","summary","update","delete","insights","simulate").contains(op))action+=" · "+op;
        return action;
    }
    private static String reason(int status){return switch(status){case 400,422->"Dados inválidos ou incompletos.";case 401->"Autenticação necessária ou expirada.";case 403->"Acesso negado.";case 404->"Recurso não encontrado.";case 409->"Conflito com o estado atual dos dados ou da conexão.";case 429->"Limite de uso ou tentativas excedido.";case 502,503,504->"Serviço ou integração indisponível.";default->status>=500?"Falha interna ao executar a ação.":"A operação não foi concluída.";};}
    private static String frames(Throwable error){
        if(error==null)return null;
        var lines=new LinkedHashSet<String>();var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        for(Throwable cause=error;cause!=null&&seen.size()<5&&seen.add(cause);cause=cause.getCause()){
            lines.add(cause.getClass().getName());
            Arrays.stream(cause.getStackTrace()).filter(frame->frame.getClassName().startsWith("ayla.")).limit(8).forEach(frame->lines.add("  "+frame));
        }
        return cut(String.join("\n",lines),6000);
    }
}
