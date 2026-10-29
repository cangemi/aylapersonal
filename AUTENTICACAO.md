# Login e tokens da API Java

## Administração de usuários pelo proprietário

Em **Configurações → Usuários**, o superadministrador (`OWNER`, correspondente ao proprietário configurado da instância) pode cadastrar e listar todas as contas, com busca por nome/login e paginação, e usar **Redefinir senha** em cada linha. A listagem exibe nome, login, permissão, estado e data de cadastro, sem hashes, senhas, tokens ou dados de recuperação.

- `GET /api/v1/auth/users?q=&page=0&size=20`: listagem paginada, até 100 contas por página.
- `POST /api/v1/auth/users/{id}/reset-password`: corpo com `newPassword`; sucesso `204`, sem devolver a senha.

A nova senha é escolhida no formulário e precisa ser confirmada. Ao salvar, o backend grava somente seu hash BCrypt, invalida a recuperação pendente, remove o bloqueio de tentativas da conta e revoga todas as suas sessões de acesso/renovação. Isso não altera o estado de habilitação nem as sessões de outras contas. A listagem e a redefinição exigem o proprietário tanto no filtro de segurança quanto no serviço. Usuários comuns e chaves internas não recebem essa permissão. A recuperação pública por WhatsApp continua independente.

O proprietário também pode redefinir a própria senha; nesse caso, o frontend avisa antes de salvar e volta para o login após concluir. As ações administrativas são registradas no monitoramento sem o conteúdo das senhas. Os testes usam contas fictícias em PostgreSQL temporário; nenhuma senha real é alterada para validar a funcionalidade.

Todos os endpoints de dados pessoais exigem autenticação: chat/conversas, financeiro, memória, despensa, compras, configuração e conexão do WhatsApp. O modo pessoal anônimo foi removido. A identidade vem do token validado pelo servidor, nunca de userId no JSON ou de cabeçalhos escolhidos pelo frontend.

O primeiro cadastro usa exatamente o `AYLA_CHAT_OWNER_ID` existente (padrão `local-owner`), preservando os registros e vínculos atuais. Este login é da API Java; é independente do login Google/Nous e da senha do painel Hermes.

## Criar seu primeiro acesso

1. Abra `http://localhost:8080/swagger-ui/index.html`, seção **Autenticação**.
2. No `.env` local, copie o valor de `AYLA_AUTH_SETUP_KEY`. Uma chave aleatória já foi gerada na instalação atual, sem exibi-la no chat ou logs. Em outra instalação, gere pelo menos 32 caracteres aleatórios.
3. Execute `POST /api/v1/auth/setup`, preenchendo o cabeçalho `X-Ayla-Setup-Key` e o corpo com seu login e sua senha.

```json
{
  "username": "pedro",
  "password": "escolha-sua-propria-senha-longa"
}
```

O exemplo não é uma senha padrão; escolha sua própria senha. O login normaliza espaços externos, Unicode e maiúsculas/minúsculas; aceita de 3 a 120 caracteres (`a-z`, números, ponto, hífen, sublinhado e símbolos comuns de e-mail). A senha é exata, não é normalizada: mínimo 12 caracteres e máximo 72 bytes UTF-8, conforme o limite do BCrypt. Não envie sua senha ao chat.

O setup só funciona uma vez e somente com a chave local correta. Repeti-lo após existir o proprietário retorna 409. A API não cria senha padrão e não oferece cadastro público livre.

## Entrar e consumir dados

`POST /api/v1/auth/login` recebe `username` e `password` e retorna:

```json
{
  "accessToken": "...",
  "refreshToken": "...",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "accessExpiresAt": "...",
  "refreshExpiresAt": "...",
  "user": {"id": "local-owner", "username": "pedro", "role": "OWNER"}
}
```

No Swagger, clique em **Authorize** e cole somente `accessToken`. No frontend, use:

```js
const loginResponse = await fetch(`${apiUrl}/api/v1/auth/login`, {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ username, password })
});
if (!loginResponse.ok) throw new Error("Não foi possível entrar");
let session = await loginResponse.json();

const result = await fetch(`${apiUrl}/api/v1/finance/summary`, {
  headers: { Authorization: `Bearer ${session.accessToken}` }
});
```

