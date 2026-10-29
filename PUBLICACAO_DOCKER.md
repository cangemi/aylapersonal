# Publicar imagens da Ayla no Docker Hub

O workflow `.github/workflows/docker-publish.yml` cria e publica as três imagens próprias do projeto no namespace `aylapersonal`:

| Componente | Imagem | Contexto |
|---|---|---|
| API Java | `aylapersonal/ayla-api` | raiz do projeto |
| Frontend | `aylapersonal/ayla-frontend` | `frontend` |
| WhatsApp | `aylapersonal/ayla-whatsapp` | `agentNode` |

As imagens publicadas incluem `linux/amd64` e `linux/arm64`, compatíveis com a Oracle Ampere A1. Java e Vite compilam na arquitetura do runner; os artefatos resultantes rodam nos respectivos runtimes da arquitetura de destino. O WhatsApp mantém Chromium e dependências da arquitetura de destino. Buildx/QEMU prepara o build multiplataforma.

## Configuração inicial no GitHub

1. No Docker Hub, prepare os repositórios `ayla-api`, `ayla-frontend` e `ayla-whatsapp` em `aylapersonal`, escolhendo a visibilidade desejada.
2. Crie um access token Docker Hub com permissão de escrita nesses repositórios.
3. No repositório GitHub, abra **Settings → Secrets and variables → Actions → New repository secret**.
4. Cadastre **`DOCKERHUB_TOKEN`** com esse token. Não coloque o token no código ou no `.env` do projeto.
5. O login padrão é `aylapersonal`. Se esse namespace for uma organização, crie a **repository variable** `DOCKERHUB_USERNAME` com o login da conta que tem permissão de publicação na organização.
6. Opcionalmente, configure a repository variable `GRAFANA_BROWSER_URL` com o endereço que o navegador do administrador usa para abrir o Grafana. O padrão é `http://localhost:3300/d/ayla-vps`. Essa URL fica no JavaScript público do frontend e deve conter apenas o endereço, sem credenciais.

A pasta `.github/workflows` deve ficar na raiz do repositório GitHub. Depois de enviar os arquivos, acompanhe a execução em **Actions → Build and publish Docker images**. A execução remota exige essas credenciais; a validação local do workflow não realiza publicação.

## Quando publica

- **Push em qualquer branch:** cria e publica imagens com `branch-<nome da branch>` e `sha-<SHA completo>`.
- **Push na branch principal do repositório:** também atualiza `latest`, independentemente de a branch se chamar `main`, `master` ou outro nome.
- **Push de tag Git:** publica `release-<tag Git>`, normalizada para formato Docker, além da identificação por commit. Por exemplo, `v1.2.3` gera `release-v1.2.3`. Os prefixos evitam que uma branch/tag chamada `latest` substitua a imagem principal.
- **Pull request:** valida os builds AMD64 sem autenticar nem publicar no Docker Hub. O build multiplataforma é feito no push.
- **Run workflow:** permite disparar manualmente pela aba Actions.

Uma nova execução da mesma branch substitui builds ainda em andamento, evitando que uma publicação antiga termine depois de uma nova. Por isso, em pushes muito próximos, a execução anterior pode ser cancelada. O workflow compila os Dockerfiles existentes; ele não acrescenta uma execução da suíte completa de testes automatizados.

Exemplos de tags:

```text
aylapersonal/ayla-api:latest
aylapersonal/ayla-api:branch-main
aylapersonal/ayla-api:release-v1.2.3
aylapersonal/ayla-api:sha-<40 caracteres do commit>
```

As três imagens de uma execução usam a mesma referência de commit. Para uma atualização consistente, aguarde os três jobs passarem e prefira usar a tag `sha-...` no servidor. A publicação entre repositórios Docker não é atômica: um job que falhar não desfaz imagens já publicadas pelos outros jobs.

## Usar as imagens prontas na VPS

Para a Oracle A1 de **2 OCPUs e 12 GB**, prepare a slice e acrescente `-f compose.oracle-free.yaml` por último em todos os comandos abaixo. O passo a passo e o orçamento conjunto estão em [ORACLE_FREE.md](ORACLE_FREE.md). Assim você aplica os limites específicos dessa máquina sem precisar compilar imagens nela.

O `compose.vps.yaml` mantém os builds locais e também referencia as imagens do Docker Hub. Para baixar e usar as imagens publicadas, sem reconstruí-las no servidor:

```sh
docker compose -f compose.yaml -f compose.vps.yaml -f compose.observability.yaml -f compose.oracle-free.yaml pull api frontend whatsapp
docker compose -f compose.yaml -f compose.vps.yaml -f compose.observability.yaml -f compose.oracle-free.yaml up -d --no-build
```

O padrão é `AYLA_IMAGE_TAG=latest`. Para fixar uma versão, configure no `.env` da VPS `AYLA_IMAGE_TAG=sha-<SHA completo de uma execução bem-sucedida>` antes de executar os comandos. Para imagens privadas, execute `docker login` na VPS com uma credencial autorizada a baixá-las.

O workflow publica imagens; ele não faz login na VPS nem reinicia automaticamente a aplicação. Os volumes e segredos continuam no servidor. Os arquivos Compose, configurações de observabilidade e arquivos montados do Hermes continuam necessários na implantação: este fluxo não cria uma imagem personalizada do Hermes. Hermes, bancos e serviços de observabilidade continuam usando suas imagens upstream.

As regras `.dockerignore` mantêm `.env`, credenciais de coleta, dependências locais, logs e sessões WhatsApp fora dos respectivos contextos de build. Nunca use credenciais como argumentos de build. As ações do GitHub estão fixadas por commit, as permissões do workflow são somente leitura do código e o cache é separado por componente.

Referências: [Docker e GitHub Actions](https://docs.docker.com/build/ci/github-actions/), [imagens multiplataforma](https://docs.docker.com/build/ci/github-actions/multi-platform/) e [tags e labels](https://docs.docker.com/build/ci/github-actions/manage-tags-labels/).
