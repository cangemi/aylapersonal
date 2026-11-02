package ayla.alarm;

import static ayla.alarm.AlarmData.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ayla.hermes.HermesException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
public class AlarmService {
    final JdbcTemplate jdbc;
    static final RowMapper<Alarm> ROW = (r, n) -> new Alarm(
            r.getObject("id", UUID.class),
            r.getString("title"),
            r.getString("description"),
            r.getString("alarm_type"),
            r.getTimestamp("alarm_at") == null ? null : r.getTimestamp("alarm_at").toInstant(),
            r.getTime("time_of_day")==null?null:r.getTime("time_of_day").toLocalTime().withSecond(0).withNano(0).toString(),
            r.getObject("day_of_week", Integer.class),
            r.getString("time_zone"),
            r.getBoolean("confirm_needed"),
            r.getBoolean("active"),
            r.getLong("version"),
            r.getTimestamp("created_at").toInstant(),
            r.getTimestamp("updated_at").toInstant(),
            r.getTimestamp("last_fired_at") == null ? null : r.getTimestamp("last_fired_at").toInstant(),
            r.getBoolean("confirm_pending"),
            r.getTimestamp("next_snooze_at") == null ? null : r.getTimestamp("next_snooze_at").toInstant());
    public AlarmService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setProactive(ayla.chat.session.ProactiveMessageService proactive) { this.proactive = proactive; }
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPush(ayla.push.PushService push) { this.push = push; }
    ayla.chat.session.ProactiveMessageService proactive;
    ayla.push.PushService push;

    public List<Alarm> list(String user) {
        return jdbc.query("select * from alarms where user_id=? order by active desc, created_at desc", ROW, user);
    }
    public List<Alarm> pendingConfirmations(String user) {
        return list(user).stream().filter(Alarm::confirmPending).toList();
    }

    public Alarm get(String user, UUID id) {
        return jdbc.query("select * from alarms where user_id=? and id=?", ROW, user, id).stream().findFirst()
                .orElseThrow(() -> error(404, "Alarme não encontrado."));
    }

