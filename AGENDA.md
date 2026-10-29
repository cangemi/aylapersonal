# Agenda pessoal, Google e lembretes

A agenda própria funciona sem Google. Compromissos e lembretes ficam no PostgreSQL e pertencem ao usuário autenticado. App e ferramentas da Ayla usam o mesmo serviço Java. A migração V32 cria as tabelas; não altera lançamentos financeiros nem históricos de conversa.

Na interface, abra **Agenda** pelo menu, barra inferior ou sino. Crie/edite um compromisso e escolha até cinco lembretes em minutos, horas ou dias (máximo 28 dias antes). Remover os lembretes mantém o compromisso; cancelar o compromisso cancela seus avisos. Datas relativas da Ayla usam America/Sao_Paulo. O formulário permite escolher outros fusos. Na conversa, se faltar término ou duração, a Ayla pergunta antes de salvar. Horários coloquiais entre 1 e 12 sem período explícito exigem esclarecer manhã/tarde/noite. A confirmação apresenta início e fim em 24 horas.

Exemplos de conversa:

- “Marque dentista amanhã às 15h e me lembre duas horas antes.”
- “O que tenho marcado na sexta?”
- “Passe o dentista para segunda às 10h.”
- “Me lembre também um dia antes.”
- “Tire os lembretes do dentista.”
- “Cancele o dentista.”
- “Quero conectar minha agenda Google.”

A Ayla consulta os registros antes de editar/cancelar e pergunta qual compromisso quando houver ambiguidade. Pede só os dados faltantes; não inventa horário nem antecedência. Referências citadas/Google são dados, nunca instruções ou autorização para executar ações. As ferramentas não usam memória como fonte de horários ou de confirmação de gravação.

## Lembretes e disponibilidade

O Java verifica vencimentos a cada 15 segundos, em um scheduler próprio com duas threads. O aviso aparece no app (sino consulta a cada 30 segundos e a agenda a cada minuto). Esses avisos não são notificações push do sistema operacional: o app precisa estar aberto para atualizar a tela. Para avisar com a tela fechada, o serviço envia texto à conversa principal habilitada do WhatsApp do próprio usuário, com o cabeçalho da Ayla, sem nova chamada à IA. Não envia a conversas secundárias.

A API, PostgreSQL e, para WhatsApp, o agentNode precisam permanecer ligados. Avisos pendentes sobrevivem a reinícios. Falhas anteriores ao envio, como desconexão ou ausência de conversa principal, tentam novamente aproximadamente a cada minuto até o início do compromisso. Depois disso o aviso permanece no app, sem enviar mensagens antigas pelo WhatsApp. Reiniciar o sistema não duplica os avisos já confirmados. Editar apenas título/local preserva os lembretes já entregues; remarcar gera avisos para o novo horário.

Um envio externo pode ter sido aceito pelo WhatsApp antes de uma queda. Nessa situação, o estado é UNCERTAIN e o sistema não reenvia automaticamente para evitar duplicação; o aviso continua no app com entrega não confirmada. Estados SENDING abandonados passam a UNCERTAIN após cinco minutos. Não há garantia de entrega de WhatsApp se o serviço/rede estiver indisponível; o banco mantém o aviso.

## Preparar a conexão Google

1. Crie um projeto no Google Cloud e habilite **Google Calendar API**.
2. Configure a tela de consentimento OAuth, público e usuários de teste, conforme as regras da sua conta/projeto.
3. Crie um cliente OAuth do tipo **Aplicativo da Web**.
4. Para autorizar neste computador, registre exatamente o redirect URI abaixo. A configuração inicial do código usa localhost; defina explicitamente o endereço 127.0.0.1 abaixo ao cadastrar o cliente e no .env. Isso evita o conflito de localhost/IPv6 existente neste ambiente.

```text
http://127.0.0.1:3000/api/v1/calendar/google/callback
```

5. Configure localmente no `.env` (não envie o client secret no chat):

```dotenv
AYLA_GOOGLE_CLIENT_ID=seu-client-id
AYLA_GOOGLE_CLIENT_SECRET=seu-client-secret
AYLA_GOOGLE_REDIRECT_URI=http://127.0.0.1:3000/api/v1/calendar/google/callback
AYLA_CALENDAR_ENCRYPTION_KEY=base64-de-32-bytes-aleatorios
```

