package ayla.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class UserPreferencesService {
    public enum PantryView { GRID, LIST, COMPACT, TABLE }
    public record Preferences(PantryView pantryView) {}
    private final JdbcTemplate jdbc;
    public UserPreferencesService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Preferences get(String userId) {
        return jdbc.query("select pantry_view from auth_accounts where user_id=? and enabled",
                (rs,n) -> new Preferences(PantryView.valueOf(rs.getString("pantry_view"))), userId)
            .stream().findFirst().orElseThrow(() -> new AuthException(404,"Conta não encontrada."));
    }

    public Preferences save(String userId, PantryView view) {
        if (jdbc.update("update auth_accounts set pantry_view=?,updated_at=now() where user_id=? and enabled",view.name(),userId) != 1)
            throw new AuthException(404,"Conta não encontrada.");
        return new Preferences(view);
    }
}
