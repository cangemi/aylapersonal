# Testes da integração Hermes

Fundo do login (25/10/2026): build e três testes Playwright passaram com `BackLogin.png` importado pelo Vite. A verificação aguarda a decodificação do fundo e confirma ausência das imagens antigas dentro do login. Capturas desktop/celular foram inspecionadas; os cartões são elementos HTML sobre a cena e o login, recuperação e cadastro continuam com os mesmos endpoints. Nenhuma alteração de dados pessoais durante a validação.

Histórico na página de conversa (25/10/2026): `ConversationHistoryTest` (2) e `HermesApiTest` (9) passaram. O contrato HTTP nativo foi exercitado com mensagens simuladas, incluindo filtragem de sistema/ferramentas/raciocínio, ocultação de payload de imagem e análise interna, paginação por linhas originais, 401/404 entre usuários, limite inválido e sessão ainda não iniciada. Três testes Playwright passaram; o novo comprova histórico após reload, páginas anteriores sem duplicatas e separação ao trocar de conversa. A leitura real do Hermes confirmou 3 sessões com histórico e 3 ainda não iniciadas entre os 6 cadastros atuais, imprimindo somente contagens. Sem chamadas à IA nem alteração de mensagens/dados pessoais. Build Vue passou.

Responsividade e imagens (25/10/2026): os dois testes Playwright cobrem desktop, 320/390/768/844 px, navegação por gaveta, formulários sem transbordamento, cartões financeiros com ações de 44 px, voz e chat em altura reduzida. Foram conferidas capturas de login, painel e chat com os novos arquivos, incluindo os temas claro/escuro. O teste valida carregamento real das imagens locais; somente as respostas da API são simuladas. Nenhum dado pessoal é alterado. O build Vue passou. A preferência por movimento reduzido também é respeitada no gráfico para acessibilidade e capturas estáveis.

Troca de parâmetros na prévia: o teste Playwright agora gera uma prévia, muda de Francisca para Antônio e verifica que o áudio anterior desaparece. Ao gerar novamente, confere voice/speed/pitchHz enviados, novo endereço do player e rótulo dos parâmetros, sem nenhum PUT de salvamento. Os dois testes de navegador e o build passaram. Comparação real com a frase fixa e duas configurações distintas retornou arquivos diferentes (43920 e 26928 bytes), sem salvar nem enviar ao WhatsApp.

Prévia de voz sem salvar (24/10/2026): os três testes `VoiceSettingsTest` passaram, cobrindo validação HTTP, uso exclusivo de parâmetros temporários sem acesso ao banco e contrato nativo sem PUT de configuração. Os dois testes Playwright passaram; a regressão usa o botão “Ouvir sem salvar” e verifica envio dos ajustes sem PUT `/hermes/voice`. Build Vue passou. Prévia real com frase genérica fixa retornou 30816 bytes de MP3; o hash do arquivo de configuração do Hermes permaneceu idêntico. Nenhum dado pessoal ou financeiro foi usado e nenhum áudio foi enviado ao WhatsApp.

Principal e chamadas pontuais (24/10/2026): 32 testes Java focados passaram em `ConversationContextTests` (1), `WhatsappDatabaseTests` (4), `PersonalMemoryDatabaseTests` (3) e `HermesIntegrationTest` (24). O teste novo usa PostgreSQL isolado para provar busca de outro contexto somente pela principal, isolamento de proprietários, bloqueio de save/search/delete de memória nas demais e revogação ao trocar o modo. O teste Node executado pela suíte Java comprova uma única principal e desvinculação da sessão antiga. O contrato HTTP simulado verifica que a memória não é injetada nas chamadas e inclui as instruções de identidade/autoria. Node: 27 testes locais passaram; 1 integração PostgreSQL é executada pela suíte Java. Python: 13 testes simulados passaram, incluindo registro/encaminhamento da ferramenta `conversation_context_search` com sessão nativa. Build Vue passou. Nenhuma chamada real à IA nem mensagem real foi enviada nessa validação.

