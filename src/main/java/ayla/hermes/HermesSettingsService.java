package ayla.hermes;

import org.springframework.stereotype.Service;

@Service
public class HermesSettingsService {
    private final HermesConfigurationRepository repository;
    private final HermesModels models;
    public HermesSettingsService(HermesConfigurationRepository repository, HermesModels models) {
        this.repository = repository; this.models = models;
    }
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    private HermesUserScope scope;
    @org.springframework.beans.factory.annotation.Autowired
    void setScope(HermesUserScope scope){this.scope=scope;}
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setJdbc(org.springframework.jdbc.core.JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** Hermes profile of a user with a personal profile; null means the shared (owner) configuration. */
    public String profile(String userId) {
        if(scope==null)throw new HermesException(503,"Isolamento do Hermes indisponível.");
        String p=scope.profile(userId,true);
        return "default".equals(p)?null:p;
    }
    public HermesConfiguration get(String userId) {
        if(scope==null)throw new HermesException(503,"Isolamento do Hermes indisponível.");
        if (scope.owner(userId)) return get();
        var rows = jdbc.queryForList("select model,instructions from user_agent_settings where user_id=?", userId);
        var config = new HermesConfiguration();
        if (!rows.isEmpty()) {
            config.setModel((String) rows.getFirst().get("model"));
            config.setInstructions((String) rows.getFirst().get("instructions"));
        }
        return config;
    }
    public HermesConfiguration update(String userId, String model, String instructions) {
        if(scope==null)throw new HermesException(503,"Isolamento do Hermes indisponível.");
        if (scope.owner(userId)) return update(model, instructions);
        String selected = model == null || model.isBlank() ? null : model.trim();
        if (selected != null) models.select(selected, scope.profile(userId,true));
        String text = instructions == null ? "" : instructions.trim();
        jdbc.update("""
                insert into user_agent_settings(user_id,model,instructions) values (?,?,?)
                on conflict (user_id) do update set model=excluded.model,instructions=excluded.instructions""", userId, selected, text);
        return get(userId);
    }
    public HermesConfiguration get() { return repository.findById(1L).orElseGet(HermesConfiguration::new); }
    public HermesConfiguration update(String model, String instructions) {
        String selected = model == null || model.isBlank() ? null : model.trim();
        if (selected != null) models.select(selected);
        var settings = get();
        settings.setModel(selected);
        settings.setInstructions(instructions == null ? "" : instructions.trim());
        return repository.save(settings);
    }
}