A chave de criptografia foi preparada no `.env` deste ambiente sem exibir seu valor. Preserve-a em backups junto da configuração: trocar a chave exige reconectar o Google. Para gerar uma chave em outro ambiente, use `[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))` em PowerShell moderno ou `openssl rand -base64 32` no Linux. O valor é configuração secreta e não deve ser versionado.

6. Reinicie Java com `observability-local` no desenvolvimento, ou recrie as réplicas de API na VPS. O Compose VPS já repassa essas variáveis.
7. Entre com seu usuário no app, abra **Agenda > Conectar Google** e autorize sua conta. É OAuth de agenda, separado do login da Ayla. A Ayla também pode gerar o mesmo link na conversa; ela não recebe sua senha Google.

Para autorizar pelo celular/VPS, use um domínio HTTPS confiável e registre `https://seu-dominio/api/v1/calendar/google/callback`. O Google não aceita IP LAN bruto como redirect URI de cliente Web; localhost só serve no aparelho em que o servidor está rodando. É possível conectar pelo computador e depois usar a sincronização normalmente no celular com o mesmo usuário da Ayla. O redirect configurado também determina a origem da página de retorno; faça login nesse endereço antes de conectar, para evitar troca de origem da sessão local.

Os escopos são `calendar.events` e `calendar.readonly`: permitem alterar eventos e ler os metadados da agenda. A implementação utiliza somente a agenda principal da conta autorizada. O refresh token é criptografado com AES-256-GCM, com o ID do usuário como dado autenticado, e nunca é retornado ao frontend ou à IA. State de OAuth é aleatório, de uso único, com validade de dez minutos. Reautorizar com outra agenda principal copia os compromissos locais para a nova conexão; agendas de usuários da Ayla permanecem separadas. Desconectar elimina a credencial local e para a sincronização, preservando os compromissos. Para revogar também a autorização concedida ao aplicativo no Google, use a área de segurança da própria conta Google.

Em projetos externos com status **Testing**, o Google normalmente limita o refresh token a sete dias para esses escopos. Para uso contínuo, siga as exigências de publicação/verificação do Google; não é uma expiração imposta pela Ayla.