Autenticação: `AuthDatabaseTests` tem cinco testes com a cadeia real do Spring Security e PostgreSQL isolado para rotas protegidas, setup único, dados existentes do proprietário, criação/isolamento de usuário, permissões globais, expiração, rotação e replay de refresh, logout, troca de senha e bloqueio persistido de tentativas. Verifica também o escopo separado das chaves do Hermes/WhatsApp, hashes no banco e DTOs com credenciais ocultas. Os testes HTTP antigos agora fornecem Principal explícito, sem modo pessoal anônimo. Consulte [AUTENTICACAO.md](AUTENTICACAO.md).

Recuperação de senha: os 12 testes focados de `AuthDatabaseTests`, `WhatsappDatabaseTests` e `WhatsappApiTest` passaram. Cobrem senha pendente com hash, preservação da anterior até o primeiro uso, expiração, revogação das sessões, resposta genérica, limites, falha de entrega, vínculo do número e confirmação com senha atual ao trocar o canal. O gateway é validado com HTTP simulado, sem envio real. No Node, 27 testes passaram e um teste PostgreSQL foi executado pela suíte Java acima, em vez da execução isolada. O teste novo verifica autorização do endpoint privado, destinatário obrigatório, deduplicação, ausência do segredo no banco e bloqueio de eco para a IA. O contexto nativo também exclui mensagens de recuperação e citações sensíveis. A sintaxe Node passou em `npm run check`. Nenhuma mensagem real de recuperação ou inferência foi usada nessa validação.

Identificador fixo e proprietário exclusivo: testes Node verificam normalização de `Ayla0101`/`ayla0101`/maiúsculas e espaços, comandos do dono e bloqueio de mensagens normais, comandos, invocações, anexos e identidade inválida de terceiros em grupos e conversas individuais, inclusive no modo automático. Nesse bloqueio não há resposta, leitura de contexto, download de mídia, reação ou chamada à IA.

Chamadas em outras conversas: testes Node validam alias exato e contagens, contexto nativo limitado ao chat/instante solicitados, prioridade à citação, supressão de eventos históricos, invocação somente pelo proprietário, modo persistido `mentionOnly` e download apenas da foto/documento selecionado. O teste PostgreSQL existente verifica a persistência desse modo e é executado por `WhatsappDatabaseTests`. `DocumentTextTest` gera PDF e DOCX sintéticos localmente, valida extração, documentos inválidos e páginas de PDF sem texto limitadas para visão. Os testes HTTP verificam o campo de documento/citação e a separação entre referências e o pedido atual; a política de voz distingue áudio citado de áudio recebido. Não fazem chamadas reais à IA nem enviam mensagens a contatos.

Reação de processamento: o teste de agrupamento verifica ausência de ⏳ durante a espera, reação na última mensagem ao iniciar a IA, permanência até o envio e remoção ao terminar. Um teste adicional cobre falha da IA, falha no envio, indisponibilidade de reações, ausência de loops e limpeza antes de desconectar. Usa mocks, sem enviar mensagens ou reações reais no WhatsApp.

Despensa/lista: `HouseholdDatabaseTests` reúne três testes com PostgreSQL real para CRUD HTTP, versões, estoque exato, conversão de unidades, idempotência, compra com reposição atômica, geração de faltantes sem duplicar, itens sem quantidade, isolamento e lote do agente com rollback. O mesmo teste HTTP cobre CORS permitido/bloqueado. Um teste Python valida registro/transporte das ferramentas, sessão nativa, rejeição de identidade do modelo e falha de acesso sem confirmação falsa. Consulte [DESPENSA.md](DESPENSA.md).