Os tokens são opacos e aleatórios, não JWTs para decodificar no frontend. O servidor valida sessão, usuário habilitado e expiração no PostgreSQL a cada requisição. Access token dura até quinze minutos; o prazo total de uma sessão com refresh é de trinta dias. Nunca ultrapassa a expiração absoluta da sessão.

## Renovação e saída

| Método | Endpoint | Uso |
| --- | --- | --- |
| POST | `/api/v1/auth/setup` | Primeiro cadastro, com a chave local |
| POST | `/api/v1/auth/login` | Obter tokens com login/senha |
| POST | `/api/v1/auth/refresh` | Renovar com `{"refreshToken":"..."}` |
| GET | `/api/v1/auth/me` | Identidade atual, com access token |
| POST | `/api/v1/auth/logout` | Revogar a sessão atual |
| POST | `/api/v1/auth/logout-all` | Revogar todas as sessões do usuário |
| POST | `/api/v1/auth/change-password` | `currentPassword` e `newPassword`; revoga todas as sessões |
| POST | `/api/v1/auth/users` | Criar outro usuário; somente proprietário |
| GET | `/api/v1/auth/users` | Listar e buscar todas as contas; somente proprietário |
| POST | `/api/v1/auth/users/{id}/reset-password` | Redefinir senha e revogar sessões da conta escolhida; somente proprietário |

Ao renovar, substitua os dois tokens pelos novos. O refresh token anterior é consumido e o access token anterior deixa de ser válido. Reutilizar um refresh consumido revoga toda aquela sessão; o frontend deve centralizar a renovação para evitar chamadas concorrentes usando o mesmo refresh. Sessão expirada/revogada exige novo login. Trocar senha também exige entrar novamente.

Logout da API não desconecta o WhatsApp: essa é uma integração separada, controlada pelos endpoints próprios. Respostas com tokens usam `Cache-Control: no-store`. O servidor não autentica usando cookies: o frontend deve enviar explicitamente o Bearer e guardar seus tokens de forma protegida, sem URLs ou logs. Fora de localhost, publique por HTTPS.

## Esqueci a senha

Sem estar logado, envie `POST /api/v1/auth/forgot-password`:

```json
{"username":"pedro"}
```

A API responde 202 com uma mensagem genérica. Havendo uma conta habilitada, um número de recuperação vinculado e o mesmo WhatsApp conectado, uma senha aleatória de 24 caracteres é enviada em segundo plano para a conversa com o próprio número. A solicitação não aceita outro destinatário e não devolve a senha pela API.

Entre pelo endpoint de login com essa senha em até quinze minutos. Até esse primeiro uso, sua senha anterior continua funcionando. O primeiro login com a nova senha substitui a anterior e encerra todas as sessões antigas. Depois, você pode escolher outra senha com `change-password`. Uma falha de envio não altera sua senha atual.

Há intervalo de cinco minutos por conta e limite de cinco solicitações por hora por endereço remoto. Uma nova solicitação válida substitui a senha pendente anterior. A resposta genérica também é usada para contas inexistentes, limites atingidos e WhatsApp indisponível.

O primeiro cadastro tenta vincular automaticamente o WhatsApp conectado. Para configurar o vínculo depois ou confirmar uma troca de número, use `POST /api/v1/auth/recovery-channel`, autenticado, com `{"currentPassword":"sua-senha-atual"}`. O servidor obtém o número da conexão, retorna apenas seu final mascarado e exige a senha atual. Reconectar outro número não muda automaticamente o destinatário de recuperação. Contas sem instância WhatsApp própria vinculada não têm recuperação por esse canal.

A senha pendente fica no banco somente como hash BCrypt. A mensagem de recuperação não passa pela IA; seu conteúdo é excluído do contexto e das citações encaminhadas à Ayla. Não há senha em texto aberto no histórico de respostas salvo pelo Node. A entrega usa uma fila em memória: se o processo encerrar antes do envio, solicite novamente após o intervalo; a senha atual permanece válida.

## Usuários e permissões

