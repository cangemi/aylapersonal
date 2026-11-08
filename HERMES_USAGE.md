# Monitoramento Hermes / Nous

Implementação na área **Conexões → Hermes** e nos avisos do chat. Não chama modelos para medir consumo e não usa APIs pagas.

## Dados e estados

- O observador instalado no Hermes usa os hooks públicos `pre_api_request`, `post_api_request` e `api_request_error`. O hook `transform_api_error_classification` apenas lê os detalhes estruturados da exceção e retorna `None`, preservando a classificação, os retries e o fallback nativos. Isso permite observar um erro de streaming ou uma espera interna antes de terminar a chamada HTTP de chat.
- HTTP 429, `reason=rate_limited`, `retry_after`, `retry_after_seconds` e `Retry-After` são normalizados. O corpo e o cabeçalho podem informar segundos; o cabeçalho também aceita uma data HTTP. O Java tem um caminho adicional para HTTP 429 e falhas estruturadas retornadas no HTTP 200.
- Um 429 do gateway sem identificação explícita do provedor aparece como **aguardando provedor**, sem afirmar que a cota Nous acabou. Um 429 recebido diretamente do provedor nos hooks é registrado como **modelo temporariamente limitado**.
- `availableAt` é uma previsão calculada a partir do prazo informado pelo provedor. `retryAttemptAt` fica nulo quando o Hermes não expõe o próximo agendamento. O prazo do provedor não é apresentado como agendamento interno.
- Ao terminar o contador, o estado passa a **aguardando confirmação**, nunca automaticamente a disponível. Somente um sucesso observado confirma a disponibilidade daquele modelo.
- A quota vem de `hermes_cli.nous_account.get_nous_portal_account_info(force_fresh=True)` e do normalizador nativo `agent.account_usage.build_nous_credits_snapshot`. A consulta usa a API oficial `https://portal.nousresearch.com/api/oauth/account`, com o OAuth do próprio perfil. Não há scraping autenticado, exportação de tokens ou alteração direta das credenciais.
- A consulta oficial roda a cada 60 segundos nos processos Hermes com o plugin carregado. O backend persiste o último resultado e apresenta erro, ausência de quota ou desatualização após 180 segundos sem atualização. Não mostra uma barra para dados desconhecidos ou desatualizados.
- As porcentagens vêm exclusivamente de cotas verificáveis: percentual normalizado pelo Hermes ou razão entre uso e teto oficial conhecidos. Contagem histórica de tokens nunca gera percentual de cota. As janelas fornecidas pela API são exibidas individualmente, incluindo conta, modelo ou família de modelos.
- `ai_usage_events` continua sendo o registro existente de chamadas e tokens. A tela mostra as últimas 24 horas do próprio usuário, com chamadas sem tokens informados separadas e indicação expressa de que esse histórico não é a cota Nous.
- O frontend consulta o backend a cada 30 segundos enquanto o painel estiver ativo. O relógio regressivo é local, atualizado a cada segundo, usando UTC do servidor, tempo monotônico e estimativa de latência descontando o processamento no backend. As datas são formatadas no fuso do navegador.

