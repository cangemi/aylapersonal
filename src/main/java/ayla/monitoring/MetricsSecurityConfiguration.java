package ayla.monitoring;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Scrapes are available only on the separate, unpublished management listener. */
@Configuration
@Profile({"vps","observability-local"})
public class MetricsSecurityConfiguration {
    @Bean @Order(0)
    SecurityFilterChain privateMetrics(HttpSecurity http,@Value("${management.server.port}") int port,org.springframework.core.env.Environment environment,
            @Value("${ayla.metrics.scrape-key:}") String scrapeKey)throws Exception{
        return http.securityMatcher((HttpServletRequest request)->request.getLocalPort()==(port==0?environment.getProperty("local.management.port",Integer.class,-1):port))
                .csrf(c->c.disable()).requestCache(c->c.disable())
                .authorizeHttpRequests(a->a.requestMatchers("/actuator/prometheus","/actuator/health","/actuator/health/**").access((authentication,context)->{
                    boolean local=environment.matchesProfiles("observability-local");
                    String header=context.getRequest().getHeader("Authorization");
                    boolean allowed=!local||(scrapeKey.length()>=32&&header!=null&&java.security.MessageDigest.isEqual(
                            ("Bearer "+scrapeKey).getBytes(java.nio.charset.StandardCharsets.UTF_8),header.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    return new org.springframework.security.authorization.AuthorizationDecision(allowed);
                }).anyRequest().denyAll())
                .build();
    }
}
