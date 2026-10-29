package ayla.auth;

import static ayla.auth.AuthData.*;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name="Autenticação",description="Login com senha, access token Bearer, renovação e revogação no PostgreSQL.")
public class AuthController {
    private final AuthService auth;
    private final PasswordRecoveryService recovery;
    public AuthController(AuthService auth,PasswordRecoveryService recovery){this.auth=auth;this.recovery=recovery;}
    private <T> ResponseEntity<T> result(T value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
    @PostMapping("/setup") @SecurityRequirements
    @Operation(summary="Criar o primeiro login do proprietário",description="Uma única vez, usando X-Ayla-Setup-Key configurada localmente em AYLA_AUTH_SETUP_KEY. Vincula ao usuário existente, preservando todos os dados. Não é cadastro público. Senha: mínimo 12 caracteres, máximo 72 bytes UTF-8.")
    public ResponseEntity<User> setup(@RequestHeader(value="X-Ayla-Setup-Key",defaultValue="") String key,@Valid @RequestBody Credentials input){var user=auth.setup(key,input);ayla.monitoring.MonitoringContext.user(user.id());return result(user);}
    @PostMapping("/login") @SecurityRequirements
    @Operation(summary="Entrar e obter tokens",description="Login não diferencia maiúsculas/minúsculas; senha é exata. Access token dura 15 minutos; refresh token tem limite de 30 dias. Três falhas bloqueiam a conta por 30 minutos. Mais três após o desbloqueio bloqueiam por uma hora; novos blocos mantêm uma hora. Login bem-sucedido e redefinição de senha zeram as tentativas. Requisições durante o bloqueio não o prolongam.")
    public ResponseEntity<Tokens> login(@Valid @RequestBody Credentials input,HttpServletRequest request){var tokens=auth.login(input,request.getRemoteAddr());request.setAttribute(ayla.monitoring.MonitoringContext.USER,tokens.user().id());return result(tokens);}
    @PostMapping("/refresh") @SecurityRequirements
    @Operation(summary="Renovar tokens",description="Envie refreshToken no corpo. Retorna novos access/refresh tokens e invalida os anteriores. Reutilizar refresh token consumido revoga a sessão. Faça uma renovação por vez no frontend.")
    public ResponseEntity<Tokens> refresh(@Valid @RequestBody Refresh input){var tokens=auth.refresh(input.refreshToken());ayla.monitoring.MonitoringContext.user(tokens.user().id());return result(tokens);}
    @PostMapping("/forgot-password") @SecurityRequirements
    @Operation(summary="Solicitar senha aleatória no WhatsApp cadastrado",description="Envie somente username. Resposta genérica 202; não informa se o login/telefone existe. Usa o número da instância conectada vinculada ao usuário, sem aceitar destinatário alternativo. A nova senha pode ser ativada pelo login em até 15 minutos; só então substitui a antiga e revoga as sessões. Há intervalo de cinco minutos por conta e limite por endereço remoto. O WhatsApp precisa estar conectado.")
    public ResponseEntity<?> forgot(@Valid @RequestBody Forgot input,HttpServletRequest request) {
        recovery.request(input.username(),request.getRemoteAddr());
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(java.util.Map.of("message","Se houver uma conta com WhatsApp cadastrado e disponível, a nova senha será enviada para esse número."));
    }
    @PostMapping("/recovery-channel")
    @Operation(summary="Vincular o WhatsApp conectado para recuperação",description="Exige access token e currentPassword. Não aceita outro telefone: vincula o número da instância deste usuário. O primeiro cadastro já tenta fazer esse vínculo automaticamente. Trocar o WhatsApp conectado não troca o destinatário de recuperação sem essa confirmação da senha atual.")
    public ResponseEntity<?> recoveryChannel(@AuthenticationPrincipal Identity identity,@Valid @RequestBody RecoveryChannel input){return result(auth.bindRecoveryChannel(identity,input));}
    @GetMapping("/me") @Operation(summary="Consultar usuário autenticado")
    public ResponseEntity<User> me(@AuthenticationPrincipal Identity identity){return result(auth.me(identity));}
    @PutMapping("/profile") @Operation(summary="Alterar o nome usado no painel e pela Ayla",description="Atualiza somente o nome do próprio usuário. Não altera o login.")
    public ResponseEntity<User> profile(@AuthenticationPrincipal Identity identity,@Valid @RequestBody Profile input){return result(auth.updateProfile(identity,input));}
    @PostMapping("/onboarding/complete") @Operation(summary="Concluir a configuração inicial",description="Exige nome definido e, para usuários criados pelo proprietário, a troca da senha provisória.")
    public ResponseEntity<User> completeOnboarding(@AuthenticationPrincipal Identity identity){return result(auth.completeOnboarding(identity));}
    @PostMapping("/logout") @ResponseStatus(HttpStatus.NO_CONTENT) @Operation(summary="Revogar a sessão atual")
    public void logout(@AuthenticationPrincipal Identity identity){auth.logout(identity,false);}
    @PostMapping("/logout-all") @ResponseStatus(HttpStatus.NO_CONTENT) @Operation(summary="Revogar todas as sessões deste usuário")
    public void logoutAll(@AuthenticationPrincipal Identity identity){auth.logout(identity,true);}
    @PostMapping("/change-password") @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary="Alterar senha e encerrar todas as sessões",description="Exige senha atual e nova senha. Depois, faça login novamente.")
    public void password(@AuthenticationPrincipal Identity identity,@Valid @RequestBody PasswordChange input){auth.changePassword(identity,input);}
    @PostMapping("/users") @Operation(summary="Criar usuário adicional",description="Somente proprietário. Novo usuário tem dados isolados; conversar com Hermes exige configurar um perfil isolado para ele. Não compartilha o perfil do proprietário.")
    public ResponseEntity<User> user(@AuthenticationPrincipal Identity actor,@Valid @RequestBody Credentials input){auth.requireOwner(actor);return result(auth.createUser(input));}
    @GetMapping("/users") @Operation(summary="Listar todos os usuários cadastrados",description="Exclusivo do superadministrador. Busca por login/nome e paginação. Não retorna senhas, hashes, tokens ou dados de recuperação.")
    public ResponseEntity<UserPage> users(@AuthenticationPrincipal Identity actor,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return result(auth.users(actor,q,page,size));}
    @DeleteMapping("/users/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary="Excluir um usuário e todos os seus dados",description="Exclusivo do superadministrador. Remove login, sessões, conversas, finanças, despensa, memórias e WhatsApp do usuário. Irreversível. O superadministrador não pode ser excluído.")
    public ResponseEntity<Void> deleteUser(@AuthenticationPrincipal Identity actor,@PathVariable String id){auth.deleteUser(actor,id);return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();}
    @PatchMapping("/users/{id}/status") @Operation(summary="Inativar ou reativar um usuário",description="Exclusivo do superadministrador. Inativação preserva os dados e encerra sessões. Reativação exige novo login. O superadministrador não pode ser inativado.")
    public ResponseEntity<Void> status(@AuthenticationPrincipal Identity actor,@PathVariable String id,@Valid @RequestBody AccountState input){auth.setEnabled(actor,id,input.enabled());return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();}
    @PostMapping("/users/{id}/reset-password") @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary="Redefinir a senha de um usuário",description="Exclusivo do superadministrador. Recebe newPassword, invalida recuperações pendentes e encerra todas as sessões do usuário escolhido. Se redefinir a própria senha, precisa entrar novamente.")
    public ResponseEntity<Void> resetPassword(@AuthenticationPrincipal Identity actor,@PathVariable String id,@Valid @RequestBody AdminPasswordReset input){auth.resetPassword(actor,id,input);return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();}
}