    @Transactional
    public Alarm save(String user, UUID id, Input input, String key) {
        Alarm old = null;
        if (id != null) {
            jdbc.queryForList("select id from alarms where user_id=? and id=? for update", user, id);
            old = get(user, id);
        }
        String title = input.title() == null ? (old == null ? "" : old.title()) : input.title().strip();
        if (title.isBlank()) throw error(400, "Qual é o alarme?");
        String type = input.type() == null ? (old == null ? "ONCE" : old.type()) : input.type().trim().toUpperCase(Locale.ROOT);
        if (!type.equals("ONCE") && !type.equals("DAILY") && !type.equals("WEEKLY")) throw error(400, "Tipo de alarme inválido.");
        String zone = input.timeZone() == null ? (old == null ? "America/Sao_Paulo" : old.timeZone()) : input.timeZone();
        try { ZoneId.of(zone); } catch (Exception e) { throw error(400, "Fuso horário inválido."); }
        boolean confirm = input.confirmNeeded() == null ? (old != null && old.confirmNeeded()) : input.confirmNeeded();
        boolean active = input.active() == null ? (old == null || old.active()) : input.active();

        Instant alarmAt = null;
        String timeOfDay = null;
        Integer dayOfWeek = null;
        if (type.equals("ONCE")) {
            if (input.alarmAt() != null) alarmAt = input.alarmAt().toInstant();
            else if (input.delayMinutes() != null) alarmAt = Instant.now().plusSeconds(input.delayMinutes() * 60L);
            else if (old != null && old.alarmAt() != null) alarmAt = old.alarmAt();
            if (alarmAt == null) throw error(400, "Para alarme único, informe a data e hora do alarme ou daqui a quantos minutos.");
            if (!alarmAt.isAfter(Instant.now()) && old == null) throw error(400, "O alarme deve ser no futuro.");
            timeOfDay = null;
        } else {
            if (input.timeOfDay() != null) timeOfDay = normalizeTime(input.timeOfDay());
            else if (old != null) timeOfDay = old.timeOfDay();
            if (timeOfDay == null || timeOfDay.isBlank()) throw error(400, "Para alarme recorrente, informe o horário (HH:mm).");
            if (type.equals("WEEKLY")) {
                if (input.dayOfWeek() != null) dayOfWeek = input.dayOfWeek();
                else if (old != null) dayOfWeek = old.dayOfWeek();
                if (dayOfWeek == null || dayOfWeek < 1 || dayOfWeek > 7) throw error(400, "Para alarme semanal, informe o dia da semana (1=segunda ... 7=domingo).");
            } else dayOfWeek = null;
            alarmAt = null;
        }

        if (id != null) {
            if (input.version() == null) throw error(400, "Consulte o alarme atual antes de editar.");
            if (input.version() != old.version()) throw error(409, "O alarme mudou. Consulte novamente.");
            jdbc.update("update alarms set title=?,description=?,alarm_type=?,alarm_at=?,time_of_day=?,day_of_week=?,time_zone=?,confirm_needed=?,active=?,version=version+1,updated_at=now() where user_id=? and id=?",
                    title, input.description() == null ? (old == null ? "" : old.description()) : input.description(), type,
                    alarmAt == null ? null : Timestamp.from(alarmAt),
                    timeOfDay == null ? null : java.sql.Time.valueOf(timeOfDay + ":00"), dayOfWeek, zone, confirm, active, user, id);
        } else {
            UUID eventId = UUID.randomUUID();
            jdbc.update("insert into alarms(id,user_id,title,description,alarm_type,alarm_at,time_of_day,day_of_week,time_zone,confirm_needed,active) values (?,?,?,?,?,?,?,?,?,?,?)",
                    eventId, user, title, input.description() == null ? "" : input.description(), type,
                    alarmAt == null ? null : Timestamp.from(alarmAt), timeOfDay == null ? null : java.sql.Time.valueOf(timeOfDay + ":00"), dayOfWeek, zone, confirm, active);
            id = eventId;
        }
        return get(user, id);
    }

    private String normalizeTime(String value) {
        String v = value.trim();
        if (v.matches("^\\d{1,2}:\\d{2}$")) {
            String[] parts = v.split(":");
            int h = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            if (h < 0 || h > 23 || m < 0 || m > 59) throw error(400, "Horário inválido. Use HH:mm.");
            return String.format("%02d:%02d", h, m);
        }
        throw error(400, "Horário inválido. Use HH:mm.");
    }

    @Transactional
    public void delete(String user, UUID id, long version) {
        Alarm alarm = get(user, id);
        if (alarm.version() != version) throw error(409, "O alarme mudou. Consulte novamente.");
        jdbc.update("delete from alarm_notifications where alarm_id=?", id);
        jdbc.update("delete from alarms where user_id=? and id=?", user, id);
    }

    public List<Notification> notifications(String user, boolean unread) {
        String sql = "select n.id,n.alarm_id,n.due_at,n.notified_at,n.whatsapp_status,n.read_at,n.confirmed_at,n.snooze,a.title from alarm_notifications n join alarms a on a.id=n.alarm_id where n.user_id=? and n.notified_at is not null";
        if (unread) sql += " and n.read_at is null";
        sql += " order by n.due_at desc limit 50";
        return jdbc.query(sql, (r, n) -> new Notification(r.getObject("id", UUID.class), r.getObject("alarm_id", UUID.class),
                r.getTimestamp("due_at").toInstant(), r.getTimestamp("notified_at") == null ? null : r.getTimestamp("notified_at").toInstant(),
                r.getString("whatsapp_status"), r.getTimestamp("read_at") == null ? null : r.getTimestamp("read_at").toInstant(),
                r.getTimestamp("confirmed_at") == null ? null : r.getTimestamp("confirmed_at").toInstant(), r.getBoolean("snooze"), r.getString("title")), user);
    }

