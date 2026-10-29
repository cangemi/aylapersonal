# Teste de capacidade com Grafana e k6

No PowerShell, na raiz do projeto:

```powershell
.\scripts\Test-Load.ps1
```

Abra http://localhost:3300/d/ayla-vps?from=now-15m&to=now&refresh=5s antes de iniciar. São quatro etapas de um minuto: quinze segundos de subida e quarenta e cinco segundos mantendo 5, 10, 25 e 50 usuários virtuais, seguidas de quinze segundos de redução. Cada usuário faz uma leitura e espera 200 ms; usuários virtuais não são o total de usuários cadastrados. O terminal mostra a concorrência e o resumo do k6. Resultados ficam em `target/load-tests/<horário>/console.log` e `summary.json`.

Mais concorrência:

```powershell
.\scripts\Test-Load.ps1 -Levels 10,25,50,100 -HoldDuration 1m
```

Consultas financeiras reais, sem alterar os lançamentos:

```powershell
.\scripts\Test-Load.ps1 -Mode Finance -Levels 5,10,25
```

O modo Finance pede um access token válido de usuário de teste sem exibi-lo. Use um token emitido pelo login normal e não cole o token na linha de comando. Ele é passado ao container por variável de ambiente, não salvo no projeto nem nos relatórios. A sessão deve durar o teste todo; um token expirado causará 401. Não são criados usuários, logins repetidos, lançamentos, exclusões ou chamadas ao chat. Consultas autenticadas podem gerar registros técnicos normais de monitoramento. Corpos das respostas são descartados.

No padrão, o teste descobre o endpoint público disponível: `/readyz`, `/actuator/health/readiness` ou `/actuator/health`, nessa ordem. A descoberta só tenta outra rota em caso de 404; falhas de saúde não são mascaradas. Uma resposta HTTP 200 também precisa ter JSON com `status=UP`. O caminho escolhido aparece no terminal e é reutilizado por todos os usuários virtuais. A descoberta fica fora dos limites de erro/latência da fase de carga.

Para um endpoint específico, use `-ReadinessPath /actuator/health/readiness` (ou `READINESS_PATH` no k6 direto). Não é necessário reiniciar a API para executar esse teste. O teste mede HTTP e o indicador de saúde publicado; no perfil padrão, readiness pode não incluir o banco. Nos perfis de observabilidade da Ayla, o grupo readiness inclui PostgreSQL. Isso não mede CRUD completo nem capacidade da IA. Use Finance para medir autenticação, consultas e serialização; ainda é apenas uma operação, para uma conta. Não conclua um número máximo de usuários reais a partir de readiness ou de uma única conta.

Observe requisições/s, p95, erros 5xx, CPU/memória da JVM e conexões/espera do Hikari. O p95 deve ficar abaixo de 1,5 s e os erros abaixo de 1% para o teste passar. Se a taxa acumulada de erros atingir 5%, o k6 interrompe a execução, após a janela inicial de trinta segundos. Esse limite é acumulado; olhe também os picos recentes no Grafana. A interrupção pode ocorrer na próxima avaliação do k6. Ctrl+C permite interromper manualmente. Código 107 indica falha de script/pré-verificação; 99 indica limites do teste excedidos. Um 404 na pré-verificação não mede a capacidade da aplicação. O comando ajusta UTF-8 durante a execução e restaura a codificação anterior ao terminar.

O limite útil é a maior etapa sustentada com latência e erros aceitáveis, confirmada em repetições e com operações representativas. Um teste que chega a cinquenta usuários sem degradar mostra que esse cenário suportou cinquenta; não descobre automaticamente o teto. O gerador tem limite de meia CPU e 256 MB e compete com a aplicação se rodar no mesmo computador. Para medir a VPS, prefira executar o gerador em outra máquina e configurar `-BaseUrl` para um endereço acessível da API. No Docker Desktop, as métricas de host representam a VM Docker, não todo o Windows.

Na própria VPS com balanceamento:

```powershell
.\scripts\Test-Load.ps1 -BaseUrl http://api-gateway:8080 -Network ayla_default
```

Em Linux sem PowerShell, use diretamente o script k6:

```sh
docker run --rm --network ayla_default -v "$PWD/tests/load:/tests:ro" -e BASE_URL=http://api-gateway:8080 -e LEVELS=5,10,25,50 grafana/k6:1.4.0 run /tests/capacity.js
```

Referência: [etapas e limites de interrupção do k6](https://grafana.com/docs/k6/latest/examples/get-started-with-k6/test-for-performance/).
