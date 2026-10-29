# Financeiro da Ayla

Os lançamentos pertencem ao usuário e ficam no PostgreSQL da API Java. Todas as conversas desse usuário consultam o mesmo financeiro. Não há uma tabela por mês: `occurred_at` permite filtrar qualquer dia/mês e gerar séries para gráficos. O Hermes interpreta a conversa e chama ferramentas; os valores e cálculos vêm do Java, sem pesquisa na internet ou memória como fonte do saldo.

## Usar na conversa

Envie mensagens para `POST /api/v1/chat`, como já faz hoje:

- “Recebi R$ 3.000 de salário agora.”
- “Gastei R$ 35,90 no almoço agora.”
- “Quanto eu tenho hoje?”
- “Quanto gastei em 21/10/2026?”
- “Mostre meus gastos por categoria neste mês.”
- “Corrija o almoço de hoje para R$ 32.”
- “Apague o almoço que registrei por engano.”

A Ayla deve registrar somente fatos realizados ou pedidos de registro. Perguntas, exemplos e simulações não geram lançamentos. Se valor/tipo/descrição/data forem ambíguos ou houver vários candidatos a uma correção, ela pede esclarecimento. Pode sugerir categoria pelo contexto. Sem data/hora, usa o instante atual. Para uma data passada sem horário informado, pede o horário em vez de inventá-lo. Só confirma sucesso após a ferramenta retornar.

Toda mensagem passa pela IA do Hermes. Ela interpreta o relato e as respostas seguintes, preservando o pedido pendente. Valor, unidade explícita (reais ou centavos) e descrição são obrigatórios: “gastei 50” exige esclarecimento; “comprei uma bala” completa a descrição; “centavos” completa a unidade. A IA pergunta somente o que falta e aguarda uma nova mensagem do usuário. Categoria pode ser inferida; descrição e unidade não podem ser inventadas. Pedidos concluídos/cancelados não devem ser retomados.

O agente usa `finance_record` com argumentos diretos: tipo (INCOME/EXPENSE), valor decimal textual, unidade (REAIS/CENTAVOS) e descrição; categoria e data/hora são opcionais. Exemplo: `{ "type": "EXPENSE", "amount": "12", "unit": "CENTAVOS", "description": "Bala" }`. O Java converte exatamente para 12 centavos e salva no PostgreSQL. Se faltar dado, a IA pergunta ao usuário; o backend também rejeita campos obrigatórios ausentes. Não há evidências, citações ou identificadores de turno nos argumentos do modelo. A memória da conversa serve para completar o pedido, nunca para consultar saldo/gastos.

O saldo começa em zero: receitas menos despesas registradas, até o instante consultado. Não é saldo bancário sincronizado. Para começar com dinheiro já existente, informe explicitamente uma receita de ajuste de saldo inicial e o valor. Evite registrar novamente receitas históricas já incorporadas nesse ajuste.

## CRUD e gráficos

Swagger: http://localhost:8080/swagger-ui.html — seção **Financeiro**.

| Método | Caminho | Uso |
| --- | --- | --- |
| POST | `/api/v1/finance/entries` | Registrar receita/despesa |
| GET | `/api/v1/finance/entries` | Listar com filtros e paginação |
| GET | `/api/v1/finance/entries/{id}` | Consultar um registro |
| PUT | `/api/v1/finance/entries/{id}` | Substituir usando versão atual |
| DELETE | `/api/v1/finance/entries/{id}?version=0` | Excluir usando versão atual |
| GET | `/api/v1/finance/summary` | Saldo, totais e séries |

Exemplo de cadastro:

```json
{
  "type": "EXPENSE",
  "amountCents": 3590,
  "description": "Almoço",
  "category": "Alimentação",
  "occurredAt": "2026-10-22T12:30:00-03:00"
}
```

