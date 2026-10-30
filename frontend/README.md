# Ayla Web

Frontend Vue 3 + Vite, com Vue Router, Chart.js e ícones Lucide. Modo escuro inicial, modo claro persistido neste navegador e layout adaptado para celular. Fontes e ícones são servidos pelo próprio projeto, sem CDN.

As imagens locais compõem a identidade visual: `public/ayla-192.png` no logo, `public/ayla-celeste.png` no painel, `public/ayla-chat.png` nos avatares e `public/ayla-ideias.png` na abertura do chat. O login usa somente `assets/images/BackLogin.png` como fundo, importado pelo Vite com nome versionado no build; título e cartões de vidro são HTML/CSS sobre a cena. No celular, o formulário aparece sobre o fundo com uma camada de contraste. As demais ilustrações preservam suas proporções; o modo claro mantém contraste com os detalhes roxos.

Em telas até 900 px, o menu vira uma gaveta com fechamento por botão/Escape, foco contido e rolagem própria. Tabelas viram cartões com todos os campos; controles têm área mínima de toque de 44 px e inputs usam 16 px para evitar zoom automático no iOS. O chat acompanha o Visual Viewport e áreas seguras. Os testes de navegador cobrem 320, 390, 768 e 844 px, incluindo orientação horizontal e altura reduzida para simular teclado. São verificações em Chrome, não testes físicos em aparelhos iOS/Android.

## Abrir pelo Docker

Na raiz do projeto Java:

```powershell
docker compose up -d --build frontend
```

Na primeira inicialização, o frontend cria uma CA local e um certificado HTTPS para o IP LAN detectado pela API Java. O endereço LAN do servidor precisa estar acessível ao container por `host.docker.internal:8080`. A Ayla envia links `https://IP/…`; o Nginx acompanha mudanças de IP e renova o certificado do servidor automaticamente.

Para o navegador abrir os links sem alerta, instale a CA local nos celulares/computadores que usarão a Ayla. Copie o certificado público da CA para o computador:

```powershell
docker cp ayla-frontend:/var/lib/ayla-certs/ca.crt .\ayla-local-ca.crt
```

Confira sua impressão digital antes de instalar/compartilhar o certificado:

```powershell
docker exec ayla-frontend openssl x509 -in /var/lib/ayla-certs/ca.crt -noout -fingerprint -sha256
```

Instale `ayla-local-ca.crt` como certificado de autoridade raiz nos dispositivos confiáveis (no Android, em **Configurações > Segurança > Criptografia e credenciais > Instalar um certificado > Certificado CA**; no iOS, instale o perfil e habilite confiança total em **Ajustes > Geral > Sobre > Ajustes de confiança de certificado**). A interface deve estar na mesma rede local; permita as portas TCP 80 e 443 no firewall. A chave privada da CA fica somente no volume Docker `ayla_frontend_certs`; não a copie nem a compartilhe. Se o volume for removido, uma nova CA será gerada e precisará ser instalada nos dispositivos novamente.

O frontend não usa mais HTTP para servir a aplicação. A porta 80 redireciona para HTTPS na porta 443. A API Java precisa estar em execução antes de iniciar o frontend.

## Desenvolver

Requer Node.js 24 e npm.

```powershell
cd frontend
npm ci
npm run dev
```

Abra **http://localhost:5173**. O proxy de desenvolvimento encaminha `/api` para `http://localhost:8080`. Para alterar o destino, copie `.env.example` para `.env.local` e configure `AYLA_API_URL`. Nenhuma chave interna do Hermes/WhatsApp deve ser colocada no frontend.

```powershell
npm run build
npm run preview
```

O build gera `dist/`. Em outra hospedagem, configure fallback das rotas Vue para `index.html` e proxy `/api`. Se a API usar outra origem, defina `VITE_API_BASE_URL` antes do build, ajuste `AYLA_FRONTEND_ORIGINS` no Java e use HTTPS.

## Telas e integração

| Tela | Recursos |
| --- | --- |
| Acesso | Login, primeiro cadastro, esqueci a senha pelo WhatsApp |
| Visão geral | Saldo, receitas/despesas do mês, gráfico, últimos registros, compras e estoque baixo |
| Chat | Criar/alternar conversas, carregar histórico salvo/páginas anteriores, atualizar mensagens, Markdown sanitizado, anexar imagens, áudio, PDF/Word/TXT, gravar áudio, pedir pesquisa e resposta falada |
| Financeiro | CRUD, filtros, paginação, gráfico, exportação CSV da página atual |
| Despensa | CRUD, validade, mínimo, busca, filtro de estoque baixo, consumo e reposição |
| Compras | CRUD de pendentes, histórico, compra com reposição opcional, sugestões a partir da despensa |
| Memórias | Consulta, busca e exclusão das lembranças persistidas |
| Conexões | Abas WhatsApp e Hermes; QR por número solicitado, autorização Nous guiada, reconexão e acesso ao portal de planos |
| Configurações | Senha, recuperação, encerrar sessões; proprietário também ajusta voz/prévia, modelo gratuito, instruções e cria usuários |

Valores financeiros são digitados em reais com vírgula e convertidos usando inteiros/BigInt para `amountCents`, sem multiplicação em ponto flutuante. A interface envia a versão atual nas edições/exclusões e chaves de idempotência nos endpoints compatíveis. Uma resposta 502 não dispara repetição automática de uma escrita. Um 401 pode renovar o token e repetir a requisição que foi rejeitada antes de chegar ao controller.

