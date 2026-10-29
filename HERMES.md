# Ayla Java + Hermes

## Swagger / OpenAPI

Com a API Java em execução, abra http://localhost:8080/swagger-ui.html.
As operações estão agrupadas em Chat, Conversas e Hermes. Expanda uma operação,
clique em **Try it out**, escolha um exemplo e execute a requisição.
O chat tem exemplos de conversa normal, pesquisa obrigatória e conversa específica.
Os identificadores devem ser copiados das respostas da sua instalação.
Não é necessário informar a chave interna do Hermes no Swagger.

O contrato gerado está em http://localhost:8080/v3/api-docs (JSON) e
http://localhost:8080/v3/api-docs.yaml (YAML). A integração usa Springdoc 3.1.1,
compatível com a linha Spring Boot 4 do projeto. A UI e o documento foram
validados com HTTP 200; a suíte completa passou com 31 testes.

Fluxo implementado: cliente → API Java → Hermes → modelo gratuito Nous.
O Hermes executa a pesquisa quando necessária e guarda o histórico das conversas.
Os providers diretos, OAuth OpenAI, fallback e pesquisa próprios em Java foram removidos.

## Inicialização

O `.env` precisa das variáveis de `.env.example`. Nesta instalação, a chave local
`HERMES_API_SERVER_KEY` já foi gerada sem expor seu valor. Ela autentica Java → Hermes;
não é uma chave de API paga de um modelo.

```powershell
docker compose up -d
docker exec -it -u hermes ayla-hermes hermes auth add nous --type oauth --no-browser
```

Conclua o login indicado pelo Hermes usando uma conta gratuita Nous Portal.
Depois, reinicie o gateway para recarregar a autenticação:

```powershell
docker compose restart hermes
.\mvnw.cmd spring-boot:run
```

O login é uma etapa inicial externa: a versão HTTP do Hermes instalada não oferece
uma rota para iniciar esse login. Configuração de modelo e instruções é feita pela
API Java depois dele. Não envie senhas nem tokens do Nous no chat.

O software Hermes é gratuito; a inferência depende do plano, modelo e limites do
Nous Portal. A imagem Docker sem login não disponibilizou um modelo gratuito
automaticamente no teste real. O plano gratuito e sua disponibilidade podem mudar.
A API não promete uso ilimitado nem muda para um modelo pago se houver bloqueio.

## Configuração pela API Java

### Painel web local

O painel está habilitado em http://localhost:9119. Usuário e senha estão no
`.env`, nas variáveis `HERMES_DASHBOARD_USERNAME` e `HERMES_DASHBOARD_PASSWORD`.
`HERMES_DASHBOARD_SECRET` mantém as sessões de login estáveis entre reinícios.
O login desse painel é separado do login Nous usado para inferência.
Ele permite acompanhar sessões e ferramentas; o fluxo da Ayla continua na
integração Java e nas instruções enviadas ao agente.

Se alterar essas variáveis, execute `docker compose up -d hermes` para recriar
o contêiner com os valores novos. O volume de sessões e autenticação é mantido.

### Personalidade e preferências

A personalidade da Ayla está em `docker/hermes/SOUL.md`: português brasileiro,
conversa informal, humor leve e sugestões práticas quando ajudarem. O Compose
monta esse arquivo e o bootstrap copia para o SOUL.md nativo do Hermes em cada
inicialização. A identidade anterior foi preservada em `SOUL.md.before-ayla` no
volume. Para mudar o estilo global, edite o arquivo do projeto e execute
`docker compose restart hermes`. Instruções adicionais continuam configuráveis
pela API Java. Comece uma nova conversa para carregar a personalidade atualizada.

Os conjuntos `search` e `memory` ficam habilitados para a API e o CLI. O Hermes
salva preferências por meio da ferramenta `memory` em `/opt/data/memories/USER.md`
e notas em `MEMORY.md`, dentro do volume persistente. Os arquivos existentes não
são substituídos. A memória pertence ao perfil Hermes desta instalação pessoal;
ela é compartilhada pelas conversas desse perfil, não separada por sessionId.
Uma aplicação com várias pessoas precisa de perfis isolados antes de compartilhar
a instância. A API atual é destinada ao proprietário da Ayla.

