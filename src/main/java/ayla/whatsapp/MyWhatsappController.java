package ayla.whatsapp;

import ayla.chat.session.ChatIdentity;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me/whatsapp")
@Tag(name = "WhatsApp", description = "Conexão WhatsApp do próprio usuário autenticado.")
public class MyWhatsappController {
    private final WhatsappGateway gateway;
    private final ChatIdentity identity;
    public MyWhatsappController(WhatsappGateway gateway, ChatIdentity identity) { this.gateway = gateway; this.identity = identity; }
    private ResponseEntity<Object> result(Object value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
    @GetMapping("/status") public ResponseEntity<Object> status(@Parameter(hidden = true) Principal p) { return result(gateway.get("/status", identity.resolve(p))); }
    @GetMapping("/qr") public ResponseEntity<Object> qr(@Parameter(hidden = true) Principal p) { return result(gateway.get("/qr", identity.resolve(p))); }
    @PutMapping("/configuration") public ResponseEntity<Object> configure(@RequestBody java.util.Map<String, Object> input, @Parameter(hidden = true) Principal p) { return result(gateway.write("PUT", "/configuration", input, identity.resolve(p))); }
    @GetMapping("/ayla/qr") public ResponseEntity<Object> aylaQr(@Parameter(hidden = true) Principal p) { return result(gateway.get("/ayla/qr", identity.resolve(p))); }
    @PostMapping("/ayla/{action:connect|disconnect|restart|logout}") public ResponseEntity<Object> aylaAction(@PathVariable String action, @Parameter(hidden = true) Principal p) { return result(gateway.post("/ayla/" + action, identity.resolve(p))); }
    @PostMapping("/connect") public ResponseEntity<Object> connect(@Parameter(hidden = true) Principal p) { return result(gateway.post("/connect", identity.resolve(p))); }
    @PostMapping("/disconnect") public ResponseEntity<Object> disconnect(@Parameter(hidden = true) Principal p) { return result(gateway.post("/disconnect", identity.resolve(p))); }
    @PostMapping("/logout") public ResponseEntity<Object> logout(@Parameter(hidden = true) Principal p) { return result(gateway.post("/logout", identity.resolve(p))); }
    @PostMapping("/personal-chat") public ResponseEntity<Object> personalChat(@Parameter(hidden = true) Principal p) { return result(gateway.post("/personal-chat", identity.resolve(p))); }
        @PostMapping("/restart") public ResponseEntity<Object> restart(@Parameter(hidden = true) Principal p) { return result(gateway.post("/restart", identity.resolve(p))); }
    @GetMapping("/chats") public ResponseEntity<Object> chats(@Parameter(hidden = true) Principal p) { return result(gateway.get("/chats", identity.resolve(p))); }
        @PostMapping("/chats") public ResponseEntity<Object> createChat(@RequestBody java.util.Map<String, Object> input, @Parameter(hidden = true) Principal p) { return result(gateway.write("POST", "/chats", input, identity.resolve(p))); }
        @PutMapping("/chats/{id}") public ResponseEntity<Object> updateChat(@PathVariable java.util.UUID id, @RequestBody java.util.Map<String, Object> input, @Parameter(hidden = true) Principal p) { return result(gateway.write("PUT", "/chats/" + id, input, identity.resolve(p))); }
        @DeleteMapping("/chats/{id}") public ResponseEntity<Object> deleteChat(@PathVariable java.util.UUID id, @Parameter(hidden = true) Principal p) { return result(gateway.write("DELETE", "/chats/" + id, null, identity.resolve(p))); }
    }
