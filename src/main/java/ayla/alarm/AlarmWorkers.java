package ayla.alarm;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import ayla.whatsapp.WhatsappGateway;
import java.util.*;

@Service
public class AlarmWorkers {
    private final JdbcTemplate jdbc;
    private final AlarmService alarms;
    private final WhatsappGateway whatsapp;
    public AlarmWorkers(JdbcTemplate jdbc,AlarmService alarms,WhatsappGateway whatsapp){this.jdbc=jdbc;this.alarms=alarms;this.whatsapp=whatsapp;}

    @Scheduled(fixedDelay=15000,initialDelay=15000,scheduler="calendarScheduler")
    public void run(){
        try {
            alarms.fireDue();
            var pending = jdbc.queryForList("""
                select id,user_id
                from alarm_notifications
                where notified_at is not null
                and whatsapp_status='PENDING'
                and (whatsapp_attempt_at is null or whatsapp_attempt_at < now()-interval '60 seconds')
                order by due_at
                limit 10
                """);
            for (var row : pending) {
                Object id = row.get("id");
                String user = (String) row.get("user_id");
                int claim = jdbc.update("""
                    update alarm_notifications
                    set whatsapp_attempt_at=now()
                    where id=?
                    and whatsapp_status='PENDING'
                    and (whatsapp_attempt_at is null or whatsapp_attempt_at < now()-interval '60 seconds')
                    """, id);
                if (claim == 0) continue;
                try {
                    Object delivery = whatsapp.write("POST", "/internal/alarm-notification", Map.of("id", id.toString()), user);
                    if (delivery instanceof Map<?, ?> result && "FRONT_ONLY".equals(result.get("status"))) {
                        jdbc.update("update alarm_notifications set whatsapp_status='FRONT_ONLY' where id=? and whatsapp_status='PENDING'", id);
                    }
                } catch (RuntimeException failure) {
                    boolean connected = false;
                    try {
                        Object status = whatsapp.get("/status", user);
                        connected = status instanceof Map<?, ?> state && Boolean.TRUE.equals(state.get("connected"));
                    } catch (RuntimeException unavailable) {
                        // WhatsApp indisponível.
                    }
                    if (!connected) {
                        jdbc.update("update alarm_notifications set whatsapp_status='FRONT_ONLY' where id=? and whatsapp_status='PENDING'", id);
                    }
                }
            }
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Alarmes indisponíveis. tipo={}", failure.getClass().getSimpleName());
        }
    }
}