    public void read(String user, UUID id) {
        jdbc.update("delete from alarm_notifications n using alarms a where n.alarm_id=a.id and a.user_id=? and n.id=?", user, id);
    }

    @Transactional
    public void confirm(String user, UUID alarmId) {
        Alarm alarm = get(user, alarmId);
        jdbc.update("update alarm_notifications set confirmed_at=coalesce(confirmed_at,now()), read_at=coalesce(read_at,now()) where alarm_id=? and confirmed_at is null", alarmId);
        jdbc.update("update alarms set confirm_pending=false, next_snooze_at=null, active = case when alarm_type in ('DAILY','WEEKLY') then true else false end, updated_at=now() where user_id=? and id=?", user, alarmId);
    }

    // Worker helpers
    @Transactional
    public void fireDue() {
        runSafely("alarmes únicos", this::fireOnceAlarms);
        runSafely("alarmes diários", this::fireDailyAlarms);
        runSafely("alarmes semanais", this::fireWeeklyAlarms);
        runSafely("alarmes com soneca", this::createSnoozes);
    }
    private void runSafely(String label, Runnable action) {
        try { action.run(); }
        catch (RuntimeException error) {
            org.slf4j.LoggerFactory.getLogger(getClass()).error("Falha ao processar " + label + ".", error);
        }
    }

    private void fireOnceAlarms() {
        Instant now = Instant.now();
        List<Alarm> alarms = jdbc.query("select * from alarms where active=true and alarm_type='ONCE' and alarm_at<=? and last_fired_at is null", ROW, Timestamp.from(now));
        for (Alarm a : alarms) {
            UUID notif = UUID.randomUUID();
            jdbc.update("insert into alarm_notifications(id,alarm_id,user_id,due_at,notified_at) values (?,?,?,?,now())", notif, a.id(), findUser(a.id()), Timestamp.from(now));
            recordProactiveMessage(a.id(), findUser(a.id()), notif);
            updateAlarmFired(a, now, true);
        }
    }

    private String findUser(UUID alarmId) {
        return jdbc.queryForObject("select user_id from alarms where id=?", String.class, alarmId);
    }

    private void updateAlarmFired(Alarm a, Instant now, boolean deactivate) {
        if (a.confirmNeeded()) {
            jdbc.update("update alarms set last_fired_at=?,active=?,confirm_pending=true,next_snooze_at=? where id=?", Timestamp.from(now), true, Timestamp.from(now.plus(Duration.ofMinutes(5))), a.id());
        } else {
            jdbc.update("update alarms set last_fired_at=?,active=? where id=?", Timestamp.from(now), false, a.id());
        }
    }

    private void fireDailyAlarms() {
        List<Alarm> alarms = jdbc.query("select * from alarms where active=true and alarm_type='DAILY'", ROW);
        Instant now = Instant.now();
        for (Alarm a : alarms) {
            ZoneId zone = ZoneId.of(a.timeZone());
            ZonedDateTime nowZ = now.atZone(zone);
            String[] parts = a.timeOfDay().split(":");
            int h = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            ZonedDateTime todayAt = nowZ.toLocalDate().atTime(h, m).atZone(zone);
            if (now.isBefore(todayAt.toInstant())) continue;
            // Já disparou hoje só se for DEPOIS do horário agendado de hoje.
            // Uma data igual basta quando o alarme foi criado/ligado no mesmo dia,
            // senão o disparo real do dia é ignorado.
            if (a.lastFiredAt() != null && !a.lastFiredAt().isBefore(todayAt.toInstant())) continue;
            UUID notif = UUID.randomUUID();
            String user = findUser(a.id());
            jdbc.update("insert into alarm_notifications(id,alarm_id,user_id,due_at,notified_at) values (?,?,?,?,now())", notif, a.id(), user, Timestamp.from(todayAt.toInstant()));
             recordProactiveMessage(a.id(), user, notif);
            if (a.confirmNeeded()) {
                jdbc.update("update alarms set last_fired_at=?,confirm_pending=true,next_snooze_at=? where id=?", Timestamp.from(now), Timestamp.from(now.plus(Duration.ofMinutes(5))), a.id());
            } else {
                jdbc.update("update alarms set last_fired_at=? where id=?", Timestamp.from(now), a.id());
            }
        }
    }

