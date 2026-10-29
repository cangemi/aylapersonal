package ayla.monitoring;

import java.util.List;
import org.springframework.context.annotation.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MonitoringConfiguration implements WebMvcConfigurer {
    @Bean MonitoringFilter monitoringFilter(MonitoringService service){return new MonitoringFilter(service);}
    @Bean FilterRegistrationBean<MonitoringFilter> monitoringRegistration(MonitoringFilter filter){
        var registration=new FilterRegistrationBean<>(filter);registration.setEnabled(false);return registration;
    }
    @Override public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers){
        resolvers.addFirst((request,response,handler,error)->{request.setAttribute(MonitoringContext.ERROR,error);return null;});
    }
}