`INCOME` é receita e `EXPENSE` despesa. Todos os valores monetários da API são inteiros em centavos: 3590 representa R$ 35,90; 15 representa R$ 0,15; 1500 representa R$ 15,00. O Java usa long/Long e o PostgreSQL BIGINT. Não há float/double nos cálculos. amountCents deve ser um número inteiro JSON positivo: frações, strings e o campo antigo amount são rejeitados, sem truncar ou arredondar. Limite por lançamento e por total: 9.007.199.254.740.991 centavos, dentro do intervalo inteiro exato do frontend JavaScript. Totais fora desse intervalo retornam 422, sem overflow. `occurredAt` opcional; `createdAt`, `updatedAt` e `version` são gerados no servidor. Horários são armazenados como instantes; consultas por dia e respostas de ocorrência usam `America/Sao_Paulo`.

Envie `Idempotency-Key: identificador-unico-da-operacao` ao cadastrar pelo frontend. Reutilizar chave e conteúdo retorna o mesmo registro; outro conteúdo com essa chave retorna 409. A proteção é por usuário. Depois de excluir um registro, repetir a chave antiga retorna 404 e não recria o registro. Um cadastro novo intencional deve ter uma chave nova.

PUT recebe `{"version": 0, "entry": { ...todos os campos do cadastro... }}`. Envie a versão retornada pela última consulta. A versão aumenta a cada edição; edição/exclusão com versão antiga retorna 409. Preserve `occurredAt` se não pretende mudar a data.

Filtros de listagem: `from`, `to` (YYYY-MM-DD, inclusivos), `type`, `category` (igualdade sem distinguir maiúsculas), `page` (inicia em 0) e `size` (1–100, padrão 50). Retorna `items`, `total`, `page`, `size`. Inclui registros futuros; estes não entram no saldo atual.

Exemplos para gráficos:

```text
GET /api/v1/finance/summary?from=2026-10-01&to=2026-10-31&groupBy=DAY
GET /api/v1/finance/summary?from=2026-01-01&to=2026-12-31&groupBy=MONTH
GET /api/v1/finance/summary?from=2026-10-01&to=2026-10-31&groupBy=CATEGORY
GET /api/v1/finance/summary?from=2026-10-21&to=2026-10-21
```

A resposta contém:

- `balanceCents`: saldo acumulado de **todo o histórico** até `asOf`, independentemente de `from/to`.
- `period.incomeCents`, `period.expenseCents`, `period.netCents`: totais apenas do período solicitado, limitados a `asOf`.
- `series`: itens com `label`, `incomeCents`, `expenseCents`, `netCents`, agrupados conforme `groupBy`.
- `currency`, `timezone`, `from`, `to`, `asOf`: contexto do cálculo.

`asOf` padrão é agora e não aceita futuro. Pode consultar saldo histórico com um instante ISO-8601, por exemplo `2026-10-21T23:59:59.999999Z`; envie o instante UTC correspondente ao horário local desejado. `from/to` padrão: início do mês de `asOf` até o dia de `asOf`. Intervalo máximo de 10 anos. Séries omitem dias/meses sem movimento; frontend pode preencher lacunas com zero. Categoria é agrupada em minúsculas. O retorno vazio significa ausência de registros, não ausência de dinheiro real.

## Integração Hermes → Java

Fluxo: `POST /api/v1/chat` → registrar a mensagem real no Java → Hermes interpreta → pergunta o que falta OU chama `finance_record` → Java valida e salva → Hermes confirma o resultado. Não há planejador auxiliar ou segunda IA financeira: é o agente principal escolhendo suas ferramentas nativas.

A tabela `finance_agent_turns` (V8) registra a mensagem recebida e permite ao Java gerar internamente a chave de idempotência do cadastro. Uma repetição da mesma chamada no mesmo turno retorna o mesmo registro; conteúdo diferente nesse turno retorna conflito. A IA não informa esses identificadores. A conversa e seus esclarecimentos ficam no histórico nativo do Hermes. As tabelas V7 são históricas e não controlam o novo fluxo. Nenhuma migration já aplicada foi alterada.