- O proprietário tem papel `OWNER`; administra usuários e configurações globais. Cada conta configura seu próprio perfil Hermes, voz e conexão WhatsApp pelos endpoints pessoais; a identidade autenticada determina a conta utilizada.
- Usuários adicionais têm `USER`, ID próprio e dados isolados. Não acessam configuração global nem os dados do proprietário. O chat de um novo usuário exige um perfil Hermes isolado, conforme [HERMES.md](HERMES.md); não há fallback para o perfil pessoal.
- Dados do usuário são filtrados pelo ID autenticado. O login pode ser um nome ou e-mail; o ID usado no banco permanece estável.
- Sem token, token expirado ou token inválido: 401. Sem permissão: 403. Registro de outro usuário: 404. Requisição inválida: 400.

### Inativar e reativar

Em Configurações → Usuários, o superadministrador pode inativar, reativar ou excluir uma conta. `PATCH /api/v1/auth/users/{id}/status` recebe `{"enabled": false}` para inativar e `true` para reativar. A conta principal não pode ser inativada nem excluída.

A inativação preserva os dados, revoga as sessões e senhas de recuperação pendentes e solicita a desconexão do WhatsApp. Mesmo se a desconexão falhar, a ponte Java e o processamento Node rejeitam a conta inativa. Ferramentas internas do Hermes também exigem uma sessão de conversa pertencente a uma conta ativa. A reativação permite novo login; não restaura tokens antigos nem reconecta automaticamente o WhatsApp.

Senhas ficam no PostgreSQL como hashes BCrypt com custo 12 e salt. Access e refresh tokens têm 256 bits aleatórios e somente seus hashes SHA-256 são salvos. Há revogação imediata, rotação de refresh e limpeza periódica dos registros expirados. DTOs ocultam senhas/tokens em `toString` e senhas não são serializadas em respostas.

Três falhas consecutivas de senha bloqueiam a conta por 30 minutos. Depois que o prazo acaba, mais três falhas bloqueiam por uma hora; novos blocos de três mantêm uma hora como limite. Requisições durante o bloqueio são recusadas sem aumentar os contadores nem estender o prazo. Login bem-sucedido, redefinição pelo superadministrador e troca de senha zeram tentativas, progressão e bloqueio. Os campos login_failed_attempts, login_lock_level, login_locked_until e last_login_failure_at ficam em auth_accounts (V37), sob bloqueio transacional da linha, e resistem a reinícios e múltiplas réplicas. O login bloqueado retorna 429 com o tempo restante. A proteção adicional por endereço remoto permanece para credenciais inválidas; não impede credenciais corretas após uma redefinição que tenha liberado a conta. Não se confia em X-Forwarded-For arbitrário.

## Integrações internas e CORS

Hermes continua usando `AYLA_FINANCE_BRIDGE_KEY`, somente em `/internal/hermes/**`, mais a sessão nativa já vinculada ao usuário. WhatsApp continua usando `AYLA_WHATSAPP_API_KEY`, somente em `POST /api/v1/chat` com `X-Ayla-WhatsApp-Chat` e validação do cadastro pelo Java. Essas chaves não permitem consultar dados pelas rotas públicas, criar usuários ou administrar a conexão. Access tokens de usuários também não abrem as pontes internas.

Essas credenciais ficam nos servidores, não vão para o frontend e não precisam ser usadas no Swagger. A restrição do WhatsApp ao próprio número continua ativa.

A Ayla não precisa entrar como usuário nem receber seu access token para usar as ferramentas de banco. As integrações continuam usando as credenciais internas automaticamente; os endpoints pessoais usados pelo frontend exigem login.

Swagger/schema e o estado básico `/actuator/health` permanecem públicos; não retornam os registros pessoais. CORS mantém as origens explícitas em `AYLA_FRONTEND_ORIGINS`, incluindo `Authorization` e `X-Ayla-Setup-Key`. CORS não substitui autenticação.

Referências de implementação: [Spring Security e armazenamento de senhas](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html), [autorização HTTP](https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html) e [OWASP: sessões e tokens](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html).

Na recuperação por WhatsApp, apenas solicitar a senha não desbloqueia a conta. Após confirmar o envio da nova senha e verificar que ela ainda corresponde à recuperação pendente, os contadores são zerados. Se a entrega falhar, o bloqueio fica preservado. Uma senha de recuperação válida também pode ativar a recuperação e liberar a conta no login, inclusive quando houve falha apenas na confirmação da entrega. Credenciais antigas não contornam um bloqueio ativo. A nova senha continua sendo ativada por uso em até 15 minutos, revogando as sessões anteriores, conforme o fluxo de recuperação existente.
