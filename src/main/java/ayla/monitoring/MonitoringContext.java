package ayla.monitoring;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public final class MonitoringContext {
    private MonitoringContext() {}
    public static final String USER="ayla.monitor.user",ERROR="ayla.monitor.error";
    public static void user(String userId) {
        if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)
            attrs.getRequest().setAttribute(USER,userId);
    }
}
