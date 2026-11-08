# Otimização de contexto — Ayla + Hermes

## Verificação posterior de execução (7 de novembro de 2026)

Os dois pedidos inspecionados retornaram confirmações de despesa/resgate sem
chamar ferramentas. A verificação anterior reconhecia apenas algumas conjugações
financeiras e não cobria "Registro de despesa concluído" ou "Resgate concluído".

A migração V59 adiciona comprovantes mínimos de alterações concluídas pelas
pontes internas. A confirmação é conferida no mesmo usuário, turno e domínio;
consultas, planos de agenda e alterações em outro domínio não comprovam a ação.
Atualização de preço no carrinho não comprova uma despesa. Um checkout com
lançamento financeiro real comprova ambas as alterações.

O verificador não chama um classificador de IA nem consulta comprovantes para
conversa comum. Uma confirmação sem comprovante admite somente a correção única
já existente; se continuar sem execução, a confirmação é bloqueada. A correção
deve executar apenas o que ainda falta ou perguntar dados obrigatórios ausentes,
sem repetir operações comprovadas. O mecanismo verifica afirmações reconhecidas
de conclusão; não é uma prova semântica de todos os detalhes de qualquer resposta.

Os testes usam respostas simuladas e PostgreSQL isolado. As consultas reais de
saldo e carteira também foram verificadas pela integração Hermes → Java, sem
gravar dados ou chamar modelos. Não houve novo consumo de inferência durante
essa verificação. As duas chamadas anteriores observadas tinham aproximadamente
22 mil tokens de entrada cada; respostas curtas não comprovam economia de tokens.
Não foi medida redução de tokens reais após esta correção.

Implementação incremental no código. Conforme pedido, **não foram executados testes, builds ou chamadas a modelos** nesta tarefa. Foram feitas leitura do código, consulta à documentação oficial, medição offline das fontes, formatação e revisão do diff. A economia real de tokens e o impacto na qualidade/latência ainda não foram medidos em execução.

## Diagnóstico e linha de base

O relatório fornecido pelo usuário apontava 46.402 bytes de system prompt, 49.406 bytes de schemas e 50 ferramentas. Esses valores são de uma execução anterior e não equivalem diretamente a tokens. O consumo relatado de 19,8 milhões de tokens de entrada, 799 requisições e 9,3 milhões de tokens de cache não foi tratado como uma medição comparável deste código nem como custo líquido.

Antes das alterações, foram registrados em `docs/ai-context-baseline.json`:

- `SOUL.md`: 38.985 bytes UTF-8, incluindo seções repetidas e regras operacionais também presentes no Java.
- Literais das instruções do Java: 34.786 bytes, sem dados dinâmicos ou o prompt interno do Hermes.
- Schemas personalizados em JSON compacto: 44.503 bytes; 49 ferramentas próprias mais a busca web. Agenda, financeiro e despensa eram os maiores grupos.

Principais problemas encontrados:

1. `tools.tool_search.enabled=off` enviava todos os schemas mesmo para cumprimentos.
2. Identidade, pesquisa, memória, financeiro, compras e agenda repetiam instruções em `SOUL.md`, Java e descrições de ferramentas.
3. Toda mensagem recebia até 40 eventos dos próximos 30 dias, independentemente do pedido.
4. A seleção de histórico preservava falas do usuário, mas descartava quase todas as respostas da Ayla e os resultados antigos de ferramentas; apenas a última pergunta curta era mantida. Isso dificultava interpretar confirmações e correções.
5. O plugin de pesquisa adicionava instruções repetidas a turnos comuns, embora não fizesse pesquisa nesses turnos.
6. A memória já usava busca textual por usuário, mas não tinha um teto global para o bloco retornado.
7. O painel registrava contadores da chamada HTTP ao Hermes, sem detalhar adequadamente tentativas internas, cache e composição local do contexto.

## Estratégias implementadas

### Ferramentas por demanda

