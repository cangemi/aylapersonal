# WhatsApp da Ayla

O `agentNode` recebe mensagens do WhatsApp e chama `POST /api/v1/chat` da API Java. A API continua usando o Hermes. Somente conversas autorizadas recebem respostas. Respostas por áudio vêm acompanhadas apenas do cabeçalho `-------Ayla--------`; respostas somente em texto não recebem cabeçalho.

## Iniciar

### Conexão opcional e dois modos

O onboarding mantém a etapa WhatsApp e permite **Pular por enquanto**, mesmo se o serviço estiver indisponível. Nome, troca de senha provisória e Hermes continuam obrigatórios; o chat do site funciona sem WhatsApp. O botão de conversar pelo WhatsApp, sem uma configuração pronta, abre um modal com **Cancelar** e **Configurar WhatsApp**.

- **Usar meu próprio número** é a opção principal e preserva o grupo pessoal e as respostas pela mesma conta conectada.
- **Usar um número exclusivo para a Ayla** exige dois pareamentos por QR: a conta do usuário e uma conta diferente para a Ayla. O atendimento só funciona enquanto ambas estão prontas. O botão de conversa autoriza a conversa privada e abre `wa.me` do número da Ayla, para uso pelo WhatsApp do usuário; não cria grupo.

No modo exclusivo, a sessão do usuário recebe apenas seus eventos nativos `fromMe === true`, identifica os pedidos e lê o contexto solicitado. Na conversa privada entre usuário e Ayla, as respostas saem pela conta exclusiva da Ayla. Em todas as outras conversas e grupos, as chamadas `Ayla0101` recebem resposta pelo WhatsApp do próprio usuário, na mesma conversa, como no modo de um número só. Não é necessário adicionar o número da Ayla aos grupos, e as respostas dessas chamadas não são redirecionadas ao privado. Mensagens de terceiros e mensagens enviadas pela própria conta da Ayla não acionam a IA. As invocações continuam isoladas das memórias da conversa principal. Áudios, respostas de contingência e lembretes seguem a mesma regra de envio conforme o destino.

`PUT /api/v1/me/whatsapp/configuration` aceita somente `{ "mode": "own" }` ou `{ "mode": "dedicated" }`. O status retorna `mode`, `ready`, o estado do usuário em `connected` e o da conta exclusiva em `ayla`. `ready` exige dois números distintos no modo exclusivo. O QR exclusivo é `/api/v1/me/whatsapp/ayla/qr`; as ações `/ayla/connect`, `/ayla/disconnect`, `/ayla/restart` e `/ayla/logout` seguem a mesma base autenticada. A migração V42 preserva todas as conexões anteriores no modo próprio. As sessões têm identificadores e backups separados, vinculados ao mesmo proprietário. A recuperação de senha continua vinculada somente ao número do usuário, nunca ao da Ayla.

As instruções sobre grupo pessoal e envio pela mesma conta, abaixo, descrevem o modo **Usar meu próprio número**.

### Criar a conversa pessoal pelo painel

Em **WhatsApp → Seu cantinho com a Ayla**, conecte a conta e clique em **Criar minha conversa Ayla**. O endpoint autenticado do proprietário `POST /api/v1/whatsapp/personal-chat` cria o grupo `Ayla` com uma lista vazia de convidados: somente o próprio número conectado participa. Ele fica autorizado automaticamente como principal, sem precisar enviar um comando. A principal anterior passa a chamadas pontuais.

Na página **Conversar**, o botão **Grupo no WhatsApp** abre esse mesmo fluxo, com prévia do avatar, estado da conexão e resultado. O grupo recebe a foto `agentNode/assets/AylaChat.png`, uma cópia do avatar usado em `frontend/public/ayla-chat.png`. A imagem é aplicada também quando o grupo é reutilizado. Falha ao aplicar a foto retorna `pictureUpdated=false`, sem desfazer o grupo nem criar outro; o painel permite tentar novamente.

Antes de reutilizar o grupo, o Node consulta seu estado no WhatsApp. Um grupo confirmado como encerrado ou sem participação é desabilitado no cadastro e substituído; falhas de consulta não autorizam criar outro. Se o WhatsApp criou o grupo mas a biblioteca perdeu a resposta, a recuperação aceita apenas um grupo Ayla ativo, pertencente à conta conectada e confirmado pelo servidor com um participante. Mais de um candidato exige seleção manual. A foto usa a ação nativa `WAWebProfilePicThumbAction.setProfilePic`, após verificar a permissão; a biblioteca antiga espera um campo `status` que a resposta atual de foto já não fornece.

O ID do grupo é persistido em `whatsapp_instances.personal_group_chat_id` (Flyway V24). Novas chamadas reutilizam e reativam esse destino, sem criar outro grupo. Não envia convites nem mensagens para outros números. Depois, abra o WhatsApp e procure o grupo Ayla. A assistente responde pelo número conectado, com o processamento e o contexto já existentes. Os testes não criam grupos reais.