Access/refresh tokens ficam em `localStorage`, com login persistente no aparelho, renovação coordenada e saída que revoga a sessão no Java. Fechar a aba não encerra o login. A preferência de tema também fica em `localStorage`; conversas e anexos não são persistidos nesse armazenamento. O servidor continua responsável por todas as permissões e pelo isolamento de dados. Telas administrativas ficam restritas ao proprietário.

## Limites atuais do backend

- `GET /api/v1/conversations/{id}/messages?limit=100&offset=0` retorna mensagens da sessão Hermes pertencente ao usuário. A página vem em ordem cronológica; `hasMore` e `nextOffset` permitem carregar páginas anteriores. O frontend busca o histórico ao abrir/trocar de conversa e permite atualização manual, inclusive de conversas do WhatsApp. Recarregar a página não perde a exibição. Não há atualização em tempo real. Conversas ainda sem primeiro envio retornam lista vazia.
- Ferramentas, raciocínio e mensagens internas ficam fora do histórico público. Anexos antigos podem ter somente uma indicação ou o texto da análise/transcrição; arquivos temporários apagados e áudios sintetizados não são recriados. Rotacionar uma sessão para isolar contexto não mescla novamente a sessão antiga arquivada.
- A API de memórias retorna até 200 registros ou busca por relevância; a criação ocorre nas ferramentas da Ayla.
- Em Configurações → Usuários, o superadministrador cadastra, lista e busca contas, e redefine senhas. A redefinição encerra as sessões somente da conta escolhida. O chat de outro usuário depende da configuração de um perfil Hermes isolado no backend.
- Não há transmissão de resposta em tempo real; aparece um indicador enquanto o endpoint de chat conclui.
- A prévia de voz usa os parâmetros do formulário sem salvá-los no Java ou no Hermes. Somente **Salvar voz** altera a configuração usada pela Ayla. Microfone requer localhost ou HTTPS e permissão do navegador. Gravação limitada a dois minutos e aos tamanhos aceitos pela API.
- Anexos ficam somente na memória enquanto são enviados. O backend mantém seus limites e valida o conteúdo real dos arquivos.

## Validação

```powershell
npm test
npm run test:e2e
```

Três testes Vitest verificam centavos exatos/limites e renovação concorrente sem repetir escritas com resposta incerta. Dois testes Playwright exercitam login, recuperação, temas, cadastro financeiro, compras, despensa, memórias, WhatsApp, voz e chat, além da navegação em celular. Os testes de navegador usam respostas HTTP simuladas: não modificam o banco pessoal, não enviam mensagens de WhatsApp e não chamam IA. Playwright está configurado para o Chrome instalado; em CI, instale o navegador correspondente ou ajuste `channel` em `playwright.config.js`.

Capturas ficam em `test-results/` (ignoradas pelo Git). A execução real via Docker também verifica entrega da SPA, proxy para a API e proteção 401 dos dados pessoais sem token.


Ao acessar pela rede local, inclua a origem HTTPS exata (por exemplo, https://192.168.3.35, sem barra final) em AYLA_FRONTEND_ORIGINS no .env do Java, preservando as origens de desenvolvimento separadas por vírgula, e reinicie a API. Essa lista controla o CORS; um domínio/IP não autorizado causa HTTP 403 no login mesmo quando o site abre. Se o IP mudar, atualize a lista; uma reserva DHCP evita essa mudança. Não use origem curinga para resolver esse bloqueio.


## Aplicativo no celular (PWA)

O manifest inicia em /chat e usa display standalone. Em telas até 900 px, a entrada após login é o chat. Visão geral continua acessível pelo menu Mais (/?visao=geral); a barra inferior oferece Conversa, Finanças e Compras. O seletor de conversas abre em um diálogo no celular. Conversas novas ou sem mensagens mantêm a imagem, sugestões e compositor; escolher uma sugestão preenche e focaliza a mensagem sem enviá-la automaticamente.

O botão Instalar Ayla usa o convite nativo quando disponível e oferece instruções para outros navegadores/iOS. É necessário HTTPS com certificado confiável: na LAN, instale a CA local conforme explicado acima. Ignorar o alerta TLS não garante instalação nem funcionamento de service worker/microfone. Após instalar, o navegador abre uma janela independente; autenticação continua obrigatória.

O service worker guarda somente a página pública offline e o ícone. Não salva respostas da API, mensagens, tokens, anexos ou dados financeiros em Cache Storage. Chat e alterações de dados precisam de conexão com o servidor. Não há envio financeiro offline. Atualizações de páginas e módulos continuam vindo da rede, sem usar cópias antigas da aplicação.


## Login persistente no aparelho

A sessão usa localStorage em vez de sessionStorage: fechar a aba, o navegador ou o webapp não encerra o login. Uma sessão antiga ainda aberta é migrada automaticamente na atualização. Senhas não são armazenadas; os tokens seguem a validade/revogação do Java (acesso de 15 minutos, sessão de até 30 dias a partir do login). A abertura consulta /auth/me e renova o acesso quando necessário. Falhas de rede não apagam a sessão; expiração ou revogação exige entrar novamente.

Abas da mesma origem sincronizam renovação, login e saída pelo evento storage. Em navegadores com Web Locks, a renovação é serializada entre abas para não consumir o mesmo refresh token duas vezes. Sair remove a sessão persistida e revoga a sessão no servidor quando ele está disponível. IPs, domínios, perfis de navegador e instalações que isolam armazenamento têm sessões próprias; limpar os dados do site também remove o login.
