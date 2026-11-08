# Carteira de investimentos

Em `/investimentos`, cadastre uma aplicação por produto e instituição. Informe o saldo atual, a data desse saldo e, se souber, o capital aplicado até essa data. A diferença entre saldo e capital registra o ganho ou perda anterior ao acompanhamento. Se o capital for desconhecido, o saldo inicial vira a referência: rendimentos anteriores não são inventados.

O cadastro aceita caixinhas, CDB, RDB, LCI, LCA, Tesouro, poupança, ações, FIIs, ETFs, fundos, previdência, cripto e outros ativos. Todos os valores são acompanhados em reais.

- **Percentual do CDI:** usa somente taxas diárias publicadas na API pública gratuita do Banco Central (SGS 12), com cache local. Informe o percentual contratado; não há taxa presumida por banco.
- **Taxa anual ou mensal:** estimativa composta pela taxa informada, com base de 252 dias úteis/ano e 21/mês ou 365 dias corridos/ano e 30/mês. Alterações de taxa passam a valer para os dias seguintes, preservando o histórico.
- **Saldo manual:** registre o valor de mercado ou saldo do extrato. Serve também para IPCA+, Selic, poupança e ativos sem cotação automática. Não há integração bancária nem serviço pago de cotações.

As estimativas são brutas, sem impostos, taxas ou marcação a mercado. Não há rendimento na própria data do saldo inicial ou de um aporte. A estimativa para no vencimento, quando informado. Dias sem CDI publicado não recebem uma taxa inventada e aparecem com aviso.

Aportes e resgates mudam o saldo e o capital líquido aplicado. Uma **atualização de saldo** substitui o valor da aplicação naquela data sem alterar o capital. **Proventos** entram no retorno total; em posições com juros estimados, o pagamento é retirado do saldo capitalizado para evitar contar o mesmo rendimento duas vezes. Em posições manuais, o saldo de mercado informado é preservado. Para reinvestir um provento, registre também um aporte.

A carteira nova não gera lançamentos financeiros. A caixinha anterior continua vinculada à categoria Investimento do financeiro e já compõe os totais quando disponível. Abra seu cartão para continuar usando os registros antigos.

API: `GET /api/v1/investments/portfolio`, CRUD em `/positions`, e movimentações em `/positions/{id}/events`, sob o mesmo prefixo. Alterações exigem a versão atual; criações aceitam `Idempotency-Key`. Todas as consultas e alterações verificam o proprietário autenticado. A migração V54 cria as novas tabelas sem modificar os saldos anteriores.

A integração da Ayla usa `investment_portfolio`, `investment_get_position`, `investment_save_position` e `investment_event`. Os dados enviados ao modelo são resumidos, sem carregar os gráficos completos.

O CRUD pelo chat também inclui `investment_delete_position`, `investment_update_event` e `investment_delete_event`, com versões atuais e escopo do proprietário. Edições de aplicação e movimentação aceitam somente os campos alterados. `investment_get_position` pagina todo o histórico; `investment_analytics` consulta rendimentos com filtros. Pedidos para tentar novamente devem consultar o registro antes de repetir uma escrita.

As chamadas de IA usam limite próprio `HERMES_INFERENCE_TIMEOUT_SECONDS` (600 segundos), separado das consultas normais e das verificações rápidas de saúde. Avisos de falha são persistidos no histórico pela migração V55, com a identidade do pedido para evitar duplicidade ao reabrir o chat. A V55 também recupera avisos dos pedidos antigos ainda retidos na fila. Reinicie API e Hermes para carregar as configurações e ferramentas atualizadas.

Fonte: [Portal de Dados Abertos do Banco Central](https://dadosabertos.bcb.gov.br/).

## Gráficos e análise de rendimentos

O painel permite filtrar tipo, instituição, aplicação e ano. Oferece evolução do saldo e capital aplicado, ganho acumulado, rendimento diário do mês escolhido, rendimento mensal, comparação entre todos os anos, fluxos de aportes/resgates/proventos, distribuição patrimonial e comparação por tipo ou banco. Os mesmos filtros são aplicados às tabelas e ao CSV exportado.

Os indicadores de hoje e do mês vigente sempre usam a data atual do servidor no fuso de São Paulo. Escolher um ano anterior altera as séries daquele ano e o indicador anual; não muda o significado de “hoje” ou “mês vigente”. Meses e anos atuais terminam hoje. A API `GET /api/v1/investments/analytics?year=2026` disponibiliza os períodos, incluindo anos anteriores ao limite de 365 dias do gráfico original. Não exige nova migração ou API externa.

Rendimento do período = variação do saldo − aportes + resgates + proventos pagos. Ganho já incluído no saldo de partida não é distribuído retroativamente por datas. “Ganho/base” divide o rendimento pelo saldo inicial do período mais os aportes; é uma comparação sobre essa base, não taxa anualizada, TIR ou retorno ponderado pelo tempo.

Um retorno exige base suficiente nas duas pontas do intervalo. Em aplicações manuais, uma atualização isolada pode permitir o resultado mensal ou anual quando existe saldo na outra ponta, mas não revela o retorno de cada dia intermediário. CDI ausente também torna o período incompleto até uma conciliação pelo extrato. Nesses casos o rendimento aparece como indisponível; as variações registradas ficam separadas em laranja, sem serem tratadas como retorno exato ou zero. A data inicial de acompanhamento e a última atualização ficam disponíveis na cobertura por aplicação.