Para consultar gastos/saldo, a IA deve chamar `finance_list`, `finance_get` ou `finance_summary` neste turno. Os resultados vêm diretamente de `financial_entries`, incluindo lançamentos feitos pelo CRUD fora da conversa. A memória/histórico não é fonte de totais. Para editar/excluir, consulta primeiro os registros atuais e chama `finance_update`/`finance_delete` com ID e versão; na edição envia só os campos alterados e o Java preserva os demais. Se houver vários candidatos, pede esclarecimento. O CRUD público continua disponível.

O planejador financeiro auxiliar em Python foi removido: a interpretação é feita pelo agente principal. O reasoning fica desativado e agent.max_turns=4 limita as tentativas. O modelo gratuito e a pesquisa nativa continuam disponíveis. A IA interpreta a conversa; os campos obrigatórios e a conversão exata são validados no Java.

O plugin envia o ID de sessão fornecido pelo despachante nativo do Hermes. Esse campo não pertence aos argumentos da IA. O Java descobre o usuário pelo vínculo já persistido em `chat_conversations`. Não aceita userId escolhido pelo modelo. Sessão desconhecida retorna 403: uma conversa aberta diretamente no painel Hermes não tem financeiro até ser vinculada/criada pela API Java. Use `/api/v1/chat` para a conversa integrada.

Configuração em `.env`:

```properties
AYLA_FINANCE_BRIDGE_KEY=chave-aleatoria-exclusiva-com-pelo-menos-32-caracteres
AYLA_JAVA_BASE_URL=http://host.docker.internal:8080
```

A mesma chave é lida pelo Java e pelo Hermes no Compose. O endpoint `/internal/hermes/finance/{operation}` exige Bearer dessa chave e sessão registrada; fica fora do Swagger público. Chave vazia/curta desabilita a ponte. O plugin não aceita URLs/credenciais/usuários nos argumentos das ferramentas. Os logs do plugin registram operação/status, sem valores, descrição ou chave.

O CRUD exige access token e usa `ChatIdentity`, igual ao chat: o ID vem do Principal autenticado, nunca de userId no corpo. O modo pessoal anônimo foi removido. O primeiro login preserva o proprietário e seus registros existentes. Veja [AUTENTICACAO.md](AUTENTICACAO.md). Para vários usuários no Hermes, mantenha perfis isolados conforme HERMES.md. O banco financeiro filtra todas as operações por proprietário.

## Aplicar e verificar

1. Inicie/reinicie o Java para o Flyway aplicar `V6__store_financial_amounts_in_cents.sql` e as migrações V7/V8. Migrations antigas não foram alteradas. V6 converte os antigos valores em reais para centavos multiplicando exatamente por 100; não há arredondamento. IDs, versões e datas são preservados, assim como a compatibilidade das chaves de idempotência antigas. Se houver valor legado fora do limite novo, a migração aborta transacionalmente sem converter parcialmente.
2. Execute `docker compose up -d hermes` após configurar a chave para carregar o plugin.
3. Use o chat Java para testar um lançamento real e consulte `/api/v1/finance/summary`.

Os testes automatizados usam PostgreSQL separado pelo Testcontainers; não gravam lançamentos fictícios no banco pessoal. Consulte TESTES.md para os comandos.

## Exibição e cálculos

Todos os campos terminados em Cents, inclusive saldo e séries, são centavos inteiros. O frontend deve somar/subtrair centavos e formatar somente na apresentação. O plugin Hermes acrescenta display aos resultados das ferramentas, com textos de reais formatados por divisão e resto inteiros, sem float, inclusive valores negativos. display é metadado das ferramentas; a API pública retorna os campos inteiros.

Exemplo de formatação exata no frontend, sem transformar o dinheiro em decimal de ponto flutuante:

