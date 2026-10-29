package ayla.calendar;

import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CalendarWorkers {
    private final JdbcTemplate jdbc;private final ayla.whatsapp.WhatsappGateway whatsapp;private final GoogleCalendarSync google;
    public CalendarWorkers(JdbcTemplate jdbc,ayla.whatsapp.WhatsappGateway whatsapp,GoogleCalendarSync google){this.jdbc=jdbc;this.whatsapp=whatsapp;this.google=google;}
    @Configuration static class Scheduler {
        @Bean ThreadPoolTaskScheduler calendarScheduler(){var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(2);scheduler.setThreadNamePrefix("calendar-");return scheduler;}
    }
    @Scheduled(fixedDelay=15000,initialDelay=15000,scheduler="calendarScheduler")
    public void reminders(){
        try{
            jdbc.update("update calendar_reminders set whatsapp_status='UNCERTAIN' where whatsapp_status='SENDING' and whatsapp_attempt_at<now()-interval '5 minutes'");
            jdbc.update("""
                update calendar_reminders r set notified_at=now() from calendar_events e,auth_accounts a
                where r.event_id=e.id and e.user_id=a.user_id and a.enabled and e.status='ACTIVE'
                and e.version=r.event_version and not r.cancelled and r.notified_at is null and r.due_at<=now()
                """);
            jdbc.update("update calendar_reminders r set whatsapp_status='SKIPPED' from calendar_events e where r.event_id=e.id and r.whatsapp_status='PENDING' and ((r.reminder_type='BEFORE_START' and e.starts_at<=now() and (r.minutes_before<>0 or e.starts_at<=now()-interval '5 minutes')) or (r.reminder_type='AT_TIME' and r.due_at<=now()-interval '5 minutes') or r.cancelled or e.version<>r.event_version or e.status<>'ACTIVE')");
            jdbc.update("""
                update calendar_reminders r set whatsapp_status='FRONT_ONLY'
                from calendar_events e where r.event_id=e.id and r.whatsapp_status='PENDING'
                and r.notified_at is not null and not r.cancelled and e.version=r.event_version and e.status='ACTIVE'
                and not exists(select 1 from whatsapp_instances i where i.owner_user_id=e.user_id and i.state='ready' and i.desired_connected)
                """);
            var pending=jdbc.queryForList("""
                select r.id,e.user_id from calendar_reminders r join calendar_events e on e.id=r.event_id join auth_accounts a on a.user_id=e.user_id
                where a.enabled and r.notified_at is not null and r.whatsapp_status='PENDING' and not r.cancelled and e.status='ACTIVE' and ((r.reminder_type='AT_TIME' and r.due_at>now()-interval '5 minutes') or (r.reminder_type='BEFORE_START' and (e.starts_at>now() or (r.minutes_before=0 and e.starts_at>now()-interval '5 minutes'))))
                and e.version=r.event_version and (r.whatsapp_attempt_at is null or r.whatsapp_attempt_at<now()-interval '60 seconds')
                order by r.due_at limit 10
                """);
            for(var row:pending){
                int claim=jdbc.update("update calendar_reminders set whatsapp_attempt_at=now() where id=? and whatsapp_status='PENDING' and (whatsapp_attempt_at is null or whatsapp_attempt_at<now()-interval '60 seconds')",row.get("id"));
                if(claim==0)continue;
                try{
                    Object delivery=whatsapp.write("POST","/internal/calendar-reminder",Map.of("id",row.get("id").toString()),(String)row.get("user_id"));
                    if(delivery instanceof Map<?,?> result&&"FRONT_ONLY".equals(result.get("status")))
                        jdbc.update("update calendar_reminders set whatsapp_status='FRONT_ONLY' where id=? and whatsapp_status='PENDING'",row.get("id"));
                }
                catch(RuntimeException failure){
                    boolean connected=false;
                    try{Object status=whatsapp.get("/status",(String)row.get("user_id"));connected=status instanceof Map<?,?> state&&Boolean.TRUE.equals(state.get("connected"));}catch(RuntimeException unavailable){}
                    if(!connected)jdbc.update("update calendar_reminders set whatsapp_status='FRONT_ONLY' where id=? and whatsapp_status='PENDING'",row.get("id"));
                }
            }
        }catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Coleta de lembretes indisponível. tipo={}",failure.getClass().getSimpleName());}
    }
    @Scheduled(fixedDelay=15000,initialDelay=30000,scheduler="calendarScheduler")
    public void synchronize(){google.scheduled();}
}
