package ayla.push;

import java.security.Principal;
import java.util.Map;
import ayla.chat.session.ChatIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/push")
public class PushController {
    private final PushService push;
    private final ChatIdentity identity;
    public PushController(PushService push, ChatIdentity identity) { this.push = push; this.identity = identity; }

    public record SubscriptionRequest(@NotBlank String endpoint, Keys keys, String userAgent) {
        public record Keys(@NotBlank String p256dh, @NotBlank String auth) {}
    }
    public record PreferenceRequest(boolean enabled) {}

    @GetMapping("/public-key")
    public Map<String, String> publicKey() { return Map.of("publicKey", push.publicKey()); }

    @PostMapping("/subscriptions")
    public Map<String, Boolean> subscribe(Principal p, @Valid @RequestBody SubscriptionRequest request) {
        String user = identity.resolve(p);
        push.saveSubscription(user, request.endpoint(), request.keys().p256dh(), request.keys().auth(), request.userAgent());
        return Map.of("subscribed", true);
    }

    @DeleteMapping("/subscriptions")
    public Map<String, Boolean> unsubscribe(Principal p, @RequestBody SubscriptionRequest request) {
        String user = identity.resolve(p);
        push.deleteSubscription(user, request.endpoint());
        return Map.of("unsubscribed", true);
    }

    @PostMapping("/test")
    public Map<String, Boolean> test(Principal p) {
        String user = identity.resolve(p);
        push.sendTest(user);
        return Map.of("sent", true);
    }

    @GetMapping("/preferences")
    public Map<String, Boolean> preferences(Principal p) {
        String user = identity.resolve(p);
        return Map.of("enabled", push.preferenceEnabled(user));
    }

    @PutMapping("/preferences")
    public Map<String, Boolean> setPreferences(Principal p, @RequestBody PreferenceRequest request) {
        String user = identity.resolve(p);
        push.updatePreference(user, request.enabled());
        return Map.of("enabled", request.enabled());
    }
}