Foi adotado o **Tool Search nativo**, sem novo roteador por palavras-chave nem chamada de LLM para classificação. O catálogo mantém as capacidades descobríveis; o modelo carrega schemas específicos com `tool_describe` ou pesquisa o catálogo quando necessário. Vários domínios podem ser utilizados no mesmo pedido, com validações e permissões existentes.

O modo é `on`, com catálogo `listing=auto`. A configuração não supõe que a janela anunciada pelo modelo seja suficiente para carregar todos os schemas. Descoberta hospedada de conectores foi desativada: os recursos atuais da Ayla são plugins locais, sem necessidade de consultar outros serviços ou criar contas. Nenhum modelo alternativo pago foi habilitado.

O bootstrap verifica a presença do módulo nativo antes de habilitar o recurso; sem ele, mantém schemas completos. `AYLA_TOOL_SEARCH_MODE=off` permite voltar ao carregamento direto. A verificação de presença do módulo não substitui uma validação da versão da imagem em execução.

O teto de iterações passa por padrão de 4 para 8, para comportar descoberta, consulta, alteração e resposta final. Isso **permite**, mas não exige mais chamadas. Ferramentas frias podem exigir uma ou duas rodadas adicionais; por isso menor contexto não garante menor latência em toda tarefa.

### Instruções e dados dinâmicos

- `SOUL.md` concentra identidade, linguagem, tom, honestidade e princípios de escopo.
- `AylaInstructions` concentra uma política operacional estável e compacta, incluindo proteção contra duplicação, regras financeiras, compras, investimentos, agenda, alarmes e memória.
- As regras de mídia e voz entram quando há mídia/voz. Referências do WhatsApp continuam condicionais e são tratadas como dados não confiáveis.
- Preferências personalizadas da conta são preservadas. Nome, escopo e dados dinâmicos ficam após o prefixo compartilhado; o horário atual aparece uma vez, ao fim das instruções estáveis.
- A agenda completa deixa de ser injetada em todos os turnos. O plano pendente da própria conversa é preservado; eventos atuais são consultados pelas ferramentas, mantendo conflitos, versões, Google Calendar e lembretes no backend.
- Foram compactadas 21 descrições de ferramentas sem remover parâmetros obrigatórios, operações, validações ou contratos de resposta.

As regras operacionais compactas continuam disponíveis globalmente por segurança. Não foi criado um seletor heurístico para ocultar políticas ou ferramentas em mensagens ambíguas. Os schemas completos são a principal parte carregada por demanda.

### Histórico e recuperação

A seleção é uma visão temporária da requisição; não altera nem apaga o histórico persistido. Conserva trocas recentes completas, com papéis e pares de chamadas/resultados de ferramentas. O turno atual, suas correções e resultados em andamento são preservados integralmente.

O padrão mantém o horizonte anterior de seis turnos e deriva o teto de 24.000 caracteres do limite anterior de seis falas de até 4.000 caracteres. Há um handoff extrativo de até 4.000 caracteres para trechos anteriores: são citações atribuídas, não um resumo inventado nem novas autorizações. Mídia antiga é substituída por um aviso explícito, mantendo o texto/análise registrada; a mídia do turno atual permanece intacta.

Quando a janela é fornecida pelo Hermes, usa-se adicionalmente seu estimador **aproximado** e limiar nativo para selecionar o histórico. Esse orçamento não é apresentado como tokenização exata nem como janela verificada diretamente no provedor. A ausência de janela permanece desconhecida; não se inventa capacidade por modelo. Sistema e turno atual podem excepcionalmente exceder o orçamento, pois não são cortados silenciosamente.

`conversation_history_search` recupera trechos da **própria conversa**, inclusive em conversas secundárias, com proprietário e conversa derivados da sessão autenticada. A consulta é textual no PostgreSQL e limitada a três turnos arquivados, sem API de embeddings. A busca existente entre conversas continua exclusiva da principal. Os arquivos e a interface de histórico do Hermes não foram apagados ou reescritos.

Não há chamada adicional de IA para resumir toda mensagem. Um resumo semântico persistente automático fica para uma etapa posterior, caso as medições mostrem benefício líquido.

### Memória e cache