Fontes verificadas: [hooks oficiais](https://hermes-agent.nousresearch.com/docs/user-guide/features/hooks), [consulta nativa da conta Nous](https://github.com/NousResearch/hermes-agent/blob/main/hermes_cli/nous_account.py), [normalização oficial das cotas](https://github.com/NousResearch/hermes-agent/blob/main/agent/account_usage.py).

## Conta, perfil e isolamento

A chave de disponibilidade é `(provider, credential_scope, model)`. `credential_scope` é um HMAC usando a chave interna da integração e a identidade OAuth estável, incluindo organização quando informada. Não contém o token, e-mail ou identificador de conta legível. O fingerprint não é devolvido ao navegador.

Perfis diferentes com a mesma identidade Nous compartilham a quota e o limite do mesmo modelo. Credenciais de contas diferentes ficam independentes. Apenas quota/disponibilidade são compartilhadas: histórico de mensagens, contadores de uso locais e a lista de modelos observados pelo outro usuário não são expostos. O endpoint de telemetria exige a chave interna e um perfil pertencente exclusivamente a um usuário ativo. Eventos atrasados não substituem o vínculo com uma credencial mais recente; eventos repetidos usam um identificador idempotente.

Quando não há uma identidade OAuth reconhecível, usa-se o HMAC da credencial; se nenhuma credencial estiver disponível, o escopo fica restrito ao perfil. Nesse caso não é possível provar que tokens distintos pertencem à mesma conta. Nenhum agrupamento é presumido. A rotação da chave interna também altera os fingerprints.

## Conversas e retry

### Sessão pausada após reinício

O Hermes pode manter uma execução como `unknown` após perder o processo que a
executava. `RuntimeStoreError: unknown_execution` pausa a fila daquela sessão;
novas chamadas podem falhar em milissegundos, sem chegar à inferência, e ainda
deixar entradas admitidas como `queued`. Reiniciar novamente não resolve essa
pendência. O histórico deve ser preservado.

A recuperação precisa inspecionar o snapshot nativo `session.resume`, conferir
as operações possivelmente realizadas, cancelar as entradas `queued` que já
foram apresentadas como falha pela Ayla via `prompt.cancel` e só então reconhecer
a execução perdida com `prompt.resolve_unknown`, usando o admission_id e a
execution_generation observados. Confira se chegaram novas entradas antes de
aplicar. Resolver primeiro a execução desconhecida pode liberar automaticamente
pedidos financeiros antigos. Não reenvie esses pedidos durante a recuperação nem
edite o SQLite diretamente. `hermes sessions discard` é o comando oficial para
reconhecer execuções perdidas, mas sozinho não cancela entradas pendentes atrás
delas.

O monitoramento conta chamadas HTTP ao Hermes, que podem falhar antes de chamar
um modelo. Uma falha não deve aparecer como "Respondeu com a IA". Ausência de
contadores de tokens é mostrada como não informado, nunca como consumo zero.

Antes de cada envio de chat, a API consulta a rota interna autenticada
`/api/plugins/ayla-finance/session-state`, que lê somente os metadados do SQLite
do próprio perfil. A leitura não abre uma execução nem altera o owner epoch.
Uma sessão com admission ou worker `unknown` resulta em `PAUSED`, mesmo com zero
execuções ativas. Nesse caso a API retorna uma mensagem específica e bloqueia o
POST de inferência antes da barreira de envio, sem acumular outra entrada no
Hermes nem registrar uma chamada à IA. Estado ausente ou não confirmado falha
sem enviar o pedido. A verificação não consome tokens de modelo.

A manutenção da fila também confere o estado nativo da sessão antes de liberar
pedidos incertos. A liberação é feita por usuário **e conversa**, impedindo que
uma sessão saudável libere por engano outras conversas pausadas da mesma conta.
O bootstrap copia `session_state.py` junto com o plugin do painel; atualize o
Hermes antes de carregar uma versão Java que dependa dessa rota.

O cliente Java deixa de repetir indiscriminadamente HTTP 429. Um limite conhecido com prazo futuro pode bloquear um novo pedido antes de enviá-lo à inferência. O pedido e a entrada do usuário permanecem salvos; **Tentar novamente** reutiliza o mesmo job, por ação explícita, somente quando não houve envio remoto e a conversa não tem outro pedido ativo ou execução incerta.

Se o Hermes já começou, o observador pode marcar o job como `NEEDS_REVIEW`, persistindo o aviso e encerrando a espera do frontend. O worker original pode continuar o retry nativo. A barreira contra replay permanece. Uma conclusão posterior substitui o aviso e pode ser recuperada pelo chat, sem reenviar o comando. Esse aviso não dispara push notification.

Nenhuma sessão, perfil, memória ou histórico remoto é apagado. No modo Automático,
a seleção de uma nova mensagem evita modelos observados como limitados e escolhe
entre os modelos gratuitos confirmados no catálogo do próprio perfil. Uma seleção
manual continua sendo respeitada. A lista de alternativas recebida no erro não
autoriza usar modelos pagos nem substitui a validação de preços. Não há reenvio
automático de um pedido que já começou a execução.

Entre os modelos gratuitos elegíveis, o Automático mantém aquele com o sucesso
observado mais recente. Uma previsão de liberação vencida não faz a conversa
voltar imediatamente para o modelo antes limitado. A política gerenciada do
Hermes usa `agent.api_max_retries=1` e `agent.auto_recovery_cycles=0`: a chamada
principal tem uma tentativa e não entra na escada extra de recuperação após
esgotamento. Isso evita as esperas longas entre tentativas do mesmo turno.
Descoberta e execução de ferramentas continuam habilitadas, e as novas mensagens
continuam passando pelas verificações de gratuidade e isolamento de perfil.

O observador reconhece tanto JSON quanto o dicionário formatado pelo SDK Python
em mensagens como `Error code: 429 - {'retry_after': 5812, ...}`. Somente os
metadados públicos do limite são publicados; o erro bruto e as credenciais não
são enviados. A API Java também extrai esses campos de erros encapsulados pelo
Hermes, preservando a previsão e as alternativas quando disponíveis.

Para carregar as alterações, reinicie a API Java e o Hermes após conferir os
pedidos em execução. Uma execução incerta não deve ser reenviada para aplicar
essa atualização.

## Endpoints

| Endpoint | Autenticação e finalidade |
|---|---|
| `GET /api/v1/me/hermes/usage` | Login Ayla; saúde, modelo, estados, quotas oficiais e uso local do próprio usuário. `Cache-Control: no-store`. |
| `POST /internal/hermes/provider-events` | Bearer interno, perfil exclusivo ativo; recebe apenas metadados normalizados. Não exige uma sessão de chat porque consultas oficiais também ocorrem fora de conversas. |
| `POST /api/v1/chat/jobs/{id}/retry` | Login Ayla, proprietário do job, pedido WEB comprovadamente não enviado e sem outro pedido ativo; reaproveita o mesmo job. |
| `GET /api/v1/chat/jobs/{id}` e `GET /api/v1/chat/jobs` | Acrescentam `availability` e `retryable`; preservam o status real do job. |
| Histórico de mensagens | Avisos persistidos incluem `availability`, `retryable` e `jobId`; respeitam o usuário e a conversa. |

## Migration e arquivos

`V56__provider_availability.sql` cria vínculos de credenciais, modelos observados por usuário, estados compartilhados por conta/modelo, snapshots de quota e recibos idempotentes. Acrescenta `chat_jobs.provider_notice_json`. Os estados ficam no PostgreSQL e sobrevivem à reinicialização. Recibos são limpos após sete dias; as cotas ficam explicitamente desatualizadas quando deixam de ser renovadas.

Arquivos novos de produção:

- `src/main/java/ayla/hermes/ProviderUsageData.java`
- `src/main/java/ayla/hermes/ModelAvailabilityService.java`
- `src/main/java/ayla/hermes/ProviderLimitParser.java`
- `src/main/java/ayla/hermes/ProviderUsageService.java`
- `src/main/java/ayla/hermes/ProviderUsageController.java`
- `src/main/resources/db/migration/V56__provider_availability.sql`
- `docker/hermes/plugins/ayla-provider-monitor/__init__.py` e `plugin.yaml`
- `frontend/src/components/ProviderUsagePanel.vue`
- `frontend/src/components/ProviderLimitNotice.vue`
- `frontend/src/lib/providerUsage.js`

Arquivos existentes integrados a esta alteração:

- `src/main/java/ayla/hermes/HermesClient.java` e `HermesExceptionHandler.java`
- `src/main/java/ayla/auth/AuthTokenFilter.java`
- `src/main/java/ayla/chat/controller/ChatController.java`
- `src/main/java/ayla/chat/queue/ChatQueueService.java` e `ChatQueueStore.java`
- `src/main/java/ayla/chat/session/ConversationHistory.java` e `ProactiveMessageService.java`
- `docker/hermes/bootstrap.py`, `docker/hermes/plugins/ayla-finance/__init__.py` e `compose.yaml`
- `frontend/src/components/HermesConnection.vue`, `frontend/src/pages/Chat.vue` e `frontend/src/lib/chatQueue.js`

O monitor é carregado pelo bundle gerenciado `ayla-finance`, já habilitado nos perfis existentes. O bootstrap copia os arquivos gerenciados e preserva as configurações desses perfis, sem exigir recriação ou novo login. As demais alterações de investimentos, notificações e voz já presentes no workspace não fazem parte desta entrega.

## Limitações e ativação

- A compatibilidade dos hooks e do helper de conta depende da versão Hermes da imagem fixada no Compose. Na ausência de suporte, o monitor registra a incompatibilidade e mantém consumo indisponível; o caminho HTTP continua detectando os 429 que chegam ao Java. Não existe neste ambiente confirmação contra uma conta Nous real.
- O Java continua usando a API síncrona existente de chat; os erros internos de streaming são observados pelos hooks. Não foi implementado um segundo cliente SSE.
- O horário exato do próximo retry interno não é exposto pelos hooks verificados e continua **não informado**. O monitor não modifica a política nativa.
- Se o processo do perfil não estiver executando, não há novas consultas oficiais daquele perfil. O cache passa a desatualizado.
- A fila de telemetria é limitada e a entrega tem tentativas curtas, sem bloquear a conversa. Eventos podem ser perdidos se o Java permanecer indisponível; o painel não presume liberação por falta de evento.
- Publicar exige atualizar a API e o frontend e recriar o serviço Hermes para montar o novo diretório e executar o bootstrap. O Flyway aplica V56 na inicialização da API. Esta sessão apenas alterou o código; não reiniciou os serviços em execução.

## Validação

Testes focados executados:

- Java: `ProviderAvailabilityTest` (5), `ProviderLimitHttpTest` (1) e `ConversationHistoryTest` (5): **11 passaram**. Incluem parsing estruturado, headers, prazo ausente, expiração pendente, quota desconhecida, uma única chamada HTTP no 429 e histórico persistido.
- Python: `test_provider_monitor.py`: **6 passaram**. Incluem erro durante resposta/retry, não alteração da classificação nativa, sanitização, modelos independentes, falha da API oficial e fingerprints compartilhados/independentes após renovação de token.
- Frontend: `providerUsage.test.js` e `chatQueue.test.js`: **6 passaram**. Incluem contador, relógio incorreto, tempo de processamento, ausência de porcentagem, estado terminal e retry explícito usando o mesmo job.
- Playwright: `provider-usage.spec.js`: **4 passaram**, nos tamanhos 1440px e 390px; quota real, nenhum HTTP por segundo, fim do prazo pendente, falta de quota e erro da consulta. O runner precisou ser interrompido no encerramento do servidor depois de reportar os quatro casos aprovados.
- `ProviderAvailabilityDatabaseTest`: **3 ignorados**, pois o Docker/PostgreSQL de teste não está acessível no sandbox. Os casos preparados validam a migration, compartilhamento entre contas, isolamento por modelo, reinicialização, expiração, troca de credenciais e deduplicação. A persistência e essas consultas SQL ainda precisam ser executadas com Docker disponível.
- A suíte legada `HermesIntegrationTest` foi executada: 26 casos, com 5 falhas e 12 erros. Sua fixture instancia `HermesSettingsService` sem configurar `HermesUserScope`, causando erros de isolamento antes da inferência; há também expectativas antigas sobre modelo automático e texto de links. Não foi removida a proteção de isolamento para fazer testes antigos passarem.
- Foi preparado em `ChatQueueDatabaseTest` um caso para retry usando o mesmo job, persistência da entrada/aviso e recusa de replay depois do envio remoto. Essa suíte com PostgreSQL não foi executada neste ambiente.
- Build do frontend aprovado. O compilador Java produz as classes, mas emite um erro de encerramento de ZIP/JAR por `AccessDeniedException` no cache Maven deste ambiente; com compilação separada (`maven.compiler.fork=true`) os testes focados concluíram com código de saída zero.

Arquivos de teste: `src/test/java/ayla/hermes/ProviderAvailabilityTest.java`, `ProviderLimitHttpTest.java`, `ProviderAvailabilityDatabaseTest.java`, `src/test/java/ayla/cluster/ChatQueueDatabaseTest.java` (fixture atualizada com o novo campo), `docker/hermes/tests/test_provider_monitor.py`, `frontend/src/lib/providerUsage.test.js`, `frontend/src/lib/chatQueue.test.js` e `frontend/tests/provider-usage.spec.js`.
