# Monitoramento da aplicação

Para métricas da VPS, réplicas Java com balanceamento, logs centralizados e traces no Grafana, consulte [OBSERVABILIDADE.md](OBSERVABILIDADE.md). O painel abaixo continua responsável pelo histórico de ações e erros por usuário.

O menu **Monitoramento**, em `/monitoramento`, é exclusivo do proprietário da instância. O papel existente `OWNER` representa o superadministrador; o backend exige esse papel e confirma que `userId` corresponde a `ayla.chat.owner-id`. Outros usuários não veem o menu e recebem bloqueio ao chamar os endpoints diretamente. A chave interna do WhatsApp não dá acesso à leitura do painel.

O Flyway V25 cria `application_events` no PostgreSQL. O registro começa na ativação: não importa logs históricos. São registrados horário, usuário identificado, origem, operação, rota parametrizada, status, duração e, nas falhas, classe da exceção, motivo resumido e arquivos/linhas do código da aplicação. Um UUID de referência também aparece em `X-Ayla-Request-Id` e no log seguro das falhas 5xx. Não são armazenados corpos de requisição/resposta, query strings, tokens, senhas, telefone, mensagens, conteúdo financeiro nem anexos. Por isso, o monitoramento identifica a operação, mas não reproduz a conversa ou o conteúdo preenchido.

Operações da API, ferramentas internas do Hermes e acesso/autenticação entram no histórico. As ferramentas atribuem o usuário após validar sua sessão. Falhas de processamento do WhatsApp anteriores à API (download de mídia, contexto e entrega) são enviadas de forma assíncrona com um código de etapa fixo para `/internal/monitoring/whatsapp`, autenticado apenas pela chave interna desse serviço; o Java associa ao proprietário configurado. O reporte não inclui texto nem o erro bruto e não bloqueia o processamento se a API estiver fora do ar. Se a API e o PostgreSQL estiverem indisponíveis, esses eventos podem não chegar ao painel; os logs locais continuam necessários para investigar essa indisponibilidade.

Consultas bem-sucedidas do próprio monitoramento, QR/status e verificações de saúde são excluídas para reduzir ruído. Falhas nesses endpoints continuam registradas. O histórico mede operações da API e eventos, não cliques ou visitantes. Métricas de duração consideram somente requisições medidas; eventos do Node sem medição aparecem como “Não medido”.

O painel oferece filtros por período (até 31 dias por consulta), usuário, origem, resultado e ação/erro; paginação; ações com mais falhas; detalhes técnicos e atualização automática opcional a cada 15 segundos, pausada quando a aba não está visível. Falha ao persistir um evento não muda a resposta da operação original. Não há exclusão automática do histórico nesta implementação.

Endpoints de leitura protegidos, disponíveis no Swagger:

- `GET /api/v1/monitoring`: totais e ações com mais erros.
- `GET /api/v1/monitoring/events`: histórico paginado, até 100 registros por página.
- `GET /api/v1/monitoring/events/{id}`: detalhes e localização do erro no código.
- `GET /api/v1/monitoring/users`: usuários para os filtros.

Filtros: `from`/`to` em ISO-8601 com fuso, `userId`, `source` (`WEB`, `WHATSAPP`, `HERMES`, `ANONYMOUS`), `outcome` (`ALL`, `SUCCESS`, `ERROR`, `CLIENT_ERROR`, `SERVER_ERROR`) e `q`. A listagem também aceita `page` e `size`.

Validação automatizada: teste com PostgreSQL temporário cobre bloqueio de usuários comuns, consulta de ações de outro usuário, erros 4xx/5xx, filtros, privacidade, localização do erro e escopo da chave interna do WhatsApp; teste de filtro garante tolerância a falha de armazenamento; teste Node verifica reporte sem conteúdo; Playwright verifica filtros, detalhes no celular e bloqueio de navegação para usuário comum. Os testes não enviam mensagens reais.