Memória pessoal: `PersonalMemoryDatabaseTests` tem três testes com PostgreSQL real para RAG entre conversas, correções por atributo, evidência visual versus fala, rejeição atômica de fatos sem origem, isolamento por usuário, expiração e esquecimento. `HermesIntegrationTest` verifica que o contexto recuperado para o usuário real chega ao chat sem alterar a pergunta. Dois testes Python verificam registro e transporte das ferramentas de memória, sessão nativa e rejeição de identidade fornecida pelo modelo. As suítes usam dados sintéticos, sem inferência paga. Consulte [MEMORIA.md](MEMORIA.md) para limites e rotas.

Execute `.\mvnw.cmd clean test` para compilar sem classes antigas e executar a suíte
completa, incluindo Spring, migrações Flyway e PostgreSQL/Redis/RabbitMQ isolados
via Testcontainers. Docker precisa estar ativo.

Para somente os testes locais, use `.\mvnw.cmd -Punit-tests test`.

Os testes HTTP usam servidor local temporário e credenciais fictícias. Cobrem:
- conversa, retorno de links e continuidade de sessão;
- escolha gratuita, rejeição de preços desconhecidos/pendentes e modelos pagos;
- revalidação do modelo antes do turno e bloqueio de fallback;
- persistência de configuração apenas depois da validação do catálogo;
- falhas HTTP, limite 429 e falha do agente mesmo com HTTP 200;
- contratos e validações dos endpoints Java, incluindo status sem modelo pronto.
- conversa atual persistida por usuário e alternância entre conversas;
- isolamento por proprietário, identificador vindo do Principal e validação de perfil Hermes;
- credenciais e rotas separadas por perfil, sem fallback para a chave do proprietário;
- primeira mensagem concorrente em PostgreSQL e rotação da sessão Hermes mantendo conversationId.

Não consomem inferência ou pesquisa externas. Validação de ponta a ponta com
resposta de IA real exige login Nous no contêiner; veja HERMES.md.

O fluxo nativo de pesquisa usa testes Python com decisões estruturadas simuladas, sem API externa. Eles cobrem contexto, assuntos desconhecidos sem palavras-chave, conversa casual, privacidade, respeito a restrições de pesquisa, limite de tentativas, refinamento de consultas, evidência inconclusiva e bloqueio de modelos sem preço gratuito confirmado. Para executá-los no Python disponível no contêiner:

```powershell
docker cp docker/hermes ayla-hermes:/tmp/ayla-hermes-tests
docker exec -u hermes ayla-hermes python -m unittest discover -s /tmp/ayla-hermes-tests/tests -v
```

Foi validada também uma busca real pela API Java: o plugin retornou cinco
resultados, persistiu as fontes no contexto do turno e a resposta trouxe links.

Validação da versão semântica: 25 testes Python e 25 testes Java passaram. No
Hermes real, uma pergunta sobre mudanças recentes acionou duas consultas e foi
marcada como inconclusiva; uma conversa casual com a palavra hoje não acionou
pesquisa. As avaliações usaram o modelo Nous gratuito selecionado.

## Financeiro

`FinanceDatabaseTests` valida CRUD, isolamento, aritmética de centavos inteiros, fuso/dias, saldo histórico, exclusão de lançamentos futuros do saldo atual, agrupamentos, versões e idempotência concorrente em PostgreSQL isolado. `FinanceApiTest` valida contratos HTTP, identidade e proteção da ponte Hermes. São 47 testes Java no total nesta implementação; o perfil `unit-tests` exclui os dois testes de contexto com Docker.

`docker/hermes/tests/test_finance.py` verifica o transporte HTTP, registro das ferramentas, sessão nativa, chave por turno/item, erros e configuração. Junto dos testes da pesquisa são 33 testes Python sem custo de IA:

```powershell
docker cp docker/hermes ayla-hermes:/tmp/ayla-finance-validation
docker exec -u hermes ayla-hermes python -m unittest discover -s /tmp/ayla-finance-validation/tests -v
```

Também foi validado o fluxo real com o Hermes/modelo gratuito e API na porta 8081 usando banco isolado: a mensagem com salário de R$ 1.000,00 e almoço de R$ 35,90 gerou exatamente dois lançamentos e saldo de R$ 964,10. Em outra conversa sem valores no histórico, a pergunta sobre saldo/gastos do dia recuperou R$ 964,10/R$ 35,90 do banco e preservou a quantidade de registros.