A recuperação continua filtrada pelo usuário e pela validade dos fatos, com relevância textual, importância e recência. A leitura automática permanece exclusiva da conversa principal. O bloco tem teto configurável em caracteres; fatos inteiros que não couberem podem ser recuperados pelas ferramentas, sem apagar memórias. IDs e datas redundantes deixam de acompanhar todo fato injetado.

O prefixo estável e a ordem determinística favorecem reutilização de cache quando suportada. Isso não garante acerto nem elimina consumo contabilizado. A seleção de histórico pode mudar o prefixo em mudanças de turno/pressão. Não foi criado cache de resultados financeiros ou de agenda que pudesse ocultar uma atualização do banco.

A pesquisa explícita pelo marcador `AYLA_SEARCH_REQUIRED` mantém seu fluxo existente de planejamento e avaliação. Turnos comuns deixam de receber o prefill repetitivo. A correção limitada de confirmação financeira sem recibo e as proteções de idempotência da fila/banco permanecem; não foram substituídas por um cache de gravações.

## Observabilidade

A migration `V57__inference_context_metrics.sql` cria `provider_inference_events`. O observador usa a entrega interna autenticada já existente, sem transmitir prompts, argumentos, resultados de ferramentas, credenciais ou texto de erros.

São armazenados, quando informados: usuário/perfil, sessão, modelo, provedor, vínculo com job, origem, estado, tokens de entrada/saída/total, cache read/write, número de retry, contagem de ferramentas, duração e HTTP status. Também são medidos tamanhos locais de sistema, schemas, memória e mensagens/histórico em bytes UTF-8. Nem todo hook/versão disponibiliza todos os campos; ausência não vira zero.

O vínculo com o job é feito usando usuário, sessão e intervalo de execução. Eventos atrasados podem ficar sem correlação. Tentativas internas são apresentadas separadamente dos contadores da chamada HTTP para evitar somá-las duas vezes. Erros são identificados por estado e HTTP quando disponível; não se guarda a mensagem de erro do provedor.

O painel **Monitoramento → Uso da IA → Contexto, cache e tentativas internas do Hermes** mostra cache, tentativas, ferramentas, gráfico de componentes locais e observações por requisição. Seus endpoints continuam exclusivos do superadministrador.

Endpoints:

- `GET /api/v1/monitoring/ai-usage`: acrescenta `inference`, separado dos totais existentes.
- `GET /api/v1/monitoring/ai-usage/inference`: observações paginadas, com os mesmos filtros de período, usuário e origem.
- `POST /internal/hermes/provider-events`: aceita métricas numéricas permitidas e eventos `TOOL`.
- `POST /internal/hermes/conversation-context/history`: recuperação local à própria sessão, com chave interna e sessão ativa.

Não há tokens por componente, custo ou tempo até primeiro token disponíveis neste fluxo; não foram inventados. O campo empresarial permanece nulo porque este caminho usa uma sessão pessoal e ainda não fornece tenant empresarial autenticado. O estado compartilhado de limite Nous não compartilha histórico ou métricas privadas entre usuários. Chamadas auxiliares que não emitam os hooks suportados continuam fora desta cobertura.

## Medição antes/depois

Os arquivos de medição são reproduzíveis com `python scripts/measure_ai_context.py`. O script apenas importa definições locais de contratos e lê fontes; não inicia o Hermes, executa testes ou faz chamadas externas.

| Componente de fonte | Antes | Depois | Redução em bytes |
|---|---:|---:|---:|
| SOUL + literais de instruções Java, sem dados dinâmicos | 73.771 | 16.399 | 77,77% |
| Schemas personalizados completos em JSON compacto | 44.503 | 37.668 | 15,36% |

A segunda linha inclui a nova ferramenta de recuperação. Esses schemas não precisam todos entrar em cada requisição com Tool Search ativo. A economia efetiva da descoberta depende da versão e das ferramentas utilizadas e não foi medida localmente.