Exemplos: "Lembra que gosto de respostas curtas", "Agora prefiro mais detalhes"
e "Esqueça aquela preferência". A gravação depende de o modelo chamar a ferramenta;
a Ayla foi instruída a só confirmar depois do sucesso. Novas sessões recebem as
preferências salvas. Isso personaliza o contexto, sem treinar os pesos do modelo.

| Método | Rota | Função |
| --- | --- | --- |
| GET | `/api/v1/hermes/status` | Conexão, prontidão e modelo gratuito efetivo |
| GET | `/api/v1/hermes/models` | Modelos Nous autenticados, disponíveis e gratuitos |
| GET | `/api/v1/hermes/configuration` | Escolha persistida e instruções |
| PUT | `/api/v1/hermes/configuration` | Selecionar modelo gratuito e instruções |
| POST | `/api/v1/chat` | Conversar com Hermes |

Consulte `/models`, copie um identificador retornado e envie:

```http
PUT /api/v1/hermes/configuration
Content-Type: application/json

{"model":"identificador-retornado-em-models","instructions":"Responda de forma objetiva."}
```

Para voltar à escolha automática do primeiro modelo gratuito disponível:

```json
{"model":null,"instructions":"Responda de forma objetiva."}
```

`provider` é sempre `nous` e `freeOnly` sempre `true`. Não são editáveis.
O catálogo e os preços são consultados novamente antes de cada conversa. Se um
modelo escolhido deixar de ser gratuito/disponível, a API responde 409 para
selecionar outro. Se nenhum gratuito existir, responde 503. Preços ausentes ou
pendentes não são aceitos. A disponibilidade e os preços vêm do próprio Hermes.

O Java usa a API nativa de sessões do Hermes com `require_model_lock: true`, que
fixa o modelo do turno e desativa fallback. Não recebe credenciais dos antigos
providers. Chamadas auxiliares automáticas de títulos, revisão e compressão estão
desativadas para evitar seleção de modelos auxiliares fora dessa política.
Conversas que excedam o contexto do modelo precisam iniciar uma nova sessão.

## Conversa normal e pesquisa

```http
POST /api/v1/chat
Content-Type: application/json

{"message":"Olá, Ayla! Pesquise quem ganhou a FNCS de 2026."}
```

Resposta: `{"response":"texto e links produzidos pelo Hermes","sessionId":"..."}`.
Sem `sessionId` ou `conversationId`, a API continua a conversa atual do usuário.
A resposta também inclui `conversationId`, que permanece estável se o Hermes
trocar a sessão interna. O Java não reenvia nem duplica o histórico.

### Usuário, conversa atual e sessões

O chat exige access token da API Java. O primeiro login preserva o usuário
`local-owner` (`AYLA_CHAT_OWNER_ID`) e os dados existentes. A primeira mensagem cria sua
conversa principal e as próximas a reutilizam, inclusive depois de reiniciar o
Java ou Hermes. O vínculo fica no PostgreSQL, não em um cache de memória.
O histórico continua no Hermes; a memória pessoal usa PostgreSQL conforme MEMORIA.md.

| Método | Rota | Função |
| --- | --- | --- |
| GET | `/api/v1/conversations` | Listar as conversas do usuário e indicar a atual |
| GET | `/api/v1/conversations/{conversationId}/messages` | Histórico salvo no Hermes, sem ferramentas/instruções internas; limit/offset para páginas anteriores |
| POST | `/api/v1/conversations` | Criar outra conversa e torná-la atual |
| PUT | `/api/v1/conversations/{conversationId}/current` | Voltar a uma conversa existente |

Para criar uma conversa, envie `{"title":"Jogos"}`. Ela ganha uma sessão Hermes
própria, criada no primeiro turno. Para enviar uma mensagem a uma conversa
específica, use `{"message":"Oi!","conversationId":"uuid-retornado"}`.
Essa escolha vale apenas para o turno; para mudar a conversa atual use a rota
`/current`. O `sessionId` anterior continua aceito se estiver cadastrado como
sessão de uma conversa desse usuário. Identificadores Hermes anteriores a esta
migração não são adotados automaticamente. Seus históricos continuam no painel.