```javascript
function formatBRL(amountCents) {
  const value = BigInt(amountCents);
  const sign = value < 0n ? '-' : '';
  const absolute = value < 0n ? -value : value;
  return `${sign}R$ ${absolute / 100n},${(absolute % 100n).toString().padStart(2, '0')}`;
}
```

Essa mudança altera o contrato do CRUD e dos gráficos: atualize clientes para amountCents, balanceCents, incomeCents, expenseCents e netCents. Campos antigos não são aliases de reais ou centavos; isso evita interpretar um valor com a unidade errada.

As respostas das ferramentas para o modelo apresentam uma única representação do dinheiro, já formatada em reais. Os dados brutos do CRUD público continuam em centavos inteiros. Para totais use finance_summary; não some o histórico nem confunda saldo com despesas. A resposta ao usuário deve ser breve e natural, sem nomes de ferramentas, erros técnicos, IDs ou regras internas.

Cadastro, edição e exclusão retornam o resultado da operação e um summary atualizado do PostgreSQL na mesma transação. A IA deve usar esses totais quando solicitados, sem recalcular saldos a partir da conversa. A confirmação de uma alteração deve ser uma frase curta, sem regras internas nem ofertas de outras operações.
## Contexto de conversas longas

O plugin registra o mecanismo nativo `ayla-conversation` (ContextEngine.select_context). Ele seleciona o contexto somente para a requisição ao modelo: preserva as instruções, as seis mensagens recentes do usuário anteriores ao turno, a última pergunta de esclarecimento e todas as chamadas/resultados do turno atual. Respostas e ferramentas de turnos antigos não são reenviadas como se fossem dados atuais. Avisos sintéticos antigos do executor sobre limite de ferramentas também ficam fora do contexto. O histórico completo continua salvo no Hermes e a sessão/conversationId não são trocados. Preferências duradouras continuam usando a memória nativa. Pedidos que dependam de detalhes além dessa janela podem exigir esclarecimento.

Essa seleção não interpreta nem registra gastos e não chama outra IA. A IA continua decidindo como esclarecer e usar as seis ferramentas; os dados financeiros vêm dos endpoints Java/PostgreSQL. Configuração: `context.engine=ayla-conversation` em bootstrap.py. O teste real na sessão que repetia R$ 65,00 chamou a consulta atual e obteve R$ 95,00 em 22/10/2026, exatamente o total do endpoint, sem alterar lançamentos.
Registro sem confirmação extra: o relato de uma receita/despesa autoriza o cadastro. Assim que houver valor, unidade e item/serviço ou origem da receita, a IA deve chamar finance_record no mesmo turno. Não pede “posso registrar?”, loja, finalidade, marca ou categoria. “Comprei um cartão de 20 centavos” basta: descrição Cartão, valor 20, unidade CENTAVOS. Se houve correção do pedido, vale a informação mais recente. Só pergunta quando faltar um dado obrigatório ou houver ambiguidade real.
Confiabilidade do cadastro: o modo automático prioriza stepfun/step-3.7-flash:free somente quando o catálogo autenticado confirma gratuidade e disponibilidade; a escolha manual é preservada. Se o modelo afirmar que cadastrou sem haver recibo no banco para o turno atual, a API solicita uma única correção interna usando a mesma chave de idempotência; não pede confirmação ao usuário e não retorna sucesso se a gravação continuar ausente. A unidade é obrigatória nos dados estruturados interpretados pela IA. O Java não exige que a palavra reais/centavos se repita no texto do último turno: confirmações como isso mesmo podem completar o pedido pelo contexto. Descrição composta só de dinheiro é rejeitada.
Confirmações na conversa: a IA pode interpretar “isso mesmo” como resposta à pergunta de unidade do turno anterior. A validação do Java aceita a unidade estruturada (REAIS/CENTAVOS) e valida o valor exato, descrição e tipo; não tenta reinterpretar a frase do usuário por regex. Isso evita bloquear um cadastro completo apenas porque a última mensagem não repetiu a moeda.