Regressão de contexto: os testes de pesquisa garantem que uma conversa normal não executa busca antecipada baseada em assuntos antigos. Os testes financeiros atuais estão descritos abaixo; os antigos testes do planejador e parser foram substituídos.

Centavos: o teste de migração executa V1–V5 em schema isolado, insere valores antigos de 15.50 e 0.15 reais e aplica V6, verificando 1550 e 15 em BIGINT. As verificações existentes agora usam long para saldo, gráficos, filtros, versões e idempotência; também rejeitam 3590.0, 0.001 e strings no JSON, e validam o erro 422 quando os totais excedem o limite. O teste do agente verifica 50 centavos como 50 e quinze reais e cinquenta como 1550, usando argumentos estruturados simulados. A apresentação verifica centavos e saldo negativo sem float. Nenhum desses testes consome inferência externa.
Ferramentas financeiras simples: o teste HTTP garante que “hj eu gastei 50” segue ao Hermes, com instruções para esclarecer o pedido e consultar valores atuais. Um teste PostgreSQL cobre dados obrigatórios ausentes, conversão exata de 12 centavos e 15,50 reais, repetição idempotente, receitas/despesas externas ao chat incluídas nas consultas e edição parcial preservando descrição/data. A ponte HTTP valida record/update/delete por usuário da sessão. O plugin expõe seis ferramentas, incluindo finance_record com argumentos diretos, rejeita parâmetros antigos com diagnóstico e retorna dinheiro formatado sem duplicar valores brutos/formatados. Os testes automatizados não chamam modelos externos. Suíte: 47 testes Java e 35 Python.
Validação real do fluxo simplificado: com Hermes e modelo gratuito, API temporária 8081 e PostgreSQL do Testcontainers, “hj eu gastei 12” perguntou unidade/descrição sem gravar; “foram centavos, comprei uma bala” gerou exatamente uma despesa de 12 centavos. Uma despesa externa de 250 centavos pelo CRUD apareceu na consulta: com receita de 100000 centavos, a IA respondeu saldo R$ 997,38 e gastos R$ 2,62. Edição para 15 centavos e exclusão foram conferidas no banco isolado. Cadastro/edição/exclusão retornam também summary calculado pelo Java para evitar que a IA recalcule saldos pelo histórico. A conferência final de exclusão retornou o valor correto de R$ 1.000,00, descrito pela resposta como recebido sem gastos. Nenhum lançamento de teste foi gravado no banco pessoal.
Regressão da sessão repetitiva: dois testes do seletor de contexto verificam que avisos sintéticos antigos, respostas financeiras antigas e resultados de ferramentas anteriores não substituem a pergunta atual. Preservam o histórico original, esclarecimentos e todas as chamadas/resultados atuais de cadastro, consulta, edição e exclusão. Total Python: 35 testes, sem inferência nesses testes. Validação real adicional foi feita na própria sessão antiga: consultou o PostgreSQL para hoje (sem gastos em 23/10) e para 22/10 (R$ 95,00), sem modificar lançamentos. A sessão e o histórico arquivado foram mantidos.
Cadastro imediato: testes HTTP adicionais verificam a preferência automática pelo modelo gratuito validado, preservação da escolha manual e bloqueio de opção paga. Uma confirmação de cadastro sem recibo real provoca no máximo uma correção automática no mesmo turno; se continuar sem gravação, não é apresentada como sucesso. O teste PostgreSQL verifica o recibo antes/depois do cadastro. A ferramenta exige unidade estruturada válida e rejeita descrição contendo apenas um valor monetário.

