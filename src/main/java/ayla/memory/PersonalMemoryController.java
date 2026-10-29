package ayla.memory;

import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import ayla.chat.session.ChatIdentity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/memories")
@Tag(name="Memória",description="Lembranças pessoais persistidas, separadas por usuário.")
public class PersonalMemoryController {
    private final PersonalMemoryService memory;
    private final ChatIdentity identity;
    public PersonalMemoryController(PersonalMemoryService memory,ChatIdentity identity) { this.memory=memory;this.identity=identity; }
    @GetMapping
    @Operation(summary="Listar até 200 lembranças ou buscar por relevância",description="Não retorna lembranças expiradas. q usa busca textual local em português, sem API de embeddings.")
    public List<PersonalMemory.View> list(Principal principal,@RequestParam(required=false) String q) {
        String user=identity.resolve(principal);
        return q==null?memory.list(user):memory.search(user,q,20);
    }
    @DeleteMapping("/{id}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    @Operation(summary="Apagar uma lembrança do usuário atual")
    public void delete(Principal principal,@PathVariable UUID id) { memory.delete(identity.resolve(principal),id); }
}
