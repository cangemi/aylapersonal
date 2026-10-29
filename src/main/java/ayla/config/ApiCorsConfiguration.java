package ayla.config;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class ApiCorsConfiguration implements WebMvcConfigurer {
    private final String[] origins;
    public ApiCorsConfiguration(@Value("${ayla.web.allowed-origins:http://localhost:3000,http://localhost:5173}") String origins) {
        this.origins=Arrays.stream(origins.split(",")).map(String::strip).filter(value->!value.isBlank()).toArray(String[]::new);
    }
    @Override public void addCorsMappings(CorsRegistry registry) {
        if(origins.length>0) registry.addMapping("/api/v1/**").allowedOrigins(origins)
                .allowedMethods("GET","POST","PUT","PATCH","DELETE","OPTIONS")
                .allowedHeaders("Content-Type","Authorization","Idempotency-Key","X-Ayla-Setup-Key","Prefer")
                .exposedHeaders("Location","Retry-After").maxAge(3600);
    }
}