Na validação real, Solar Mini4 fez perguntas redundantes e chegou a afirmar que salvou sem chamar ferramenta. O modelo Step 3.7 Flash, com gratuidade confirmada no catálogo local, gravou imediatamente “Comprei um cartão de 20 centavos” com descrição Cartão e amountCents=20 no banco isolado, sem confirmação extra. A escolha automática agora o prioriza; seleção manual e validação de gratuidade por requisição continuam disponíveis. Não foram gravados dados de teste no banco pessoal.
Regressão de confirmação de unidade: em PostgreSQL isolado, “Comprei uma caneta por 30” seguido de “isso mesmo” permite cadastrar os 30 centavos interpretados pela IA sem exigir a repetição literal da palavra centavos. O teste confere valor de 30, recibo do turno e idempotência (exatamente um registro). Campos obrigatórios ausentes continuam sendo rejeitados nos testes existentes. Essa regressão cobre o erro 400 visto nas tentativas reais da caneta.
## WhatsApp

Validação da integração: **51 testes Java passaram**, incluindo quatro testes novos de WhatsApp (HTTP/identidade e PostgreSQL). Os cinco testes locais Node passaram; o sexto teste Node, de persistência real, passou dentro do banco isolado criado por `WhatsappDatabaseTests`. Nenhum teste enviou mensagens reais ou consumiu IA.

O teste de PostgreSQL verifica CRUD de contatos, atualização/reativação, bloqueio de mensagem duplicada, criptografia da sessão, restauração e rejeição de chave/caminho incorretos. A migração V9 foi aplicada ao banco do aplicativo. A imagem Docker foi construída, o contêiner ficou healthy e o Chromium gerou QR real em texto, imagem e ASCII. O pareamento e o envio real dependem de escanear o QR com o celular.

A execução `npm audit fix --ignore-scripts` aplicou atualizações compatíveis, removendo os avisos críticos. Permanecem nove avisos altos na árvore transitiva do Puppeteer (incluindo ferramentas de download/extração de navegador). A sugestão automática restante exige trocar a versão da biblioteca WhatsApp; não foi aplicada atualização forçada. O contêiner usa Chromium instalado pelo sistema e não baixa navegador pelo Puppeteer.

Filtro de mensagens novas: seis testes locais Node passaram (mais o teste PostgreSQL, não repetido nesta alteração). A regressão adicional cobre sincronização inicial, histórico entregue após ready, comandos antigos, data inválida, fila de conexão anterior e reconexão de rede. Histórico/offline não chega à IA nem altera autorizações. O segundo da conexão é excluído devido à precisão de segundos da data do WhatsApp.

Mensagens do próprio dono: oito testes locais Node passaram, incluindo autorização de grupo pelo dono, mensagens do dono e de participantes no grupo, conversa consigo mesmo e bloqueio de eco da Ayla. Uma regressão da assinatura ao vivo simula add/change sem isNewMsg, verifica instalação única, ausência de replay e rejeição de comando antigo antes de autorizar um comando novo. O teste PostgreSQL não foi repetido; a alteração não muda o schema ou os comandos SQL.

Compatibilidade dos identificadores: na sessão real, WhatsApp entrega Message.id com fromMe/remote/id/participant, sem _serialized. O descarte silencioso foi corrigido com chave canônica derivada desses campos tanto na captura padrão quanto na complementar. A regressão usa esse formato e verifica deduplicação entre os dois eventos. Oito testes Node e dois HTTP Java passaram. Mensagens históricas não foram reprocessadas nem enviadas em testes reais. Erros HTTP agora identificam método/rota sem registrar corpo, telefone ou credenciais. Swagger inclui exemplos concretos para evitar corpo additionalProp1 no cadastro.

Falha r na autorização de grupo: o diagnóstico na sessão real reproduziu DataError (IDBObjectStore.get sem chave) na serialização de metadados do grupo por getChat/getChatModel. A consulta do chat bruto, com getAsModel:false, funcionou. A autorização agora depende só do ID da conversa, usa o nome já carregado quando disponível e tolera falhas nessa leitura. O atendimento não chama getChat nem bloqueia a IA por falhas de indicador de digitação. O envio usa sendSeen:false para não marcar conversas como lidas. O teste de grupos simula DataError e comprova autorização e atendimento sem getChat; os logs de falha incluem etapa e mensagem sanitizada. Nenhum comando histórico foi reprocessado ou enviado pela validação.