Os números não representam o prompt final no transporte, o tokenizer de um modelo Nous ou a redução total por conversa. **Redução real de tokens: não medida. Impacto na latência, qualidade e taxa de sucesso: não medido.** A meta de 30–50% continua sendo uma meta para comparação controlada após ativação.

## Configuração e ativação

- `AYLA_TOOL_SEARCH_MODE=on|off`
- `AYLA_AGENT_MAX_TURNS=8`
- `AYLA_HISTORY_RECENT_TURNS=6`
- `AYLA_HISTORY_MAX_CHARS=24000`
- `AYLA_HISTORY_HANDOFF_CHARS=4000`
- `AYLA_MEMORY_CONTEXT_MAX_CHARS=4000`

Os limites em caracteres são configuráveis e provisórios; não são cotas de tokens calibradas por modelo. Ajustes por tipo de tarefa/modelo devem usar as métricas reais, sem assumir capacidades iguais entre modelos.

Para ativar: atualizar primeiro a API para aplicar V57, atualizar o frontend e recriar o serviço Hermes para executar o bootstrap/configurador e carregar os plugins. **Nenhum serviço em execução foi reiniciado nesta tarefa.** As configurações próprias dos perfis preservam modelo, instruções, memória e credenciais. O configurador altera apenas as opções gerenciadas de ferramentas/iterações e mantém backup do config.

A persona dos perfis existentes só é substituída quando coincide com a persona gerenciada anterior do perfil padrão, com backup. Personas customizadas são preservadas e podem continuar extensas. Opções personalizadas de `defer` do Tool Search também são preservadas e podem impedir adiamento. Não houve alteração de OAuth, exclusão de sessões, memórias ou dados.

## Arquivos desta alteração

- `src/main/java/ayla/chat/service/AylaInstructions.java` e `ChatService.java`
- `src/main/java/ayla/chat/session/ConversationContext.java` e `ConversationContextBridge.java`
- `src/main/java/ayla/memory/PersonalMemoryService.java`
- `src/main/java/ayla/hermes/ProviderUsageData.java` e `ModelAvailabilityService.java`
- `src/main/java/ayla/monitoring/AiUsageService.java` e `MonitoringController.java`
- `src/main/resources/db/migration/V57__inference_context_metrics.sql`
- `src/main/resources/application.properties`
- `frontend/src/components/AiUsagePanel.vue`
- `docker/hermes/SOUL.md` e `bootstrap.py`
- `docker/hermes/plugins/ayla-finance/__init__.py`, `configure.py` e `plugin.yaml`
- `docker/hermes/plugins/ayla-research/__init__.py`
- `docker/hermes/plugins/ayla-provider-monitor/__init__.py`
- `.env.example`, `compose.yaml` e `compose.vps.yaml`
- `scripts/measure_ai_context.py`, `docs/ai-context-baseline.json`, `docs/ai-context-after.json` e este documento

## Limitações e próximos ajustes

Não foram executados testes, compilação ou validação contra o container/uma conta Nous. É necessário confirmar compatibilidade da imagem e comportamento de conversas/CRUD após ativação. A entrega de métricas é assíncrona e limitada; eventos podem ser perdidos durante indisponibilidade do Java, sem repetir inferências. Não é uma fatura do provedor.

Próximos passos recomendados: comparar tarefas equivalentes por modelo com tokens reais, retries, sucesso e duração; avaliar o custo adicional de descoberta fria; ajustar os limites e a paginação de resultados grandes; medir se um resumo semântico persistente se paga; adicionar contexto empresarial derivado da autenticação quando esse fluxo existir. A tokenização por componente exige um tokenizer correspondente ao modelo ou uma API de contagem confiável, não uma regra bytes/tokens.

Referências técnicas verificadas: [Tool Search](https://hermes-agent.nousresearch.com/docs/user-guide/features/tool-search), [contrato de Context Engine](https://hermes-agent.nousresearch.com/docs/developer-guide/context-engine-plugin), [hooks públicos](https://hermes-agent.nousresearch.com/docs/user-guide/features/hooks) e [compressão/cache](https://hermes-agent.nousresearch.com/docs/developer-guide/context-compression-and-caching).
