package ayla.auth;

import ayla.chat.session.ChatIdentity;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.security.Principal;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me/preferences")
@Tag(name="Preferências",description="Preferências pessoais do usuário autenticado.")
public class UserPreferencesController {
    public record Update(@NotNull UserPreferencesService.PantryView pantryView) {}
    private final UserPreferencesService preferences;
    private final ChatIdentity identity;
    public UserPreferencesController(UserPreferencesService preferences, ChatIdentity identity) { this.preferences=preferences; this.identity=identity; }
    private ResponseEntity<UserPreferencesService.Preferences> result(UserPreferencesService.Preferences value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
    @GetMapping
    public ResponseEntity<UserPreferencesService.Preferences> get(@Parameter(hidden=true) Principal principal) {
        return result(preferences.get(identity.resolve(principal)));
    }
    @PutMapping
    public ResponseEntity<UserPreferencesService.Preferences> save(@Valid @RequestBody Update input,@Parameter(hidden=true) Principal principal) {
        return result(preferences.save(identity.resolve(principal),input.pantryView()));
    }
}