A migração V4 cria `chat_users` e `chat_conversations`. Uma chave estrangeira
garante que a conversa atual pertence ao mesmo usuário. O primeiro acesso usa
upsert e bloqueio de linha, evitando duas conversas principais quando mensagens
chegam juntas. Não há chamada de inferência dentro dessa transação.

Cadastro inicial, login, refresh e logout estão disponíveis conforme
[AUTENTICACAO.md](AUTENTICACAO.md). O identificador vem de `Principal.getName()`
autenticado, nunca de `userId` no JSON ou cabeçalho arbitrário. O modo pessoal
anônimo foi removido. Todas as operações filtram pelo usuário; uma conversa
de outra pessoa retorna 404.

Cada futuro usuário precisa de um perfil Hermes próprio para isolar também as
memórias. O proprietário usa `default`. Usuários novos ficam sem perfil e o
chat retorna 503 até o provisionamento, evitando acesso ao seu perfil pessoal.
A camada Java já suporta um `hermes_profile` atribuído no cadastro, roteia para
`/p/{perfil}/...` e usa `ayla.hermes.profiles.{perfil}.api-key`. Não reutiliza a
chave do proprietário em perfis novos. O provisionamento do perfil, sua chave,
login Nous e `gateway.multiplex_profiles` devem ser integrados ao futuro cadastro;
não há criação automática de contas ou perfis pelo chat. O cadastro também deve
atribuir perfis exclusivos por usuário. Veja a documentação oficial de
[roteamento de perfis](https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server#multi-profile-routing-pprofile).

O Hermes recebe a data atual em America/Sao_Paulo e instruções para pesquisar
informações atuais ou desconhecidas. A API usa `search` (`web_search`) para
pesquisa e `memory` para preferências. A pesquisa retorna resumos e links, sem extrair os sites.
O backend DDGS não exige chave de pesquisa; pode sofrer limites e bloqueios dos
mecanismos públicos. O bootstrap instala o extra DDGS com o gerenciador oficial
do Hermes antes de iniciar o gateway, pois a ferramenta depende de esse pacote
estar disponível no ambiente ativo.

### Fluxo de pesquisa nativo do Hermes

Em conversas normais, o agente decide chamar `web_search` somente para esclarecer um fato público da pergunta atual que não sabe ou não consegue confirmar. Não há classificador ou pesquisa automática adicional antes de cada resposta. Relatos financeiros, cumprimentos e conversa informal não precisam de busca. O histórico ajuda a entender referências, mas perguntas já respondidas não viram tarefas pendentes.

O plugin `ayla-research` injeta orientação para ignorar pesquisas e falhas antigas quando o assunto muda. Somente `searchRequired:true` na API Java ativa o fluxo antecipado com planejamento semântico, busca DDGS e avaliação/refinamento de fontes. Esse modo explícito continua usando apenas o modelo Nous gratuito confirmado no catálogo. Sem a flag, a ferramenta nativa de pesquisa permanece disponível ao agente, sem executar esse fluxo antecipado.

Os contextos de pesquisa persistidos no histórico servem como registro de turnos anteriores; não comprovam uma busca ou falha no turno atual. A resposta deve se limitar ao que o usuário acabou de dizer ou perguntar. A ponte financeira verifica o acesso real da sessão, sem usar mensagens antigas da assistente como diagnóstico.

## Docker e persistência

O Compose usa a imagem oficial Hermes 0.22.4, fixada pelo digest verificado, com
porta local 8642 e volume nomeado `hermes_data`. Não monta o Docker socket nem os
arquivos do projeto no agente. O volume contém login e sessões SQLite do Hermes.
O script `docker/hermes/bootstrap.py` gerencia seu `config.yaml` a cada boot;
não edite esse arquivo para configurar os modelos da Ayla. Use a API Java.

PostgreSQL continua sendo o banco da aplicação Java. A migração V3 cria
`hermes_configuration`. V1 e V2 foram preservadas para não quebrar instalações
existentes; a tabela antiga de providers permanece inativa. Dados históricos e
chaves antigas não foram apagados do banco ou do `.env`, mas não são mais usados.
O Hermes mantém seu armazenamento próprio; não recebe acesso ao PostgreSQL da Ayla.

Em Java hospedado em outro contêiner da mesma rede, configure
`HERMES_BASE_URL=http://hermes:8642`. Não exponha a API de administração Java
diretamente à internet sem o controle de acesso da aplicação.

## Validação

Veja `TESTES.md`. A imagem foi baixada, o serviço iniciado e `/health` respondeu
com Hermes 0.22.4. Depois do login Nous, a conversa real pela API Java respondeu
com sucesso. A personalidade foi carregada e uma chamada real à ferramenta
`memory` gravou a preferência de comunicação no perfil persistente. Uma nova
sessão também respondeu com a personalidade configurada. A pesquisa externa
foi validada de ponta a ponta com cinco fontes reais no contexto do turno e
links na resposta, incluindo `searchRequired:true` sem palavra-chave de busca.
Os testes locais da integração
passaram sem consumir inferência externa. Após adicionar as sessões por usuário,
a suíte completa passou com 30 testes, incluindo persistência e concorrência em
PostgreSQL isolado. Duas mensagens reais sem IDs reutilizaram a mesma conversa e
sessão, e a migração V4 foi aplicada ao banco da aplicação.

Fontes oficiais:
- [Hermes API Server](https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server)
- [Pesquisa e DDGS](https://hermes-agent.nousresearch.com/docs/user-guide/features/web-search)
- [Docker](https://hermes-agent.nousresearch.com/docs/user-guide/docker)
- [Nous Portal e planos](https://portal.nousresearch.com/)

## Isolamento entre contas

Cada usuário tem um perfil Hermes exclusivo e uma chave de API própria, além de sessões de conversa vinculadas ao seu ID no PostgreSQL. Somente a conta principal utiliza `default`. Perfil ausente, compartilhado ou reservado resulta em bloqueio, sem recorrer ao perfil principal. Configurações pessoais de voz e instruções também não recorrem às configurações salvas do proprietário. A autorização OAuth fica vinculada ao usuário e ao perfil que iniciaram o fluxo e expira em quinze minutos; a migração V29 persiste esse vínculo no PostgreSQL, permitindo consultar o fluxo em outra réplica ou após reiniciar a API.

Novos perfis são clonados de `ayla-internal-template`, criado pelo bootstrap apenas com configurações e plugins da aplicação, personalidade base e uma chave aleatória. Não é usado o perfil principal como origem: a clonagem nativa pode copiar `.env`, identidade e memórias. A seleção de perfil fica validada no Java antes de encaminhar as chamadas ao Hermes. A conexão WhatsApp também é selecionada pelo usuário autenticado, sem aceitar que o navegador escolha outra conta.

Para ativar esta alteração, execute primeiro o bootstrap atualizado do Hermes, recriando seu container, e depois carregue o código Java, Node e frontend atualizados. O template é reservado para provisionamento e não pode ser usado como perfil de um usuário. Os perfis antigos não são apagados nem recriados automaticamente; seus arquivos e credenciais precisam de auditoria no ambiente antes de confirmar o isolamento de instalações já existentes.

### Financeiro pessoal

O Hermes interpreta todos os relatos financeiros e pergunta naturalmente pelos dados obrigatórios ausentes (valor, reais/centavos e descrição). Usa as seis ferramentas nativas ayla-finance para cadastrar, consultar, editar e excluir no banco Java. finance_record recebe apenas tipo, valor, unidade e descrição (categoria/data opcionais). O Java valida campos obrigatórios, conversão exata em centavos, propriedade, idempotência e versões; identificadores internos não ficam a cargo da IA. Toda consulta financeira deve usar o banco atual, incluindo registros externos ao chat; nunca apenas a memória. Não há resposta automática por parser Java nem planejador auxiliar. Configuração, exemplos e endpoints: [FINANCEIRO.md](FINANCEIRO.md).