    private void fireWeeklyAlarms() {
        List<Alarm> alarms = jdbc.query("select * from alarms where active=true and alarm_type='WEEKLY'", ROW);
        Instant now = Instant.now();
        for (Alarm a : alarms) {
            ZoneId zone = ZoneId.of(a.timeZone());
            ZonedDateTime nowZ = now.atZone(zone);
            int todayDow = nowZ.getDayOfWeek().getValue(); // ISO Monday=1
            if (a.dayOfWeek() == null || a.dayOfWeek() != todayDow) continue;
            String[] parts = a.timeOfDay().split(":");
            int h = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            ZonedDateTime todayAt = nowZ.toLocalDate().atTime(h, m).atZone(zone);
            if (now.isBefore(todayAt.toInstant())) continue;
            // Já disparou hoje só se for DEPOIS do horário agendado de hoje.
            // Uma data igual basta quando o alarme foi criado/ligado no mesmo dia,
            // senão o disparo real do dia é ignorado.
            if (a.lastFiredAt() != null && !a.lastFiredAt().isBefore(todayAt.toInstant())) continue;
            UUID notif = UUID.randomUUID();
            String user = findUser(a.id());
            jdbc.update("insert into alarm_notifications(id,alarm_id,user_id,due_at,notified_at) values (?,?,?,?,now())", notif, a.id(), user, Timestamp.from(todayAt.toInstant()));
             recordProactiveMessage(a.id(), user, notif);
            if (a.confirmNeeded()) {
                jdbc.update("update alarms set last_fired_at=?,confirm_pending=true,next_snooze_at=? where id=?", Timestamp.from(now), Timestamp.from(now.plus(Duration.ofMinutes(5))), a.id());
            } else {
                jdbc.update("update alarms set last_fired_at=? where id=?", Timestamp.from(now), a.id());
            }
        }
    }

    private void createSnoozes() {
        Instant now = Instant.now();
        List<Map<String, Object>> pending = jdbc.queryForList("select id,user_id,next_snooze_at from alarms where active=true and confirm_pending=true and next_snooze_at<=?", Timestamp.from(now));
        for (var row : pending) {
            UUID alarmId = (UUID) row.get("id");
            String user = (String) row.get("user_id");
            UUID notif = UUID.randomUUID();
            jdbc.update("insert into alarm_notifications(id,alarm_id,user_id,due_at,notified_at,snooze) values (?,?,?,?,now(),true)", notif, alarmId, user, Timestamp.from(now));
            recordProactiveMessage(alarmId, user, notif);
            jdbc.update("update alarms set next_snooze_at=? where id=?", Timestamp.from(now.plus(Duration.ofMinutes(5))), alarmId);
        }
    }

    private void recordProactiveMessage(UUID alarmId, String user, UUID notificationId) {
        if (proactive == null || alarmId == null || user == null) return;
        try {
            var row = jdbc.queryForMap("select title, confirm_needed from alarms where id=?", alarmId);
            String title = String.valueOf(row.get("title"));
            boolean confirm = Boolean.TRUE.equals(row.get("confirm_needed"));
            String text = "⏰ Alarme: " + title + (confirm ? "\nResponda \"ok\" para parar os lembretes a cada 5 minutos." : "");
            if (proactive != null) proactive.record(user, proactive.currentConversation(user).orElse(null), text, "alarm", alarmId);
        } catch (RuntimeException ignored) {
        }
    }

    static HermesException error(int status, String message) { return new HermesException(status, message); }
}

