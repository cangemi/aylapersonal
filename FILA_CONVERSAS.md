# Fila persistente de conversas

Os pedidos de chat passam por `chat_jobs` no PostgreSQL (Flyway V31). Cada conversa tem ordem FIFO; conversas diferentes podem executar em paralelo, respeitando o limite de chats por réplica. Não é necessário Redis ou RabbitMQ para esse fluxo.

## API

- `POST /api/v1/chat`: use `Idempotency-Key` estável por pedido e `Prefer: respond-async`. Retorna HTTP 202, `Location` e `{id,status,conversationId,message,position,result,userMessage}`. Sem `Prefer`, aguarda até 20 segundos e retorna a resposta antiga se ficar pronta; caso contrário, retorna o pedido 202.
- `GET /api/v1/chat/jobs/{id}`: consulta o mesmo pedido, sem enviar outra mensagem à IA.
- `GET /api/v1/chat/jobs?conversationId=...`: recupera pedidos/respostas ainda não recebidos, em ordem, até 50 por consulta.
- `POST /api/v1/chat/jobs/{id}/ack`: confirma o recebimento. Não executa a IA.

Estados: `QUEUED`, `RUNNING`, `COMPLETED`, `FAILED`, `NEEDS_REVIEW`, `CANCELLED`. A posição é relativa à própria conversa. As consultas verificam o usuário autenticado. No WhatsApp, a credencial interna continua exigindo `X-Ayla-WhatsApp-Chat`, com escopo limitado à conversa autorizada; a credencial não acessa outras áreas da API. Desativar usuário/conversa impede processamento e entrega subsequentes.

## Capacidade e recuperação

Cada réplica usa workers virtuais limitados pela mesma configuração de concorrência do chat. A seleção SQL usa `FOR UPDATE SKIP LOCKED` e não admite o segundo pedido de uma conversa enquanto o anterior estiver pendente. Os clientes assíncronos não mantêm conexões SQL/HTTP abertas durante a espera; fazem consultas curtas de status. Apenas o processamento ativo continua ocupando o lock/conexão de conversa já existente. No WhatsApp, depois dos dez segundos de agrupamento, cada lote é enviado e persistido sem aguardar a resposta do lote anterior; somente a entrega das respostas permanece serializada. A janela anterior ao agrupamento ainda pertence ao buffer local de recebimento.

Vagas ocupadas e indisponibilidade **antes** de enviar o agente mantêm o pedido na fila com recuo de até 60 segundos, sem limite de tentativas por falta de capacidade. O gateway Hermes da versão fixada devolve HTTP 429 antes de executar o agente; somente essa rejeição explícita de admissão pode repetir a mesma chamada HTTP. Timeout, 5xx e resposta incerta após despacho nunca repetem automaticamente uma inferência.

Os workers renovam leases a cada 15 segundos, com validade de dois minutos. Após interrupção, um pedido sem despacho volta à fila; um pedido já enviado passa a `NEEDS_REVIEW`, informando que é preciso conferir a operação. Sua conversa só avança quando `/health/detailed` do Hermes confirmar ausência de execuções ativas. Isso evita sobrepor turnos enquanto o agente anterior ainda pode estar alterando dados. Não há promessa de execução exatamente uma vez em serviços externos; a estratégia evita repetição automática de alterações incertas.

O site consulta status e recupera pedidos da conversa ao recarregar/voltar à tela. O WhatsApp consulta respostas pendentes a cada dez segundos quando conectado, inclusive após reiniciar o Node, sem reler mensagens antigas do WhatsApp. A confirmação de entrega é separada do processamento: falhar ao confirmar não executa novamente a IA. Uma interrupção exatamente entre enviar pelo WhatsApp e confirmar no banco ainda pode duplicar a entrega do texto/áudio após reinício; não repete a operação financeira.

## Dados e operação

O conteúdo e anexos necessários ficam no banco enquanto o pedido aguarda. Ao terminar, `request_json` é apagado. Resposta e mensagem original ficam para recuperação; pedidos terminais com recebimento confirmado são removidos após sete dias. Pedidos pendentes ou respostas sem confirmação não são descartados por falta de capacidade nem pela limpeza. Exclusão explícita de conta/conversa remove seus pedidos associados. O volume de dados exige acompanhar espaço disponível no PostgreSQL.

O Monitoramento registra a execução final como operação `JOB`, sem mensagens/anexos. Consultas de status bem-sucedidas não poluem o histórico de ações. Tokens continuam sendo registrados pelas respostas do Hermes e seguem a retenção própria de dois meses.

Ativação: aplique a V31 pela API Java antes de atualizar os clientes WhatsApp/frontend. Faça backup consistente do banco antes das migrações. Preserve volumes de PostgreSQL e WhatsApp. As imagens e o código do cliente devem ser implantados com essa versão da API.

## Agilidade da fila e das respostas

O agendador de 500 ms continua como recuperação. Pedidos novos acionam o despacho imediatamente após o commit, e a liberação de uma vaga também acorda a fila. Isso reduz a espera artificial entre atendimentos; não altera o FIFO por conversa nem o limite de concorrência.

HTTP 429 do gateway antes de qualquer inferência aceita devolve o pedido à fila, liberando vaga e conexão do banco para outras conversas. Contenção usa espera curta de 1–3 s; indisponibilidade de infraestrutura mantém o backoff progressivo. Após uma inferência aceita ou resultado incerto, não se reinicia o pedido inteiro nem se repete lançamento financeiro.

A verificação de existência de sessões Hermes usa metadados em memória por até 30 s, separados por perfil e sessão, com invalidação em exclusão/404. Não há cache de respostas, financeiro, estoque ou agenda. A autenticação das chamadas reais continua sendo validada. A voz usa trava local por perfil e mantém coordenação entre réplicas, permitindo perfis diferentes em paralelo e impedindo mistura de configurações.

Respostas simples recebem orientação de 1–3 frases, com detalhes completos quando necessários. Não há garantia de redução percentual: a geração no provedor gratuito, ferramentas, mídia e limites externos continuam influenciando o tempo. Não foram executados testes nem carga; os testes de proteção foram escritos e a API compilada.
