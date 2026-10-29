package ayla.auth;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.core.userdetails.*;

@Configuration
public class SecurityConfiguration {
    @Bean SecurityFilterChain apiSecurity(HttpSecurity http,AuthService auth,ayla.monitoring.MonitoringFilter monitoring,
            @Value("${ayla.finance.bridge-key:}") String hermesKey,@Value("${ayla.whatsapp.api-key:}") String whatsappKey) throws Exception {
        return http.csrf(csrf->csrf.disable()) // No authentication cookies: all credentials are explicit request headers/bodies.
                .cors(cors->{})
                .sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache->cache.disable()).formLogin(form->form.disable()).httpBasic(basic->basic.disable()).logout(logout->logout.disable())
                .exceptionHandling(errors->errors.authenticationEntryPoint((request,response,error)->AuthTokenFilter.writeError(response,401,"Autentique-se para acessar a API."))
                        .accessDeniedHandler((request,response,error)->AuthTokenFilter.writeError(response,403,"Você não tem permissão para este recurso.")))
                .addFilterBefore(new AuthTokenFilter(auth,hermesKey,whatsappKey),AnonymousAuthenticationFilter.class)
                .addFilterBefore(monitoring,AuthTokenFilter.class)
                .authorizeHttpRequests(access->access
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS,"/**").permitAll()
                        .requestMatchers("/swagger-ui.html","/swagger-ui/**","/v3/api-docs/**").permitAll()
                        .requestMatchers(HttpMethod.GET,"/actuator/health","/actuator/health/liveness","/actuator/health/readiness","/livez","/readyz").permitAll()
                        .requestMatchers(HttpMethod.GET,"/internal/lan-ip").permitAll()
                        .requestMatchers(HttpMethod.GET,"/api/v1/calendar/google/callback").permitAll()
                        .requestMatchers(HttpMethod.POST,"/api/v1/auth/setup","/api/v1/auth/login","/api/v1/auth/refresh","/api/v1/auth/forgot-password").permitAll()
                        .requestMatchers("/internal/hermes/**").hasRole("HERMES")
                        .requestMatchers("/internal/monitoring/whatsapp").hasRole("WHATSAPP")
                        .requestMatchers("/api/v1/auth/users","/api/v1/auth/users/**","/api/v1/hermes/**","/api/v1/monitoring/**","/api/v1/monitoring","/actuator/info").hasRole("OWNER")
                        .requestMatchers("/api/v1/whatsapp/**").hasAnyRole("OWNER","USER")
                        .requestMatchers(HttpMethod.POST,"/api/v1/chat").hasAnyRole("OWNER","USER","WHATSAPP")
                        .requestMatchers("/api/v1/chat/jobs","/api/v1/chat/jobs/**").hasAnyRole("OWNER","USER","WHATSAPP")
                        .requestMatchers("/api/v1/**").hasAnyRole("OWNER","USER")
                        .anyRequest().denyAll()).build();
    }
    // Disable Boot's generated default account; login is implemented against PostgreSQL only.
    @Bean UserDetailsService noDefaultAccount() {return name->{throw new UsernameNotFoundException("Conta indisponível.");};}
}
