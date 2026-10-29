package ayla.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import io.swagger.v3.oas.annotations.Hidden;
import ayla.hermes.HermesException;
import ayla.memory.PersonalMemory.Fact;

@Hidden
@RestController
@RequestMapping("/internal/hermes/memory")
public class PersonalMemoryBridge {
    private final PersonalMemoryService memory;
    private final JdbcTemplate jdbc;
    private final byte[] key;
    private final ayla.chat.session.ConversationContext context;
    public PersonalMemoryBridge(PersonalMemoryService memory,JdbcTemplate jdbc,@Value("${ayla.finance.bridge-key:}") String key) {
        this.memory=memory;this.jdbc=jdbc;this.key=key.getBytes(StandardCharsets.UTF_8);this.context=new ayla.chat.session.ConversationContext(jdbc);
    }
    public record Command(@Size(min=1,max=8) List<@NotNull @Valid Fact> facts,
                          @Size(max=1000) String query,UUID id) {}
    @PostMapping("/{operation}")
    @Transactional
    public Object execute(@RequestHeader(value="Authorization",defaultValue="") String auth,
                          @RequestHeader(value="X-Ayla-Session",defaultValue="") String session,
                          @PathVariable String operation,@Valid @RequestBody Command command) {
        if(key.length<32 || !auth.startsWith("Bearer ") || !MessageDigest.isEqual(key,auth.substring(7).getBytes(StandardCharsets.UTF_8)))
            throw new HermesException(401,"Integração de memória não autorizada.");
        var scope="save".equals(operation)?context.requireCollect(session):context.requirePrimary(session);
        String user=scope.userId();
        ayla.monitoring.MonitoringContext.user(user);
        return switch(operation) {
            case "save" -> {
                if(command.facts()==null || command.facts().isEmpty()) throw new HermesException(400,"Informe os fatos a guardar.");
                yield memory.save(user,scope.conversationId(),command.facts());
            }
            case "search" -> memory.search(user,command.query(),12);
            case "delete" -> {
                if(command.id()==null) throw new HermesException(400,"Consulte a lembrança antes de apagar.");
                memory.delete(user,command.id()); yield Map.of("deleted",true);
            }
            default -> throw new HermesException(404,"Operação não encontrada.");
        };
    }
}