Compatibilidade com WhatsApp Web: `groupCreation.js` carrega `WAWebNewGroupFlowLoadable.requireBundle()` antes de `Client.createGroup` quando o módulo `WAWebGroupCreateJob` ainda não está disponível. Sem isso, a biblioteca retorna um erro genérico de criação após reiniciar o navegador (whatsapp-web.js #201920). Os testes unitários verificam carregamento, reutilização e falha explícita do módulo; o diagnóstico no navegador conectado não cria grupos.

### Principal e chamadas pontuais

`configurar Ayla0101` define **uma conversa principal por instância**. Ela responde às suas novas mensagens normalmente e é o único canal da IA com acesso às memórias pessoais. Configurar outra principal transforma a anterior em chamada pontual; não apaga o histórico, mas desvincula a sessão anterior para não carregar contexto pessoal na nova modalidade.

Nas demais conversas/grupos, `Ayla0101` invoca uma ação com histórico próprio (incluindo o contexto local solicitado). Elas não consultam nem guardam memórias pessoais e não recuperam o papo de outras conversas. Só a principal pode pesquisar trechos relevantes de outras conversas, por meio de `conversation_context_search`, quando necessário ao pedido atual. Estoque, compras e financeiro continuam registros da sua conta para executar as ações solicitadas.

No frontend, desmarcar “Chamada pontual” define a principal e substitui a anterior. A troca é transacional no Node e há índice único no PostgreSQL. Na migração de cadastros antigos, a conversa automática atualizada mais recentemente permanece principal; outras passam a chamadas pontuais. Reenvie `configurar Ayla0101` na conversa desejada para escolher outra. Somente o número conectado continua podendo configurar ou invocar a Ayla.

A recuperação de senha da API usa uma mensagem privada enviada à conversa do próprio número conectado. Ela exige vínculo prévio desse número à conta e não aceita destinatário escolhido na solicitação. O endpoint Node `/internal/password-recovery` exige a chave interna, valida o proprietário e o número vinculado, deduplica pedidos e não encaminha o segredo à IA. A mensagem fica marcada como sensível, sem corpo salvo no histórico de respostas; também é excluída das citações e do contexto da Ayla. Veja [AUTENTICACAO.md](AUTENTICACAO.md).

1. Inicie a API Java para o Flyway aplicar as migrações, inclusive V15. O PostgreSQL precisa estar disponível.
2. Execute `docker compose up -d --build whatsapp` na raiz.
3. No Swagger (`http://localhost:8080/swagger-ui/index.html`), abra **WhatsApp** e execute `POST /api/v1/whatsapp/connect`.
4. Consulte `GET /api/v1/whatsapp/qr`. O campo `qrImageDataUrl` pode ser usado diretamente no `src` de uma imagem no frontend. Consulte novamente para acompanhar a renovação do QR.
5. No celular, abra os dispositivos conectados e escaneie o QR. Ele também aparece no console: `docker compose logs -f whatsapp`.
6. Consulte `/status`: `connected=true` indica conexão; `sessionSaved=true` confirma o backup no PostgreSQL. O primeiro backup pode levar cerca de um minuto após conectar.

A porta 4000 é a API interna Node, sem página HTML. O frontend deve usar os endpoints Java abaixo; não precisa receber a chave interna.

## Endpoints Java

Base: `/api/v1/whatsapp`.

| Método | Caminho | Função |
| --- | --- | --- |
| GET | `/status` | Estado, número conectado, erro e backup da sessão |
| GET | `/qr` | QR atual como texto, imagem e ASCII |
| POST | `/connect` | Conectar ou iniciar pareamento |
| POST | `/disconnect` | Desconectar preservando a sessão salva |
| POST | `/restart` | Reconectar com a sessão existente |
| POST | `/logout` | Sair e excluir a sessão salva; exige novo pareamento |
| GET | `/chats` | Listar conversas configuradas |
| POST | `/chats` | Cadastrar conversa autorizada |
| PUT | `/chats/{id}` | Alterar telefone, nome ou enabled |
| DELETE | `/chats/{id}` | Excluir autorização |

Exemplo de cadastro:

```json
{"phone":"5511999999999","name":"Pedro"}
```

Para desativar via PUT: `{"enabled":false}`. Para grupos, use `chatId` terminado em `@g.us`. Alterar o destinatário inicia uma nova conversa de IA.

## Comandos no WhatsApp

- `configurar ayla0101`: autoriza a própria conversa e confirma sem chamar a IA.
- `tirar ayla0101`: desativa a própria conversa.

Todo atendimento do WhatsApp é exclusivo do próprio número conectado, identificado por `fromMe === true` nativo. Mensagens de outros participantes são descartadas antes da fila, download de mídia, reações e chamada à IA, inclusive em grupos e conversas autorizadas para atendimento automático. Nome de contato, autor citado ou conhecer o código não concede acesso. O modo automático responde somente às mensagens do proprietário; `mentionOnly=true` exige também a chamada explícita.

O identificador é fixo em `ayla0101`. Comandos normalizam Unicode, espaços e maiúsculas/minúsculas: `Ayla0101`, `ayla0101` e `AYLA0101` são equivalentes. `CONFIGURAR Ayla0101` e `tirar AYLA0101` funcionam. A antiga variável `WHATSAPP_COMMAND_TOKEN` não troca o identificador.

Conforme solicitado, os autorizados compartilham os dados pessoais e financeiros do proprietário. Cada chat tem uma conversa própria, persistida no PostgreSQL, sem substituir a conversa atual do frontend. Mensagens enviadas pelo próprio número também acionam a Ayla nos grupos e conversas autorizados, incluindo a conversa consigo mesmo. As respostas são enviadas pela mesma conta conectada, para a mesma conversa. Mensagens duplicadas e respostas da própria Ayla são ignoradas. O atendimento aceita texto, imagens JPEG/PNG/WebP, áudios e documentos PDF/DOCX/DOC/TXT. Vídeos não são analisados.

## Chamar em outras conversas

Em qualquer conversa/grupo, envie do seu próprio número uma mensagem contendo `Ayla0101` (maiúsculas/minúsculas indiferentes; `@Ayla0101` também funciona). Exemplos:

- Respondendo à mensagem com os itens: “Ayla0101 anote esses itens na lista de compras”.
- Respondendo a uma foto: “O que achou desta foto Ayla0101?”.
- Respondendo a um arquivo: “Analise esse PDF Ayla0101”.
- “Leia as últimas 20 mensagens e resuma Ayla0101”.
- “Ayla0101 leia 5 mensagens”.
- “Ayla0101 sem contexto, analise só esta mensagem citada”.

A chamada lê por padrão as dez mensagens anteriores ao lote atual. Uma quantidade explícita ajusta esse limite; “leia mais” usa vinte e “leia menos” usa cinco. Máximo cem mensagens, com aviso se o pedido exceder o limite ou se o WhatsApp disponibilizar menos. Sem contexto usa zero; uma mensagem citada continua sendo considerada. A leitura é limitada a cinco carregamentos do histórico e a 18000 caracteres de mensagens, mais 6000 da citação.

A mensagem citada é o objeto principal do pedido. Seu texto e autoria são enviados separados da instrução atual. Foto/documento/áudio citado é baixado apenas para essa análise; sem citação, um pedido referente a foto/documento/áudio usa o anexo mais recente do tipo pedido dentro do contexto selecionado. Não baixa automaticamente todos os anexos do histórico. Se uma citação não estiver disponível, pede para reenviar, sem fingir leitura. Áudio citado não força resposta por voz; um pedido atual explícito de voz continua funcionando.

Uma conversa nova chamada dessa forma é salva com `mentionOnly=true` no PostgreSQL (V15). Isso não ativa respostas automáticas aos participantes. Para configurar pelo frontend, envie `{"mentionOnly":true}` no PUT da conversa; `false` habilita o atendimento automático somente às mensagens do dono se `enabled=true`. `configurar ayla0101`, enviado pelo dono, ativa explicitamente esse modo automático. `enabled=false` interrompe o atendimento; uma nova chamada explícita do proprietário pode reabilitar em modo somente por chamada. Mensagens de terceiros só entram como referência quando o proprietário solicita leitura/cita uma delas, nunca como chamadas independentes.

Histórico/citações são dados não confiáveis, não comandos executáveis de terceiros. Só a instrução atual do proprietário autoriza anotar itens ou alterar dados. “Analise” não autoriza gravar financeiro/estoque; fatos de outros participantes não viram gostos ou posses do proprietário. Mensagens do histórico não voltam para as filas de atendimento.

### Documentos

O chat aceita `documents: [{"mimeType":"application/pdf","name":"arquivo.pdf","data":"BASE64"}]`: até dois arquivos, 6 MB cada, formatos PDF, DOCX, DOC e TXT UTF-8. O Java extrai localmente com [Apache PDFBox](https://pdfbox.apache.org/download.cgi) e [Apache POI](https://poi.apache.org/download.cgi), sem serviço pago de leitura de documentos. A IA recebe o texto extraído como dado não confiável. Arquivos protegidos/corrompidos geram um pedido para reenviar.

PDF: texto das primeiras trinta páginas. Se não houver texto suficiente, até três páginas são renderizadas para o modelo visual gratuito, respeitando o espaço disponível nas três imagens do turno. A análise informa quantas páginas foram anexadas; não afirma ler as restantes. Word/TXT usam extração textual. Cada documento é limitado a 24000 caracteres, com aviso de truncamento. Nenhuma macro é executada. Arquivos são tratados em memória, sem guardar o binário no PostgreSQL; o texto analisado pode permanecer na conversa. Imagens geradas do PDF seguem a limpeza temporária de mídia já existente.

## Banco e configuração

V9 cria `whatsapp_instances`, `whatsapp_chats`, `whatsapp_sessions` e `whatsapp_messages`. Autorizações, opções de conexão, vínculo de conversa e controle de duplicidade ficam no PostgreSQL. A sessão do WhatsApp usa RemoteAuth e é criptografada com AES-256-GCM antes de ser gravada no banco. Arquivos temporários do Chromium ainda existem enquanto o serviço funciona; o backup durável está no PostgreSQL. As pastas antigas de autenticação não são importadas automaticamente.

O `.env` local já recebeu as chaves da integração. Guarde `WHATSAPP_SESSION_KEY`: mudar ou perder essa chave impede recuperar a sessão anterior. `AYLA_WHATSAPP_API_KEY` deve ser a mesma no Java e no Node; não a envie ao frontend. `.env.example` descreve as variáveis. O Docker conecta ao Java do host pela porta 8080. Para outra instalação, ajuste `WHATSAPP_JAVA_BASE_URL` no serviço.

A instância restaura automaticamente a conexão após reiniciar se estava marcada como conectada. `/disconnect` desativa essa restauração até um novo `/connect`. `/logout` mantém os cadastros de contatos, mas apaga a credencial do WhatsApp.

Para executar sem Docker: Node 22+, Chromium/Chrome configurado em `PUPPETEER_EXECUTABLE_PATH`, `npm ci --include=optional` e `npm start` dentro de `agentNode`. Execute apenas uma instância do serviço por `WHATSAPP_CLIENT_ID`.

## Testes

`cd agentNode; npm test` executa os testes locais de autorização, filas, QR, HTTP e envio à API Java. O teste PostgreSQL é executado automaticamente por `WhatsappDatabaseTests`, usando banco descartável do Testcontainers. `WhatsappApiTest` valida a identidade de contatos e a proteção da administração. Nenhum desses testes envia mensagens reais ou chama modelos de IA.

Dependências: as correções compatíveis de `npm audit fix` foram aplicadas. Ainda há nove avisos altos na árvore transitiva do Puppeteer, documentados em TESTES.md; o ajuste sugerido pelo npm exige troca de versão da biblioteca WhatsApp. A imagem usa Chromium do sistema.

## Somente mensagens novas

O Node descarta mensagens recebidas durante a inicialização e compara a data original da mensagem com o instante em que a conexão ficou pronta. Mensagens antigas, não lidas e sincronizadas posteriormente não acionam a IA nem os comandos de autorização. Mensagens sem data válida são descartadas. A mesma regra é renovada ao reconectar, inclusive após queda de rede. Filas de uma conexão anterior não são retomadas.

Exceção explícita: uma nova chamada `Ayla0101` enviada pelo proprietário pode ler mensagens anteriores como contexto ou a mensagem citada. Elas são referências para o pedido atual, nunca novos eventos para executar automaticamente. A captura complementar suprime o retorno dessas mensagens históricas à fila ao carregá-las.

Como o WhatsApp fornece a data em segundos, o segundo exato da conexão também é descartado por segurança; envie a nova mensagem a partir do segundo seguinte. Não há leitura retroativa das mensagens recebidas enquanto a Ayla estava offline.

A captura usa os eventos da biblioteca e uma assinatura complementar dos eventos novos do WhatsApp Web, incluindo mensagens enviadas pelo celular do próprio dono. Essa assinatura não busca histórico; notificações duplicadas são filtradas antes de repetir o atendimento.


## Mensagens picotadas e indicador de digitação

Mensagens de cada conversa autorizada são agrupadas até completar **5 segundos sem novas mensagens**. Cada nova mensagem reinicia esses 5 segundos. O conteúdo é enviado junto, separado por quebras de linha, em uma única chamada ao chat Java. Eventos duplicados não reiniciam o prazo. Conversas diferentes têm contagens independentes. Comandos de ativar/desativar são imediatos.

Durante a consulta à IA, o serviço envia o estado de digitando diretamente pelo ID da conversa, renova a cada 20 segundos e limpa o estado ao terminar, inclusive em falhas. Não utiliza a consulta completa de metadados do grupo. Se o indicador falhar, a resposta continua normalmente. O WhatsApp controla a exibição desse estado na própria conta; o serviço envia o sinal, mas não controla a interface do aplicativo.

Funciona em grupo somente com o dono e na conversa consigo mesmo: habilite com `configurar ayla0101` e envie mensagens pelo número conectado. Se chegarem novas mensagens enquanto a IA responde, elas entram em outro lote, atendido após o anterior. Desativar a conversa ou perder a conexão descarta o que ainda estava aguardando o prazo; esse conteúdo não é retomado como histórico ao reconectar.


## Imagens e financeiro

Envie fotos nos chats autorizados, com ou sem legenda. A foto e os textos enviados nos cinco segundos seguintes seguem juntos para a API Java e para o Hermes, como conteúdo multimodal; não se usa pesquisa na internet para ler a foto. Imagens antigas não são baixadas e contatos não autorizados não acionam download. Mídias ainda incompletas aguardam o evento de carregamento; falha no download pede reenvio, sem inventar uma análise.

O modelo visual validado é Step 3.7 Flash via Nous, somente quando a gratuidade estiver confirmada no catálogo do Hermes. Ele é usado para o turno com imagens sem alterar a escolha salva para o chat de texto. A configuração auxiliar visual também aponta explicitamente para o modelo gratuito. Se a opção visual gratuita estiver indisponível, a análise falha sem trocar para uma opção paga.

Fotos comuns recebem respostas de acordo com o contexto. Notas/comprovantes de compras pessoais com valor, moeda e descrição legíveis autorizam o registro financeiro usando as ferramentas existentes do Java, em centavos inteiros no PostgreSQL. Total, subtotal, troco e itens não devem virar despesas duplicadas. Várias fotos da mesma nota representam uma compra. Se houver indício de registro anterior, a IA deve consultar o banco. Se a foto estiver ilegível ou houver ambiguidade sobre valor, moeda, titular, direção da transferência ou data, ela deve perguntar o que falta. Pedidos para somente analisar são respeitados. Orçamentos e boletos não pagos não são tratados como gastos já realizados. Compras distintas são tratadas uma por turno.

Limites: até 3 imagens por chamada, 4 MB por imagem e 6 MB no total, nos formatos JPEG, PNG e WebP. Ao exceder o limite acumulado, o Node separa as imagens em chamadas sucessivas. O JSON enviado a `POST /api/v1/chat` aceita `images: [{"mimeType":"image/jpeg","data":"BASE64_PURO"}]`, além de message. URLs e arquivos SVG/PDF não são aceitos nesse campo. Somente a análise em texto entra no histórico da conversa; o financeiro guarda os dados estruturados do lançamento. Os arquivos das imagens são processados em uma sessão temporária do Hermes e removidos após a análise. O Node não grava os bytes nas tabelas de WhatsApp nem os imprime nos logs.

Base técnica: [API multimodal do Hermes](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/features/api-server.md) e [suporte visual do Step 3.7 Flash](https://build.nvidia.com/stepfun-ai/step-3.7-flash). O contrato também foi conferido no código da imagem Docker instalada.

Compatibilidade de download: a versão instalada de whatsapp-web.js omite o MIME no gerenciador interno do WhatsApp. A integração informa o MIME, valida os bytes descriptografados e mantém o ID completo, incluindo o campo $1 usado por versões recentes. Foi validada na foto real que apresentou o erro, sem reenviá-la automaticamente à IA ou ao financeiro. [Relato técnico na biblioteca](https://github.com/wwebjs/whatsapp-web.js/issues/201908).


### Áudio e descarte de mídia

Mensagens de voz e arquivos de áudio novos, em conversas autorizadas, seguem a mesma janela de cinco segundos e indicador de digitação. O Node baixa/descriptografa a mídia em memória e a envia para o Java em `audios: [{"mimeType":"audio/ogg","data":"BASE64_PURO"}]`. Até três áudios por chamada, 6 MB cada, 12 MB no total; mensagens de voz de até cinco minutos. Formatos: Ogg/Opus, MP3, MP4/M4A, WAV, WebM, FLAC e AAC.

O Java usa o endpoint nativo `/api/audio/transcribe` do painel Hermes com autenticação de servidor. O Whisper base executa localmente na CPU em português, sem chave adicional ou cobrança por transcrição. O primeiro uso baixa o modelo; os pesos ficam em cache para os próximos áudios. Configure `HERMES_DASHBOARD_USERNAME` e `HERMES_DASHBOARD_PASSWORD` no `.env`; para outro endereço, `HERMES_DASHBOARD_URL` (padrão `http://127.0.0.1:9119`).

A transcrição segue para a Ayla como fala do usuário. A Ayla pode consultar/registrar/editar/excluir usando as ferramentas financeiras existentes. Se valor, unidade ou descrição estiverem ambíguos, ela pede esclarecimento. O reconhecimento pode errar palavras; áudio silencioso ou incompreensível não deve criar lançamento.

O endpoint nativo remove seu arquivo temporário de áudio ao terminar, inclusive em erro. Imagens usam uma sessão descartável: ao concluir, o Java exclui essa sessão e o arquivo exato do cache de upload, preservando a análise textual na conversa principal. Se a limpeza de imagens falhar, uma pendência na tabela `hermes_media_cleanup` permite novas tentativas automáticas (a partir de dez minutos). Os bytes de áudio/imagem não são salvos nas tabelas de WhatsApp. O descarte não apaga as mensagens originais no WhatsApp, os registros financeiros, a sessão de login ou os pesos do Whisper. Imagens de conversas anteriores a essa implementação não são apagadas retroativamente.

### Respostas por voz

A Ayla responde também por áudio quando recebe áudio ou quando você pede no turno atual, por exemplo: "me manda um áudio" ou "responda por voz". "Não mande áudio" e "responda só em texto" desativam a voz nesse turno. Uma mensagem comum de texto não herda o formato de uma conversa anterior. A IA também pode escolher voz espontaneamente num papo claramente leve ou ao comentar uma imagem informal, respeitando o intervalo abaixo. Para controle explícito no frontend, `POST /api/v1/chat` aceita `audioReply: true/false`; ausente, aplica as regras acima. A resposta JSON mantém `response`, `sessionId`, `conversationId` e inclui `audio: {mimeType,data}` quando a síntese funciona.

O Hermes gera a voz pelo Edge TTS. O padrão é `pt-BR-FranciscaNeural`, velocidade 1.02 e pitch +2 Hz, com entonação mais suave. É um serviço online sem assinatura ou chave de API; a disponibilidade depende do serviço Edge. Não há provedor pago alternativo. Não é clonagem de voz. As opções brasileiras suportadas são Francisca, Thalita e Antônio.

O WhatsApp envia somente `-------Ayla--------` e a mensagem de voz em Ogg/Opus, sem repetir a resposta em texto. Se a geração ou entrega de voz falhar, envia a resposta completa em texto, sem repetir a IA nem o financeiro. O texto completo continua no JSON e no registro interno da resposta. Respostas normais de texto não levam cabeçalho. Ecos da própria conta são filtrados pelo hash antes do envio e pelo identificador persistido; o filtro de texto é por conversa e expira em dois minutos.

O endpoint nativo `/api/audio/speak` remove o arquivo gerado após lê-lo; o Hermes limpa os intermediários de conversão. Java e Node transportam os bytes em memória. Respostas faladas com mais de 6.000 caracteres ficam somente em texto, sem truncar dados. Links e Markdown continuam no texto; a fala não lê URLs/código.

A imagem fixada do Hermes inclui Edge TTS 7.2.7, incompatível com a autenticação atual do serviço. `docker/hermes/prepare_voice.py` atualiza somente essa dependência para a versão oficial 7.3.1 e regenera o lock pelo uv antes de o gerenciador do Hermes preparar o ambiente. Esse arquivo é montado no Compose; as sessões e dados continuam no volume existente.

Fontes: [Edge TTS](https://github.com/rany2/edge-tts), [correção oficial do erro 403](https://github.com/rany2/edge-tts/issues/490). Kokoro/pf_dora também foi pesquisado como alternativa local gratuita, mas não foi instalado nem usado como fallback.

Compatibilidade de envio de voz: `patchWhatsapp.js` aplica a correção de identificador de mídia descrita em [whatsapp-web.js #201921](https://github.com/wwebjs/whatsapp-web.js/issues/201921) durante o postinstall, com verificação do trecho esperado. O adaptador `voiceMedia.js` usa o identificador atual `$1` e recupera somente a nova mensagem correspondente quando o SDK devolve vazio após enviar. Não repete envios automaticamente. Mensagens de voz não recebem legenda; o cabeçalho é a única mensagem escrita que as acompanha. O hash é registrado antes do envio para impedir eco da própria voz, e o ID é persistido após a confirmação.

### Pronúncia de valores e timbre

A voz padrão continua Francisca. O timbre agora usa pitch +2 Hz e ritmo +2%, reduzidos dos anteriores +14 Hz e +8%. A adaptação em `prepare_voice.py` repassa o parâmetro de pitch já suportado pelo SDK Edge, que a versão fixada do Hermes não encaminhava.

### Configuração de voz pelo Java

As rotas também aparecem na seção **Voz** do Swagger:

| Método | Rota | Uso |
| --- | --- | --- |
| GET | `/api/v1/hermes/voice` | Consultar parâmetros salvos |
| PUT | `/api/v1/hermes/voice` | Salvar os três parâmetros |
| GET | `/api/v1/hermes/voice/voices` | Listar IDs, nomes, gênero e idioma das vozes suportadas |
| POST | `/api/v1/hermes/voice/preview` | Ouvir parâmetros temporários sem salvar (body opcional) |

Exemplo de corpo do PUT:

```json
{
  "voice": "pt-BR-FranciscaNeural",
  "speed": 1.02,
  "pitchHz": 2
}
```

`speed` aceita de 0.75 a 1.50, com até duas casas decimais; 1.00 é a velocidade original. `pitchHz` aceita inteiros entre -50 e 50; zero mantém o tom original, valores negativos abaixam o tom. `voice` deve ser um ID da lista de vozes suportadas. Todos os campos são obrigatórios; valores inválidos retornam HTTP 400.

A configuração é global e fica na tabela PostgreSQL `voice_settings` (migração V12). Cada síntese lê a configuração atual do banco e aplica somente o bloco de voz na configuração nativa do Hermes, sem alterar o modelo ou a personalidade. Assim, reiniciar Java/Hermes não perde os ajustes; áudios em andamento podem usar o ajuste anterior. O PUT salva sem gerar áudio nem chamar a IA. As regras de quando enviar áudio continuam as mesmas.

A prévia retorna `{mimeType, data}`, com `data` em base64. Envie `{voice, speed, pitchHz}` para experimentar sem salvar; sem corpo, ela usa a configuração salva. No frontend, **Ouvir sem salvar** envia os valores do formulário; somente **Salvar voz** persiste os ajustes. A prévia usa uma frase fixa, não envia nada ao WhatsApp nem chama a IA. O adaptador nativo `/api/audio/ayla-preview` sintetiza com Edge usando apenas parâmetros desta requisição, sem alterar o PostgreSQL ou arquivos de configuração do Hermes e sem criar arquivos de áudio temporários. A prévia depende do Hermes conectado. Uma falha mantém a configuração anterior intacta.

Antes da síntese, o Java transforma valores monetários em palavras em português: `R$ 0,12` vira “doze centavos”, `R$ 1,00` vira “um real” e `R$ 1.234,56` vira “mil duzentos e trinta e quatro reais e cinquenta e seis centavos”. A regra contempla singular, negativos e centavos; não arredonda frações de centavo nem adivinha separadores ambíguos. Valores explicitamente em dólar não são reclassificados como reais. A transformação é só para a voz; texto, saldo e banco permanecem intactos.

A conversão usa ICU4J para as regras de português e inteiros/BigDecimal para separar os valores exatamente, sem ponto flutuante binário. Como há uma dependência nova no Java, reinicie a execução Maven/IDE para carregar a biblioteca no classpath.

Pronúncias específicas de nomes podem ser configuradas no `.env` com `AYLA_VOICE_PRONUNCIATIONS=Nome=pronúncia;Outro=outra pronúncia`. A substituição vale apenas para palavras completas no áudio. Nenhuma pronúncia de Cangemi foi presumida: falta o usuário dizer como deseja que soe.


### Voz espontânea e personalidade

Indicador de processamento: ao encaminhar um lote para a API Java/Ayla, o Node reage com ⏳ à última mensagem desse lote, inclusive em grupos e conversas com o próprio número. Durante os cinco segundos de agrupamento não há reação. Ela permanece durante processamento e envio da resposta (incluindo voz) e é removida ao concluir ou falhar, pelo bloco finally. Uma desconexão controlada tenta remover os indicadores antes de fechar o navegador. Reações são ignoradas como entrada para evitar loops. Falhas do indicador não impedem nem repetem a chamada à IA. A remoção depende da conexão com o WhatsApp; uma queda abrupta do processo/rede pode impedir a limpeza.

A escolha de voz espontânea é feita pela IA no próprio turno, sem outra chamada de classificação. O Java só aceita essa indicação em contexto leve, sem pedido de somente texto; faz uma verificação adicional de termos financeiros/sérios e limita a uma tentativa espontânea a cada quinze minutos por conversa. A reserva é atômica, pertence ao usuário da conversa e fica no PostgreSQL (`chat_conversations.last_spontaneous_voice_at`, migração V11), inclusive após reiniciar. Áudios pedidos e respostas a áudios não dependem desse intervalo. Sem indicação da IA, a resposta normal continua em texto.

Notas fiscais, recibos, comprovantes, dinheiro, documentos e assuntos sérios não devem receber áudio espontâneo. Uma imagem informal pode render um comentário falado quando combinar com o momento. O controle `audioReply:false` impede todos os modos de voz naquele turno. Marcadores internos da escolha são retirados da resposta antes de retorná-la ao usuário.

A personalidade passou a ser jovial, engraçada, risonha e muito simpática, com gosto por conversar, piadas espontâneas, sarcasmo leve e impaciência brincalhona ocasional. Humor e risadas não são obrigatórios em toda fala e ficam fora das confirmações financeiras e de momentos delicados. A voz continua Francisca; o tom e o ritmo foram ajustados sem trocar o serviço gratuito.


### Respostas a mensagens específicas

Na conversa principal, responder pelo WhatsApp a uma mensagem inclui essa citação no contexto, mesmo sem Ayla0101. Nas conversas secundárias, a chamada do proprietário com Ayla0101 continua obrigatória. Textos citados são enviados como referência; áudios citados são baixados e transcritos, separados dos áudios atuais do proprietário. A leitura da citação não ativa o processamento automático de mensagens antigas. As proteções de mensagens de recuperação de acesso continuam aplicadas.

Uma resposta de voz pode retornar textSupplement na API: apenas links ou dados necessários por escrito, enviados depois do áudio. O texto completo continua disponível em response para histórico e fallback. O Java remove o separador interno antes de persistir ou retornar a resposta e evita ler URLs na síntese; o Node não duplica toda a fala no WhatsApp.


### Espera enquanto o proprietário escreve

A janela padrão é de cinco segundos. Cada nova mensagem reinicia a contagem. A integração observa a presença nativa (digitação/gravação) e só considera IDs identificados pelo WhatsApp como o próprio número conectado. Quando o sinal de atividade é recebido, aguarda seu término e mais cinco segundos; uma sondagem de presença durante a espera acompanha a pausa. Outros participantes não conseguem manter um lote parado por estarem digitando. Presença não inicia conversas nem busca mensagens antigas. A API do WhatsApp pode não fornecer a digitação do próprio número a dispositivos vinculados: nesse caso, vale a pausa de cinco segundos após a última mensagem recebida. Limites de anexos continuam separando lotes para respeitar os contratos de mídia.

A consulta de resultado pronto na fila ocorre a cada segundo. A lista de modelos gratuitos mantém um cache de quinze segundos por perfil, sem trocar o modelo selecionado nem reutilizar dados de outro usuário. O cache não serve resultados expirados após falha. O tempo de inferência e de ferramentas externas permanece dependente do provedor.

### Apagar uma conversa pelo frontend

A lista de conversas tem lixeira no desktop e no seletor mobile. GET /api/v1/conversations/{id}/deletion informa o alcance antes da confirmação; DELETE /api/v1/conversations/{id}?removeWhatsappGroup=true exige nova verificação no servidor. Esse fluxo não é oferecido como ferramenta à IA nem é aceito com as credenciais do bridge Hermes/WhatsApp no Java.

Invocações e grupos compartilhados são apagados apenas na Ayla. O WhatsApp permanece intacto e a próxima chamada abre outro contexto. Para o grupo principal, a remoção externa só é permitida após consultar participantes atuais e identificar exatamente um participante, o próprio número. A operação sai desse grupo e apaga o chat no WhatsApp do proprietário. Se houver outro participante ou não for possível verificar, a confirmação oferece somente exclusão local. O grupo pessoal cadastrado é desvinculado quando removido, para permitir criar outro depois.

Mensagens em processamento impedem a exclusão até terminar. Pedidos ainda em fila são cancelados. O histórico local deixa de ser listado ou consultado, trechos usados como contexto de outras conversas são removidos e a sessão Hermes entra em uma limpeza persistida no PostgreSQL (tentativas em segundo plano). Identificadores técnicos ficam como tombstone para preservar referências e recibos financeiros; não dão acesso ao histórico apagado. Memórias pessoais, lançamentos financeiros, agenda e demais registros não são apagados por esse comando. Falha de conexão na remoção do grupo não apaga silenciosamente o histórico da Ayla; o usuário recebe a falha para conferir e repetir a ação pelo painel.

### Exclusão e recuperação de respostas

GET /api/v1/chat/jobs e consultas/ack de jobs validam o contato com WhatsappAccess.inspect: não criam conversa, nem recuperam um histórico apagado. Apenas o POST de mensagem nova pode criar um novo histórico. Ao apagar, history_reset_at muda no contato; lotes recebidos antes dessa exclusão são rejeitados, mesmo que ainda estivessem aguardando no buffer. O Node envia X-Ayla-WhatsApp-Generation no pedido. A listagem no frontend também descarta respostas antigas de rede referentes a IDs já apagados nessa sessão. A limpeza de sessão Hermes continua pela fila de limpeza.

Quando um lembrete real vence e há WhatsApp ativo, o envio usa a conversa principal configurada; se não houver, cria/reutiliza a conversa pessoal Ayla pelo mesmo fluxo do botão da aplicação. Não convida outros números. Isso é uma nova ação autorizada pelo lembrete, não recuperação de conversa apagada. Sem WhatsApp ativo, ou se a conexão cair antes do envio, o lembrete é entregue somente no app (FRONT_ONLY), sem criar grupo nem enviar o aviso antigo quando reconectar. Falha na criação do grupo com WhatsApp ainda conectado mantém a tentativa pendente.
