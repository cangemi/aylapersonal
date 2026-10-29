package ayla.whatsapp;

import ayla.chat.session.ChatIdentity;
import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/whatsapp")
@Tag(name="WhatsApp",description="Conexão, QR code e conversas autorizadas no agentNode.")
public class WhatsappController {
    private final WhatsappGateway gateway;
    private final ChatIdentity identity;
    public WhatsappController(WhatsappGateway gateway,ChatIdentity identity) {this.gateway=gateway;this.identity=identity;}
    private ResponseEntity<Object> result(Object value) {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
    @GetMapping("/status") @Operation(summary="Consultar estado, número conectado e persistência da sessão")
    public ResponseEntity<Object> status(@Parameter(hidden=true) Principal principal) {return result(gateway.get("/status",identity.resolve(principal)));}
    @GetMapping("/qr") @Operation(summary="Consultar QR atual",description="Retorna qr, qrImageDataUrl, qrAscii e generatedAt. Use qrImageDataUrl no src de uma imagem; consulte enquanto aguarda o pareamento.")
    public ResponseEntity<Object> qr(@Parameter(hidden=true) Principal principal) {return result(gateway.get("/qr",identity.resolve(principal)));}
    @PostMapping("/connect") @Operation(summary="Conectar ou gerar QR para novo pareamento")
    public ResponseEntity<Object> connect(@Parameter(hidden=true) Principal principal) {return result(gateway.post("/connect",identity.resolve(principal)));}
    @PostMapping("/disconnect") @Operation(summary="Pausar a conexão preservando a sessão no PostgreSQL")
    public ResponseEntity<Object> disconnect(@Parameter(hidden=true) Principal principal) {return result(gateway.post("/disconnect",identity.resolve(principal)));}
    @PostMapping("/restart") @Operation(summary="Reconectar usando a sessão salva")
    public ResponseEntity<Object> restart(@Parameter(hidden=true) Principal principal) {return result(gateway.post("/restart",identity.resolve(principal)));}
    @PostMapping("/logout") @Operation(summary="Sair e apagar a sessão salva",description="Se conectado, faz logout no WhatsApp. Mantém o cadastro das conversas; o próximo connect gera um novo QR.")
    public ResponseEntity<Object> logout(@Parameter(hidden=true) Principal principal) {return result(gateway.post("/logout",identity.resolve(principal)));}
    @PostMapping("/personal-chat") @Operation(summary="Criar ou reutilizar o grupo pessoal Ayla",description="Exige WhatsApp conectado. Cria um grupo Ayla sem convidar outros números e o configura como conversa principal. Salva o destino no PostgreSQL; próximas chamadas reutilizam o grupo. A principal anterior passa a aceitar chamadas pontuais.")
    public ResponseEntity<Object> personalChat(@Parameter(hidden=true) Principal principal) {return result(gateway.post("/personal-chat",identity.resolve(principal)));}
    @GetMapping("/chats") @Operation(summary="Listar números e grupos configurados",description="Uma conversa principal por instância (mentionOnly=false). As demais são chamadas pontuais (mentionOnly=true), com contexto isolado e sem acesso à memória pessoal. Configurar uma nova principal substitui a anterior, que passa a aceitar apenas chamadas.")
    public ResponseEntity<Object> list(@Parameter(hidden=true) Principal principal) {return result(gateway.get("/chats",identity.resolve(principal)));}
    @PostMapping("/chats") @Operation(summary="Autorizar telefone ou grupo",description="Exemplo: {\"phone\":\"5511999999999\",\"name\":\"Pedro\"}. Grupos usam chatId terminado em @g.us.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/json",examples={
        @io.swagger.v3.oas.annotations.media.ExampleObject(name="Telefone",value="{\"phone\":\"5511999999999\",\"name\":\"Pedro\"}"),
        @io.swagger.v3.oas.annotations.media.ExampleObject(name="Grupo",value="{\"chatId\":\"120363000000000000@g.us\",\"name\":\"Meu grupo\"}")
    }))
    public ResponseEntity<Object> create(@RequestBody Map<String,Object> input,@Parameter(hidden=true) Principal principal) {return result(gateway.write("POST","/chats",input,identity.resolve(principal)));}
    @PutMapping("/chats/{id}") @Operation(summary="Alterar número, nome ou habilitação",description="Aceita phone/chatId, name, enabled e mentionOnly. Todo atendimento é exclusivo do próprio número conectado. mentionOnly=true exige também a chamada Ayla0101, normalizada sem distinguir maiúsculas; false responde às mensagens do dono na conversa autorizada. Terceiros nunca acionam respostas. Ao mudar o destinatário, começa uma nova conversa de IA.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/json",examples=@io.swagger.v3.oas.annotations.media.ExampleObject(value="{\"enabled\":false}")))
    public ResponseEntity<Object> update(@PathVariable UUID id,@RequestBody Map<String,Object> input,@Parameter(hidden=true) Principal principal) {return result(gateway.write("PUT","/chats/"+id,input,identity.resolve(principal)));}
    @DeleteMapping("/chats/{id}") @Operation(summary="Excluir autorização desta conversa")
    public ResponseEntity<Object> delete(@PathVariable UUID id,@Parameter(hidden=true) Principal principal) {return result(gateway.write("DELETE","/chats/"+id,null,identity.resolve(principal)));}
}
