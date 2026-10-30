# Oracle Ampere A1: 2 OCPUs e 12 GB

O arquivo `compose.oracle-free.yaml` ajusta os limites da aplicação e da observabilidade para essa VPS. Ele deve ser o **último** arquivo da composição. Não use esse overlay no Docker Desktop.

## Orçamento

| Serviço | Limite de RAM |
|---|---:|
| API Java | 2 × 768 MiB |
| Hermes | 2 GiB |
| WhatsApp / Chromium | 3 GiB |
| PostgreSQL | 768 MiB |
| Redis | 128 MiB |
| RabbitMQ | 384 MiB |
| Frontend + HAProxy | 160 MiB |
| Observabilidade completa | 2.016 MiB |
| **Soma dos limites, incluindo as duas APIs** | **10.112 MiB (9,875 GiB)** |

A slice Linux `ayla.slice` limita **o conjunto** a 10 GiB e 1,8 CPU. Isso deixa aproximadamente 2 GiB e 0,2 CPU de margem para Linux, Docker e administração numa máquina com 12 GiB e 2 CPUs. O consumo real pode ficar abaixo dos tetos. A partir de 9 GiB, o Linux tenta recuperar memória; o teto de 10 GiB pode provocar OOM. Swap fica desabilitada para a slice.

Os limites individuais de CPU permitem picos por serviço e, por isso, sua soma passa de duas CPUs. O teto compartilhado de 1,8 CPU continua valendo: serviços competem por esse orçamento, não recebem CPU reservada. Sob carga, haverá espera e aumento de latência.

Cada API aceita dois chats ativos e até oito conexões com o banco. São quatro vagas de chat no conjunto; o provedor e o Hermes ainda podem limitar o processamento. O PostgreSQL aceita até 60 conexões, com buffers compartilhados de 128 MiB; o Redis limita seus dados a 64 MiB e o RabbitMQ começa a aplicar controle de fluxo de memória em 256 MiB. O teto do WhatsApp inclui Node e os navegadores: não significa capacidade ilimitada de contas conectadas.

## Ativar na VPS Linux

Use Docker Engine com driver de cgroups `systemd`, cgroup v2 e systemd no host, por exemplo Ubuntu 24.04 ARM64. Configure o `.env` conforme `OBSERVABILIDADE.md` e prepare o limite compartilhado **antes** de criar os containers:

```sh
sudo bash scripts/Prepare-Oracle-Free.sh
docker compose -f compose.yaml -f compose.vps.yaml -f compose.observability.yaml -f compose.oracle-free.yaml config --quiet
docker compose -f compose.yaml -f compose.vps.yaml -f compose.observability.yaml -f compose.oracle-free.yaml pull
docker compose -f compose.yaml -f compose.vps.yaml -f compose.observability.yaml -f compose.oracle-free.yaml up -d --no-build
```

As imagens próprias precisam estar publicadas no Docker Hub; veja `PUBLICACAO_DOCKER.md`. Prefira uma tag de commit em `AYLA_IMAGE_TAG`. Compile e publique pelo GitHub Actions para não disputar CPU/RAM com builds na VPS. Todos os comandos de atualização devem manter os quatro arquivos Compose na mesma ordem. O script instala somente a slice, sem reiniciar Docker ou serviços existentes. `up` pode recriar os containers para aplicar os novos limites; os volumes são preservados.

O overlay fixa os limites de pool e concorrência em 8 e 2 mesmo se seu `.env` ainda contiver os padrões genéricos 12 e 3. Para recalibrar, altere o overlay e recalcule o orçamento de todas as réplicas.

## Conferir depois de subir

```sh
systemctl show ayla.slice -p CPUQuotaPerSecUSec -p MemoryHigh -p MemoryMax -p MemoryCurrent
systemd-cgtop
docker stats --no-stream
docker compose -f compose.yaml -f compose.vps.yaml -f compose.observability.yaml -f compose.oracle-free.yaml ps
```

Confira também CPU, RAM, reinícios e erros no Grafana. Os limites evitam que a stack consuma toda a VPS, mas não garantem capacidade para qualquer volume de chats, áudio ou contas WhatsApp. Meça esses cenários na ARM real antes de aumentar usuários. A retenção de métricas/logs/traces permanece a definida em `OBSERVABILIDADE.md`; estes limites não impõem um teto conjunto de disco.

Referências: [cgroup_parent no Compose](https://docs.docker.com/reference/compose-file/services/#cgroup_parent), [limites de recursos do systemd](https://www.freedesktop.org/software/systemd/man/latest/systemd.resource-control.html).

A concorrência do overlay pode ser ajustada explicitamente por ORACLE_MAX_CONCURRENT_CHATS (padrão 2 por réplica, 4 pedidos com duas réplicas). O MAX_CONCURRENT_CHATS genérico da configuração local não aumenta silenciosamente esse limite da Oracle. Aumentar esse parâmetro não acrescenta CPU ou RAM; medir consumo e fila antes de elevar em produção.