Agrupamento de mensagens: dez testes locais Node passaram. Dois testes novos usam relógio controlado, sem esperar dez segundos reais ou chamar IA: reinício do prazo, concatenação ordenada, uma chamada por lote, mensagens durante inferência, indicador de digitando/stop, isolamento por conversa, duplicatas, cancelamento ao desativar/desconectar e falha opcional de presença. O teste de PostgreSQL não foi repetido nesta alteração. O envio de digitando usa sendChatstate diretamente, sem getChat/getChatModel, e é renovado a cada 20 segundos durante a chamada de IA.

Imagens do WhatsApp: 11 testes Node, 30 testes Java selecionados (HermesIntegrationTest, HermesApiTest e WhatsappApiTest) e 36 Python passaram. O teste Node novo cobre foto sem legenda, complemento por texto, agrupamento, deduplicação, mídia parcial, autorização, histórico e falha de download. O transporte Java preserva as imagens no vínculo confiável do WhatsApp, envia partes image_url nativas, rejeita MIME/base64/assinatura/tamanho inválidos e não usa visão quando o catálogo não confirma gratuidade. O teste Python mantém imagens de esclarecimentos como contexto antigo, sem novo pedido de cadastro.

Também houve teste visual REAL no Hermes: imagem sintética com retângulo azul e texto TESTE VISUAL. O catálogo confirmou Step 3.7 Flash gratuito antes da chamada; a resposta reconheceu corretamente cor e texto. A sessão temporária foi removida. Esse teste não enviou mensagens reais no WhatsApp nem criou lançamentos financeiros. O registro de uma nota real pelo WhatsApp ainda depende de envio pelo usuário; a gravação usa as ferramentas financeiras existentes e a validação de recibo do turno. Não foram executados testes de banco adicionais nesta alteração.

Correção de download de imagem: reproduzida a falha real InvalidMediaFileType (application/octet-stream) na foto do usuário. A biblioteca 1.34.7 omite mimetype ao chamar DownloadManager. O novo download nativo informa esse campo explicitamente, preserva o identificador canônico $1, limita o tamanho e valida assinatura JPEG/PNG/WebP após a descriptografia. Não consulta histórico: busca apenas o modelo da mensagem entregue pelo evento. Captura o erro dentro do navegador para evitar a perda de informação como t:t. Falhas anteriores à IA agora pedem reenvio e informam que não houve lançamento a partir da imagem.

Validação: 13 testes Node passaram (mais o teste PostgreSQL, não repetido). Dois testes novos cobrem contrato do MIME, identificador completo, preparação da mídia, descriptografia, bytes inválidos e mensagem de falha apropriada. O MESMO código final foi executado na foto real que falhou: retornou JPEG válido com 99324 bytes. Foi somente diagnóstico de download, sem chamada à IA, envio de mensagem ou gravação financeira. Referência primária: https://github.com/wwebjs/whatsapp-web.js/issues/201908 .

Áudio WhatsApp e descarte de mídia (23/10/2026):
- Testes focados Java: HermesIntegrationTest (21), HermesMediaTest (2), WhatsappDatabaseTests (2), todos passaram. A migração V10 foi aplicada em PostgreSQL isolado; o teste Node de persistência/RemoteAuth também passou nesse banco.
- Node: 15 testes passaram; integração PostgreSQL é omitida na execução isolada e executada pelo teste Java acima.
- Casos novos: download de voz Ogg/Opus preserva MIME; mensagens novas autorizadas agrupadas por dez segundos; transcrição chega ao agente e ao contexto financeiro apenas como texto; autenticação por cookie no painel; exclusão restrita ao arquivo exato, sem recursão; falha de limpeza mantém pendência para repetir; imagem sai da sessão temporária e apenas a análise textual entra na conversa durável.
- Verificação real sem inferência paga: áudio público de teste retornou HTTP 200, provider local, transcrição não vazia. Nenhum arquivo hermes-desktop-voice-* permaneceu em /tmp após a conclusão. Whisper instalado via extra oficial stt-whisper, modelo base/CPU/int8/pt.
- Exclusão real: endpoint nativo DELETE /api/files retornou 200 e o arquivo sintético identificado no cache deixou de existir; nenhum arquivo pessoal foi usado nesse teste.

