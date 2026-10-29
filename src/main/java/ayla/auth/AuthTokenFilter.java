package ayla.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

/** Access tokens and service credentials have separate scopes; neither selects a user from a body. */
public class AuthTokenFilter extends OncePerRequestFilter {
    private final AuthService auth;
    private final byte[] hermesKey,whatsappKey;
    public AuthTokenFilter(AuthService auth,String hermesKey,String whatsappKey) {
        this.auth=auth;this.hermesKey=hermesKey.getBytes(StandardCharsets.UTF_8);this.whatsappKey=whatsappKey.getBytes(StandardCharsets.UTF_8);
    }
    private static String path(HttpServletRequest request) {return request.getRequestURI().substring(request.getContextPath().length());}
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=path(request);
        return request.getMethod().equals("OPTIONS") || path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs") || path.equals("/actuator/health")
                || (request.getMethod().equals("GET") && path.equals("/api/v1/calendar/google/callback"))
                || (request.getMethod().equals("POST")&&Set.of("/api/v1/auth/setup","/api/v1/auth/login","/api/v1/auth/refresh","/api/v1/auth/forgot-password").contains(path));
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
        String authorization=request.getHeader("Authorization");
        if(authorization!=null) {
            try {
                if(!authorization.regionMatches(true,0,"Bearer ",0,7))throw new AuthException(401,"Use um token Bearer válido.");
                String token=authorization.substring(7),role;Object identity;
                if(path(request).startsWith("/internal/hermes/")) {
                    if(!matches(hermesKey,token))throw new AuthException(401,"Integração não autorizada.");
                    auth.requireActiveAgentSession(request.getHeader("X-Ayla-Session"));
                    identity="hermes-internal";role="HERMES";
                } else if((request.getHeader("X-Ayla-WhatsApp-Chat")!=null
                        && ((request.getMethod().equals("POST")&&path(request).equals("/api/v1/chat"))
                        || (request.getMethod().equals("GET")&&(path(request).equals("/api/v1/chat/jobs")||path(request).matches("/api/v1/chat/jobs/[0-9a-fA-F-]{36}")))
                        || (request.getMethod().equals("POST")&&path(request).matches("/api/v1/chat/jobs/[0-9a-fA-F-]{36}/ack"))))
                        || (request.getMethod().equals("POST")&&path(request).equals("/internal/monitoring/whatsapp"))) {
                    if(!matches(whatsappKey,token))throw new AuthException(401,"Integração WhatsApp não autorizada.");
                    identity="whatsapp-internal";role="WHATSAPP";
                } else {
                    var user=auth.authenticate(token);identity=user;role=user.role();
                }
                var context=SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(identity,null,List.of(new SimpleGrantedAuthority("ROLE_"+role))));
                SecurityContextHolder.setContext(context);
            } catch(AuthException e) {
                SecurityContextHolder.clearContext();writeError(response,e.status(),e.getMessage());return;
            }
        }
        chain.doFilter(request,response);
    }
    private static boolean matches(byte[] key,String value) {return key.length>=32&&MessageDigest.isEqual(key,value.getBytes(StandardCharsets.UTF_8));}
    public static void writeError(HttpServletResponse response,int status,String message) throws IOException {
        response.setStatus(status);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control","no-store");
        if(status==401)response.setHeader("WWW-Authenticate","Bearer");
        response.getWriter().write(JsonMapper.builder().build().writeValueAsString(Map.of("error",message)));
    }
}
