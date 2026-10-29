package ayla.chat.controller;

import ayla.chat.session.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/conversations")
@Tag(name = "Conversas", description = "Conversas do usuário atual; cada conversa tem uma sessão própria do Hermes.")
public class ConversationController {
    public record Create(@Schema(description = "Título opcional. Se vazio, usa Nova conversa.", example = "Jogos") @Size(max = 120) String title) {}
    private final ConversationService conversations;
    private final ChatIdentity identity;
    private ConversationHistory history;
    private ConversationDeletion deletion;
    @org.springframework.beans.factory.annotation.Autowired
    public void setDeletion(ConversationDeletion deletion){this.deletion=deletion;}
    @org.springframework.beans.factory.annotation.Autowired
    public void setHistory(ConversationHistory history) {this.history=history;}
    public ConversationController(ConversationService conversations, ChatIdentity identity) {
        this.conversations = conversations; this.identity = identity;
    }
    @Operation(summary = "Listar minhas conversas", description = "Retorna somente as conversas do usuário atual, indicando qual será usada quando o chat não receber IDs.")
    @GetMapping public List<ConversationService.View> list(@Parameter(hidden = true) Principal principal) {
        return conversations.list(identity.resolve(principal));
    }
    @GetMapping("/{id}/deletion")
    @Operation(summary="Consultar o efeito de apagar esta conversa",description="Somente frontend autenticado. Verifica no WhatsApp se o grupo principal contém apenas o próprio número.")
    public Object deletionPlan(@PathVariable UUID id,Principal principal){return deletion.plan(identity.resolve(principal),id);}
    @DeleteMapping("/{id}")
    @Operation(summary="Apagar uma conversa pelo frontend",description="Memórias e dados pessoais permanecem. removeWhatsappGroup=true exige comprovar novamente que o grupo principal tem apenas o próprio número. Invocações removem somente o histórico da Ayla.")
    public Object delete(@PathVariable UUID id,@RequestParam(defaultValue="false") boolean removeWhatsappGroup,Principal principal){return deletion.delete(identity.resolve(principal),id,removeWhatsappGroup);}
    @GetMapping("/{id}/messages")
    @Operation(summary="Carregar mensagens salvas de uma conversa",description="Lê somente a sessão Hermes pertencente ao usuário autenticado. Traz as mensagens mais recentes em ordem cronológica; nextOffset permite carregar páginas anteriores. Não retorna ferramentas, raciocínio nem instruções internas. Limite padrão 100, máximo 200. Conversa ainda sem sessão retorna lista vazia.")
    public org.springframework.http.ResponseEntity<ConversationHistory.Page> messages(@PathVariable UUID id,
            @RequestParam(defaultValue="100") int limit,@RequestParam(defaultValue="0") int offset,
            @Parameter(hidden=true) Principal principal) {
        return org.springframework.http.ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(history.list(identity.resolve(principal),id,limit,offset));
    }
    @PostMapping @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @Operation(summary = "Criar e ativar uma conversa", description = "Torna a nova conversa atual. O corpo é opcional; a sessão no Hermes será criada no primeiro envio de mensagem.",
        responses = @ApiResponse(responseCode = "201", description = "Conversa criada e definida como atual"))
    public ConversationService.View create(@Valid @RequestBody(required = false) Create request,
                                            @Parameter(hidden = true) Principal principal) {
        return conversations.newConversation(identity.resolve(principal), request == null ? null : request.title());
    }
    @Operation(summary = "Trocar a conversa atual", description = "As próximas mensagens sem IDs continuarão esta conversa. O identificador precisa pertencer ao usuário atual.",
        responses = {@ApiResponse(responseCode = "200", description = "Conversa atualizada"),
                     @ApiResponse(responseCode = "404", description = "Conversa não encontrada para o usuário")})
    @PutMapping("/{id}/current") public ConversationService.View activate(
            @Parameter(description = "conversationId retornado ao criar ou listar conversas") @PathVariable UUID id,
            @Parameter(hidden = true) Principal principal) {
        return conversations.activate(identity.resolve(principal), id);
    }
}