Correção de autenticação de áudio (23/10/2026):
- Causa reproduzida com JDK real: tentativa HTTP/2 cleartext upgrade no login do painel retorna 400; as mesmas credenciais usando HTTP/1.1 retornam 200.
- HermesDashboardClient agora fixa HTTP/1.1. O teste de contrato existente garante ausência do header Upgrade e protocolo HTTP/1.1, além do cookie de sessão.
- Validação com a classe Java real contra o Hermes local: áudio público JFK/Whisper transcrito com sucesso, sem chamada ao agente ou registro financeiro.
- Node: 16 testes passaram; 1 integração PostgreSQL omitida nessa execução isolada. Dois testes Java de mídia passaram. Falhas explícitas de transcrição agora pedem reenvio/texto no WhatsApp; erros de resultado financeiro incerto mantêm o comportamento anterior.

Resposta por voz gratuita (23/10/2026):
- Java: 27 testes focados passaram (HermesIntegrationTest 21, HermesMediaTest 2, AudioReplyPolicyTest 2, WhatsappApiTest 2). Foram adicionados só dois testes de política/falha e ajustados os contratos existentes.
- Node: 17 testes passaram; 1 teste PostgreSQL omitido na execução isolada. Novo teste cobre entrega de texto + áudio, eco anterior ao retorno do envio e falha de transporte de voz sem repetir IA/financeiro.
- Edge TTS 7.2.7 da imagem original falhou na síntese real com 403. Atualização oficial 7.3.1 pelo uv mudou somente edge-tts no lock e resolveu a falha.
- Síntese real via endpoint nativo: provider=edge, audio/ogg válido com assinatura OggS, 43.662 bytes, aproximadamente 3,7 segundos para a frase sintética de apresentação.
- Classe Java real HermesDashboardClient.speak validada contra Hermes: audio/ogg, 26.223 bytes, assinatura OggS. Nenhuma inferência paga nem operação financeira foi executada nesses testes reais.
- Voz padrão pt-BR-FranciscaNeural, ritmo 1.02 e pitch +2 Hz. Naturalidade e percepção de idade são subjetivas; não foi afirmado que houve teste auditivo comparativo nem clonagem.

Configuração de voz: `VoiceSettingsTest` tem dois testes para GET/PUT/listagem e validação dos parâmetros, além do contrato HTTP real com Hermes simulado (cookie, perfil, atualização parcial e bloqueio da síntese se a configuração falhar). `WhatsappDatabaseTests.voiceSettingsSurviveNewServiceInstancesAndDriveSynthesis` verifica a migração V12, persistência real em PostgreSQL e uso do ajuste salvo na síntese. A prévia real foi validada com uma frase genérica fixa, sem dados pessoais, sem envio ao WhatsApp e sem inferência: áudio Ogg válido retornado pelo endpoint Java.