Fontes: [OAuth para aplicações Web](https://developers.google.com/identity/protocols/oauth2/web-server), [validades de tokens OAuth](https://developers.google.com/identity/protocols/oauth2), [eventos e lembretes Google](https://developers.google.com/workspace/calendar/api/v3/reference/events).

## Sincronização

Alterações locais são gravadas antes de chamar Google e ficam pendentes no banco até confirmação. O worker verifica conexões elegíveis a cada 15 segundos, normalmente sincroniza cada usuário a cada dois minutos e faz backoff de cinco minutos após falhas. O botão Atualizar e a ferramenta da Ayla antecipam a próxima tentativa. A API não bloqueia o cadastro local por indisponibilidade Google.

A importação usa uma janela móvel de 30 dias anteriores e 730 dias futuros, com paginação e expansão de recorrências Google em ocorrências individuais. Edições/cancelamentos no app afetam a ocorrência selecionada, não a série inteira. Eventos criados na Ayla são enviados mesmo fora dessa janela. Cadastros recorrentes pela Ayla e configuração de múltiplas agendas Google não fazem parte desta versão. Não são criados eventos novos apenas por carregar histórico.

Mudanças no Google são importadas; mudanças locais são exportadas. ETags/If-Match protegem alterações concorrentes. Em conflito de edição, prevalece a alteração com data mais recente; uma exclusão confirmada no Google cancela a cópia local. É necessário manter o relógio do servidor sincronizado. Um ID Google determinístico para novos eventos e recibos de operação local evitam duplicação em tentativas repetidas. Campos que a Ayla não gerencia, como convidados e conferência, não são enviados nos patches de edição. Sessões/perfis Hermes carregam as ferramentas `calendar_*`, inclusive perfis existentes, sem copiar credenciais entre usuários.

Os workers usam PostgreSQL e locks existentes para coordenar réplicas. Não exigem Redis, RabbitMQ, serviços extras ou chamadas periódicas ao modelo. A agenda exibe o estado da sincronização; “salvo localmente” não significa “já publicado no Google”.

## API para outros frontends

Todos os endpoints abaixo exigem o token do usuário, exceto o callback OAuth, protegido por state de uso único.

| Endpoint | Uso |
| --- | --- |
| `GET /api/v1/calendar/events?from=YYYY-MM-DD&to=YYYY-MM-DD&q=texto&page=0&size=50` | Listar compromissos ativos, com dados atuais do banco |
| `GET /api/v1/calendar/events/{id}` | Detalhes e versão atual |
| `POST /api/v1/calendar/events` | Criar; suporta `Idempotency-Key` |
| `PATCH /api/v1/calendar/events/{id}` | Editar; enviar versão e apenas campos a alterar |
| `DELETE /api/v1/calendar/events/{id}?version=N` | Cancelar compromisso e lembretes |
| `GET /api/v1/calendar/reminders?unread=true` | Avisos disponíveis no app |
| `POST /api/v1/calendar/reminders/{id}/read` | Marcar aviso como lido |
| `GET /api/v1/calendar/google` | Disponibilidade, conexão, última sincronização e estado |
| `POST /api/v1/calendar/google/connect` | Gerar link de autorização |
| `GET /api/v1/calendar/google/callback` | Retorno do Google |
| `POST /api/v1/calendar/google/sync` | Solicitar sincronização |
| `DELETE /api/v1/calendar/google` | Desconectar, mantendo compromissos |

Cadastro com horário: `title`, `startsAt` ISO-8601 com offset e `endsAt` ou `durationMinutes` obrigatórios. `description`, `location`, `timeZone`, `allDay` e `reminderMinutes` são opcionais. A API rejeita criação com horário sem término/duração; edições preservam a duração omitida. Dia inteiro mantém término exclusivo, por padrão o dia seguinte. Exemplo de antecedências: `[120,1440]` = duas horas e um dia. `[]` retira todos os lembretes. Uma edição exige `version` e preserva campos omitidos. Para dia inteiro, os limites são normalizados ao início dos dias no fuso do evento, com término exclusivo. Consulte o Swagger para os esquemas.

Validação realizada nesta entrega: compilação Java com testes desativados, build Vue e sintaxe Node/Python. Nenhuma suíte automatizada foi executada nem foram criados compromissos de demonstração ou enviados avisos de teste. A autorização Google precisa ser verificada após configurar as credenciais do seu projeto; não há conta Google conectada automaticamente.

Lembretes com antecedência zero (na hora) aceitam até cinco minutos de tolerância para o intervalo de coleta; as demais tentativas de WhatsApp terminam no início do compromisso.

Inclua a origem do frontend escolhido (por exemplo, http://127.0.0.1:3000 ou https://seu-dominio) na lista AYLA_FRONTEND_ORIGINS, preservando as origens já configuradas. O callback e o login precisam usar endereços consistentes com essa configuração.

### Alteração de duração pela IA

A ferramenta calendar_save recebe id e version no nível principal e os campos editáveis em event. Por compatibilidade, event.version também é aceito; se ambas as posições forem informadas, devem coincidir. Para mudar uma reunião de uma para duas horas, use event.durationMinutes=120, preservando início, título e lembretes. A API pública também aceita durationMinutes no corpo. Caso durationMinutes e endsAt sejam enviados juntos, precisam corresponder; a API rejeita informações contraditórias. Versão ausente é um pedido incompleto (400), e somente divergência com a versão existente é conflito (409).

### Oferta de lembrete durante o agendamento

Após salvar um novo compromisso, a Ayla pergunta se o usuário quer ser lembrado, uma única vez, quando essa escolha ainda não foi informada. A pergunta é obrigatória mesmo que o lembrete não seja. Uma resposta positiva sem antecedência leva à pergunta de quanto tempo antes (dias, horas ou minutos). Uma antecedência já informada é aplicada diretamente, e uma recusa explícita encerra a oferta. A continuação atualiza o mesmo evento após consulta ao banco, preservando o início e os outros campos. Não cria um segundo compromisso. O aviso é entregue à conversa principal configurada do proprietário da conta, independentemente da conversa onde o lembrete foi combinado. A oferta não se repete em consultas ou edições sem relação com lembretes.

Quando o usuário fala que quer ser lembrado ("quero que você me lembre", "me lembra"), use a resposta como a decisão: com "sim" e tempo informado, a Ayla configura o lembrete no compromisso correspondente; com "sim" sem tempo, pergunta quanto tempo antes; com recusa, não configura nem oferece de novo. O aviso confirmado segue para a conversa principal configurada do proprietário, no grupo principal do WhatsApp, independentemente da conversa em que foi combinado.

### Conflitos de horário

Ao agendar um novo compromisso, a Ayla consulta a agenda e verifica sobreposição de horários com compromissos ativos, incluindo os sincronizados do Google, considerando o intervalo completo do novo compromisso (início mais duração; sobreposição já é conflito). Em caso de conflito, ela avisa o usuário e oferece opções (reschedule para outro horário, manter o compromisso existente ou cancelar o novo), sem gravar sobreposto. Quando o usuário pedir um novo compromisso sem duração informada, pergunte quanto tempo deve durar e aguarde antes de salvar; o compromisso nasce com início e término (ex.: 'às 10:00' + '2 horas' = 'das 10:00 às 12:00'). Se o usuário mencionar horário sem período claro, confirme antes de agendar; '11 da noite' é 23:00 e não conflata com um compromisso das 10:00 às 12:00.

Horários informais seguem o padrão 24 horas: "11 da manhã" é 11:00, "3 da tarde" é 15:00, "meia-noite" é 0:00 e "noite" costuma ser 23:00; a Ayla escreve o horário no formato 24h.

### Horários na conversa

“Dentista amanhã às 11” pede esclarecer “11 da manhã ou da noite?” e término/duração. “Às 23:00 por uma hora” já define 23:00 até 00:00 do dia seguinte. “Das 23:00 às 01:00” exige confirmar o dia do término. Não se presume uma hora de duração nem horário para a palavra “noite” isolada. Cada novo agendamento recebe oferta de lembrete se a escolha ainda não foi informada. Um sim sem antecedência pede horas ou dias antes. Datas com offset e fuso IANA e término posterior ao início preservam o formato usado na sincronização Google; eventos de dia inteiro usam término exclusivo.

### Conflitos de horário

Antes de criar ou remarcar, a Ayla consulta todos os eventos ativos do intervalo, sem filtrar pelo título. O serviço Java também valida no PostgreSQL: início existente < fim novo e fim existente > início novo. Eventos consecutivos (um termina quando outro começa) são permitidos; cancelados são ignorados. As gravações da agenda são serializadas por usuário com trava transacional para impedir duas requisições simultâneas de reservar o mesmo intervalo. Sobreposição retorna 409 e não grava; o usuário deve escolher outro horário ou solicitar a remarcação do existente. Editar apenas lembretes/descrição não fica bloqueado por conflitos antigos. Importações Google preservam a agenda externa; não são canceladas ou deslocadas automaticamente. Consulta médica já é título suficiente; médico/local/descrição são opcionais.

### Vários cancelamentos e avisos contados a partir de agora

A cada turno a API fornece um resumo dos próximos 30 dias lido no PostgreSQL (até 40 eventos, com total explícito). Isso não substitui consultar datas fora do resumo nem consultar novamente após alterações. A memória mantém a intenção do pedido pendente, não o estado atual dos eventos. calendar_cancel_many recebe targets com id/version para cancelar até 20 eventos explicitamente autorizados na mesma transação: falha em um item desfaz o lote.

“Daqui a cinco minutos” usa calendar_remind com delayMinutes=5; o servidor calcula e persiste o horário a partir de agora. “Cinco minutos antes da reunião” mantém reminderMinutes=[5]. Alternativamente, remindAt recebe ISO-8601 com offset. Avisos próprios AT_TIME ficam separados dos alertas BEFORE_START e são entregues pelo mesmo trabalhador durável no app/WhatsApp principal. Não são exportados como antecedência Google. Remarcar o evento preserva o horário próprio do aviso, cancelá-lo cancela todos os avisos; reminderMinutes=[] remove também avisos próprios. Estes avisos tentam entregar até cinco minutos após o horário definido para evitar mensagens antigas ao reconectar.

API: GET /api/v1/calendar/events/{id}/reminders consulta avisos; POST na mesma URL cria aviso com version e apenas um de delayMinutes ou remindAt. Suporta Idempotency-Key. O atraso máximo é 28 dias e o aviso deve ser anterior ao término do evento. calendar_get retorna event e reminders atuais.

### Horários locais para a IA e checagem de disponibilidade

A API pública mantém os instantes originais. No resumo e nas ferramentas Hermes, os eventos são apresentados com startsAt/endsAt com offset do evento e localStart/localEnd explícitos (dd/MM/yyyy HH:mm), calculados por ZoneId no Java. Assim 11:40Z é apresentado como 08:40 em America/Sao_Paulo. calendar_check recebe início e término ISO com offset e calcula a sobreposição no PostgreSQL. Sem término, retorna canConfirmFree=false, mesmo quando o início não está ocupado: não é possível confirmar disponibilidade do intervalo desconhecido. Horas isoladas entre 1 e 12 exigem esclarecer período no pedido atual; conflitos não autorizam assumir manhã/noite.

### Pedidos de agenda com várias etapas

V35 mantém calendar_conversation_plans por usuário e conversa. calendar_plan guarda resumo e alterações pretendidas antes de perguntas/conflitos, sem criar eventos. A cada turno, pendingPlan é carregado do PostgreSQL para a mesma conversa; não mistura contextos de outros chats. O plano preserva intenção, enquanto eventos e versões sempre vêm do banco. Ao excluir a conversa, seu plano é apagado.

calendar_apply recebe até dez alterações com id/version/event. Primeiro atualiza eventos existentes, depois cria novos; todas as gravações passam pela validação de horários, conflitos, versões e idempotência na mesma transação. Qualquer falha desfaz o lote e mantém o pedido pendente. O lote precisa contemplar as ações do plano; se o usuário mudar de ideia ou alguma etapa já tiver sido feita, atualize o rascunho antes. Sucesso limpa o plano. O suporte atual é a sequência de remarcações e criações; não se presume que trocas circulares de horários possam ser aplicadas nessa ordem.

“Depois do aniversário” com término conhecido às 13:00 indica a reunião começando às 13:00, preservando a duração, desde que o banco confirme disponibilidade. Não é preciso perguntar esse horário novamente. Remarcar para acomodar outro compromisso mantém o pedido original de criá-lo; a resposta deve confirmar todos os eventos gravados.

### Avisos com horário próprio na interface

GET /api/v1/calendar/events e GET /api/v1/calendar/events/{id} incluem scheduledReminders, com id, dueAt, deliveryStatus e notifiedAt. Esses avisos AT_TIME são consultados em lote por página, filtrados por usuário, versão atual e não cancelados. O card do compromisso e o formulário mostram data/hora no fuso do evento e status da entrega, incluindo os já enviados. Os avisos de antecedência permanecem em reminderMinutes. Ao editar um compromisso sem alterar os avisos de antecedência, o frontend omite reminderMinutes para preservar os avisos em horário próprio; enviar [] explicitamente continua removendo todos os avisos.

### Arquivamento de avisos enviados

Após a entrega (whatsapp_status=SENT), o aviso sai automaticamente da lista ativa, do contador do sino e das etiquetas dos cartões. Continua no PostgreSQL e aparece em Arquivados, seção recolhida por padrão com os últimos 50 avisos enviados ou lidos. GET /api/v1/calendar/reminders?archived=true consulta esse histórico; a consulta padrão unread=true exclui entregues. unread=false sem archived mantém a consulta completa para compatibilidade. Nenhum compromisso é cancelado nem configuração de antecedência é removida. scheduledReminders também informa type e minutesBefore para distinguir avisos de antecedência dos de horário próprio e ocultar somente os já enviados. Falhas e envios não confirmados permanecem visíveis.

O histórico arquivado é exibido dentro do próprio cartão do compromisso, em “Lembretes arquivados”, recolhido por padrão. A seção separada da página foi removida. A interface combina os avisos arquivados por eventId com os envios da versão atual para manter o histórico associado ao item correto.

### Lembretes sem conversa principal ou WhatsApp ativo

V36 permite FRONT_ONLY na entrega. O aviso sempre é disponibilizado no app. Se não há instância ativa do próprio usuário no vencimento, fica somente no frontend e não tenta entregar retroativamente ao conectar. Com WhatsApp ativo e sem conversa principal, o Node usa o fluxo existente para criar/reutilizar o grupo pessoal Ayla e enviar o lembrete. Avisos FRONT_ONLY ficam visíveis no app até serem lidos; não são tratados como entregues ao WhatsApp.