Correção de entrega de voz no WhatsApp (23/10/2026):
- Falha real reproduzida no navegador: `Data passed to getter must include an id property`. A mídia incluía `__x_id` antigo, ocultando o identificador criado para a mensagem. A correção documentada em whatsapp-web.js #201921/#201923 é aplicada de forma guardada por `patchWhatsapp.js` no postinstall, também no Docker; não depende de editar node_modules manualmente após cada instalação.
- Envio de voz agora passa pelo adaptador nativo sem legenda PTT e sem serializar o modelo completo. O cabeçalho continua no texto. Erros do navegador são capturados com estágio e detalhe legíveis, sem bytes de mídia nem credenciais.
- Segunda incompatibilidade reproduzida: o SDK enviava a voz e retornava undefined, pois buscava `_serialized` enquanto a versão atual usa `$1`. O adaptador confirma o identificador somente da nova voz correspondente ao hash, conversa e remetente, excluindo mensagens existentes antes do envio.
- Validação real: uma mensagem de voz de apresentação foi enviada à conversa autorizada do pedido e confirmada como PTT com ack >= 1. Seu ID foi persistido para deduplicação. A confirmação posterior não reenviou a mensagem. Nenhuma operação financeira foi executada.
- Java: 23 testes focados passaram (HermesIntegrationTest 21, AudioReplyPolicyTest 2). Node: 19 passaram, 1 integração PostgreSQL omitida na execução isolada. Dois testes focados do adaptador verificam envio sem legenda/serialização completa, erro legível e recuperação somente do novo ID quando o SDK retorna vazio.
- SOUL.md e instruções Java agora declaram a capacidade real de voz e substituem negações antigas do histórico; pedidos de voz recebem instrução para produzir diretamente a fala, sem indicar outro aplicativo.

Pronúncia de dinheiro e ajuste da mesma voz (23/10/2026):
- 25 testes Java focados passaram: SpeechTextTest (2), AudioReplyPolicyTest (2), HermesIntegrationTest (21). Mais uma execução focada dos dois testes SpeechTextTest validou a integração com o adaptador TTS mockado, sem enviar valores ao serviço externo.
- Casos de dinheiro cobrem centavos, singular, zero, negativos, milhares, valores grandes com centavos finais preservados, formatos inequívocos brasileiro/internacional e manutenção de moedas estrangeiras/entradas ambíguas. O texto original não é alterado.
- Teste real de timbre usando apenas saudação genérica: Edge retornou audio/ogg válido com assinatura OggS (38.284 bytes). Uma verificação local do adaptador confirmou voz pt-BR-FranciscaNeural, pitch +10Hz e rate +5%. Nenhum arquivo tts_* permaneceu no cache após o teste.
- A revisão automática rejeitou o teste externo planejado com valores monetários, por tratá-los como dados financeiros pessoais enviados ao Edge. Esse teste não foi executado. A alternativa segura validou a conversão monetária localmente com mock e o timbre real com uma saudação sem dados pessoais ou financeiros.
- Pronúncia de nomes é configurável, mas a pronúncia pessoal de Cangemi aguarda informação do usuário. Nenhum nome/valor foi enviado a serviço externo durante essas verificações finais.
- Inicialização completa do Spring com ICU4J também validada em PostgreSQL isolado: WhatsappDatabaseTests (2) passou. Total: 27 testes Java relevantes, sem falhas.

Áudio sem transcrição repetida, voz espontânea e personalidade (23/10/2026):
- Node: 20 testes passaram; 1 integração PostgreSQL omitida na execução isolada e executada pela suíte Java. Testes cobrem somente cabeçalho + voz no sucesso, texto completo como alternativa na falha e eco imediato de respostas sem cabeçalho, sem nova chamada à IA.
- Java: 27 testes relevantes passaram (HermesIntegrationTest 22, AudioReplyPolicyTest 2, WhatsappDatabaseTests 3). Dois testes adicionados verificam escolha pela IA, exclusão de financeiro/recibo e pedidos de texto, marcador não exposto, cooldown, reserva única e persistência/escopo do usuário no banco isolado.
- Runtime: migração V11 aplicada no PostgreSQL da aplicação; health da API Java retornou 200; Hermes e WhatsApp saudáveis após publicação. Não foram enviados novos comentários a contatos durante a verificação.
- Timbre: mesma Francisca, pitch +14Hz e ritmo +8%. Síntese real apenas de saudação genérica retornou audio/ogg válido (38.311 bytes), sem dados pessoais ou financeiros. A conversão monetária anterior continua ativa.
