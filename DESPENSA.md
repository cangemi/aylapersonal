# Despensa e lista de compras

Os controllers e as ferramentas da Ayla usam o mesmo serviço Java e os mesmos registros PostgreSQL (migração V14). Dados separados por usuário; contatos autorizados no WhatsApp continuam compartilhando os dados do proprietário. Lista e carrinho não geram despesa até o pagamento. Ao confirmar o pagamento, o checkout cria a despesa e repõe estoque na mesma transação. Estoque, preços e totais sempre vêm do banco.

## API para o frontend

As rotas estão na seção **Despensa e compras** do Swagger.

| Método | Endpoint | Uso |
| --- | --- | --- |
| GET/POST | `/api/v1/pantry/items` | Listar/cadastrar produtos |
| GET/PATCH/DELETE | `/api/v1/pantry/items/{id}` | Consultar/editar/excluir |
| POST | `/api/v1/pantry/items/{id}/adjustments` | Repor ou consumir |
| GET/POST | `/api/v1/shopping-list/items` | Consultar/adicionar à lista |
| GET/PATCH/DELETE | `/api/v1/shopping-list/items/{id}` | Consultar/editar/excluir |
| POST | `/api/v1/shopping-list/items/{id}/purchase` | Marcar comprado e opcionalmente repor |
| POST | `/api/v1/shopping-list/from-pantry` | Gerar faltantes sem duplicar pendências |

Listagens retornam `{items,total,page,size}`. `page` começa em zero; `size` padrão 50, máximo 100. `q` filtra nomes, ignorando caixa e acentos. Na despensa, `lowStock=true` filtra produtos zerados ou abaixo do mínimo e `expiringBefore=2026-12-31` filtra validade até uma data inclusiva. Na lista, o padrão é `status=PENDING`; use `status=PURCHASED` ou `all=true` para histórico.

Cadastro na despensa:

```json
{
  "name": "Arroz",
  "quantity": 2,
  "unit": "KG",
  "minimumQuantity": 1,
  "expiresOn": "2027-06-01",
  "category": "Alimentos",
  "notes": "Armário da cozinha"
}
```

Nome, quantidade (inclusive zero) e unidade são obrigatórios para estoque. Unidades: `UN`, `KG`, `G`, `L`, `ML`, `PACOTE`. Quantidades usam `BigDecimal`/`numeric`, até três casas decimais, sem ponto flutuante. Valores entre zero e 999999999; consumo/entrada e quantidades da lista precisam ser positivos. Mínimo padrão zero; categoria, notas e validade são opcionais.

PATCH exige `version` e preserva campos omitidos. `quantity` é o novo total, não um acréscimo. `clearExpiry=true` remove a validade. Por exemplo, `{"version":0,"quantity":1.5}`. DELETE exige `?version=0`. Versão desatualizada retorna 409. O mesmo nome normalizado não pode ser cadastrado duas vezes na despensa ou como duas pendências da lista.

Para baixar meio quilo de um estoque em kg:

```json
{"version":0,"action":"CONSUME","quantity":500,"unit":"G"}
```

Para repor, use `action=ADD`. KG/G e L/ML são convertidos exatamente; pacotes não são convertidos em peso ou unidades sem um ajuste informado. Estoque negativo é rejeitado. Mudanças de unidade por PATCH convertem os valores preservados quando compatíveis.

Adicionar à lista pode ser somente `{"name":"Leite"}`. Quantidade e unidade ficam indefinidas, sem inventar uma quantidade. Se informar quantidade sem unidade, o frontend recebe o padrão `UN`; para líquidos/peso informe explicitamente. Editar um item pendente substitui o total desejado. Compra concluída fica no histórico: para comprar outra vez, adicione nova pendência.

Marcar como comprado e repor a despensa:

```json
{"version":0,"addToPantry":true,"quantity":2,"unit":"L"}
```

Quantidade/unidade podem ser omitidas quando já conhecidas na lista. `addToPantry=false` (padrão) só marca como comprado, inclusive sem quantidade. Marcação e reposição são uma transação: se a entrada falhar, a compra continua pendente. Uma compra concluída nunca repõe novamente. Excluir seu histórico não desfaz o estoque.

Envie `Idempotency-Key` único em mutações para repetir a mesma requisição sem duplicar efeitos. A chave é separada por usuário; reutilizá-la para outro conteúdo retorna 409. Os resultados repetidos são o recibo da operação original; consulte a listagem para o estado atual.

Gerar faltantes usa o déficit até o estoque mínimo, sem somar novamente uma sugestão existente. Um produto zerado sem mínimo configurado entra sem quantidade. A geração não exclui pendências criadas manualmente. Validade é por produto, sem lotes separados: entradas preservam a data mais próxima conhecida; quando o estoque anterior era zero, usam a validade da nova entrada. Datas desconhecidas continuam desconhecidas. Produtos vencidos não são consumidos/descartados automaticamente.

## Conversa com a Ayla

Exemplos de pedidos:

- “Coloca arroz e leite na lista.”
- “Tenho 2 kg de arroz na despensa.”
- “Usei 500 g de arroz.”
- “Comprei mais dois litros de leite.”
- “O café acabou, coloca na lista.”
- “O que está faltando? Coloca os faltantes na lista.”
- “Tira o leite da lista.”

`household_list` consulta o banco; `household_apply` aplica até vinte ações atomicamente. O conjunto `ayla_household` é registrado pelo plugin existente `ayla-finance`. A identidade vem da sessão nativa, e a chave fica só no backend. A idempotência por turno e conteúdo é calculada pelo Java, sem confiar em chaves inventadas pelo modelo.

A Ayla deve interpretar total versus entrada versus consumo, consultar IDs/versões atuais e perguntar somente o essencial. Quantidade é opcional na lista; estoque precisa de quantidade/unidade claras. “Comprei”, no contexto de estoque e sem valor monetário, não deve gerar perguntas de preço/moeda. A interpretação continua sendo feita pelo modelo; os testes validam os contratos e efeitos no banco, sem garantir cada decisão linguística de uma conversa real.

## CORS

O frontend pode usar `http://localhost:3000` ou `http://localhost:5173` por padrão. Para outro endereço, configure `AYLA_FRONTEND_ORIGINS=https://meu-front.example,http://localhost:5173` e reinicie a API. A configuração aplica-se a `/api/v1/**`, com cabeçalhos `Content-Type`, `Authorization` e `Idempotency-Key`; as rotas internas do Hermes não são liberadas por CORS.

## Preços, carrinho e pagamento (V38)

Estados: PENDING (para pegar), IN_CART (check do carrinho), PURCHASED (pago). O filtro padrão PENDING inclui os dois estados ativos; IN_CART filtra só carrinho e all=true inclui o histórico. Não pode haver dois produtos de mesmo nome normalizado entre os ativos. Itens históricos não impedem nova compra do mesmo produto.

No cadastro/PATCH de item, unitPriceCents é o preço por unidade; totalPriceCents é o valor de toda a quantidade desse produto. Envie apenas um. São inteiros JSON em centavos, com limite do financeiro; zero é aceito para item gratuito. Para preço por unidade, o Java multiplica pela quantidade BigDecimal e arredonda uma vez por linha, HALF_UP para centavos. Total do carrinho é soma de inteiros, validada pelo servidor. Mudar quantidade recalcula o total unitário. Mudar a unidade de um produto com preço unitário exige atualizar o preço ou removê-lo, para não aplicar preço de kg como se fosse de g.

Exemplo de duas unidades de leite a R$ 5,90 cada: {"name":"Leite","quantity":2,"unit":"UN","unitPriceCents":590}. O servidor calcula 1180 e marca IN_CART. Para R$ 11,80 no conjunto, use totalPriceCents:1180. Preço informado marca carrinho por padrão; inCart:false permite orçamento sem check. inCart:true/false marca/desmarca sem alterar estoque ou financeiro; clearPrice:true retira o preço. Nome sozinho continua permitido para planejar, mas checkout exige quantidade/unidade e preço conhecidos. Produto contável sem quantidade, unidade UN ou ausente, com preço assume 1 UN; quantidade cadastrada é preservada. Peso/volume exige quantidade conhecida.

| Método | Endpoint | Uso |
| --- | --- | --- |
| GET | /api/v1/shopping-list/cart | Itens, versões, total calculado e contagens de preços/quantidades faltantes |
| POST | /api/v1/shopping-list/checkout | Confirmar pagamento de todos os itens IN_CART |
| GET | /api/v1/shopping-list/checkouts | Histórico paginado de compras e totais pagos |
| GET | /api/v1/shopping-list/checkouts/{id} | Detalhes e itens de uma compra |

Checkout recebe items:[{id,version}] de TODO o carrinho e occurredAt opcional (sem data usa agora). A API rejeita carrinho alterado, incompleto ou vazio. Cada linha passa para PURCHASED, vai à despensa por nome/unidade compatível e se vincula ao checkout. A despesa é uma única EXPENSE, descrição Compra mercado, categoria Supermercado, valor calculado. Total zero repõe estoque sem despesa zero. Produtos PENDING não entram no pagamento: são arquivados como não comprados. Toda a lista ativa fica vazia. Qualquer falha desfaz estoque, despesa e finalização. Idempotency-Key e recibos por turno evitam repetir pagamento e reposição. Excluir o histórico de um item não desfaz estoque nem lançamento financeiro.

Ayla: household_list e household_apply continuam fazendo CRUD em lotes de até vinte itens; household_cart lê total e pendências; household_checkout recebe IDs/versões para concluir o pagamento; household_purchases lê totais já pagos. A IA não soma valores nem usa lembranças como lista/preço/total. No mercado, informar preço atualiza o produto e o check; dizer já paguei autoriza finalizar sem outra confirmação, após completar apenas os dados faltantes. Não chamar finance_record ou repor estoque separadamente depois do checkout. Uma proteção na contabilização do turno evita duplicar a despesa se a IA tentar finance_record para o mesmo checkout.

Frontend: preço com máscara e escolha unitário/total, check do carrinho, resumo calculado pela API e botão Já paguei. Atualizações externas são buscadas a cada 15 segundos em segundo plano sem desmontar cartões; não atualiza por cima de um formulário aberto.

## Listas arquivadas e evolução de preços (V39)

A lista é dinâmica e não exige criar/confirmar um cabeçalho. Ao finalizar, shopping_checkouts guarda a compra datada e shopping_purchase_items guarda uma cópia imutável dos nomes, quantidades e preços. Linhas IN_CART são compradas; linhas PENDING ficam ARCHIVED, não compradas e sem estoque/despesa. A lista ativa começa vazia. Excluir um produto posteriormente não apaga seu snapshot histórico. A migração recupera apenas linhas reais de checkouts antigos ainda disponíveis; não reconstrói itens excluídos.

Na conversa: chocolate 10,00 = R$ 10,00; bala 10 centavos ou 0,10 = R$ 0,10. Preço simples significa total da linha; cada significa preço unitário. Uma unidade contável é assumida quando não há quantidade anterior; para peso/volume, a IA pergunta somente o que falta. Já comprei tudo autoriza finalizar sem confirmação adicional. Se ainda há itens sem preço, esclarece quais foram comprados antes de encerrar.

| Método | Endpoint | Uso |
| --- | --- | --- |
| GET | /api/v1/shopping-list/prices/products?q=&page=0&size=50 | Produtos comprados, unidade e quantidade de compras |
| GET | /api/v1/shopping-list/prices/history?name=Arroz&unit=KG&from=2026-01-01&to=2026-12-31 | Histórico cronológico paginado, menor/maior/média e variação entre últimas compras |
| POST | /api/v1/shopping-list/prices/compare | Comparar oferta com última compra: name, unit e unitPriceCents OU totalPriceCents + quantity |
| GET | /api/v1/shopping-list/prices/cart-comparison | Comparar até 200 itens atuais com sua última compra |
| GET | /api/v1/shopping-list/items/{id}/price-comparison | Comparar um item atual com sua última compra |

Todos os endpoints exigem autenticação e isolam os dados pelo usuário. A IA usa household_price_products, household_price_history, household_price_compare e household_cart_prices com a identidade validada da conversa. Consultas não alteram o carrinho. Sem compras anteriores não se afirma aumento/redução. Preços comparam nomes normalizados exatos (sem equivalência automática entre marcas), KG/G e L/ML convertidos para base comum; pacote permanece separado de unidade. Total usa centavos inteiros; referência por medida e percentuais usam BigDecimal. Preço anterior zero não gera percentual indefinido. Valores decimais de referência são strings; a conversão para Number ocorre somente no desenho do gráfico.

Frontend: Compras → Compras anteriores e evolução de preços (/compras/historico), com listas arquivadas, detalhes, produto/unidade, período, gráfico com pontos, tabela, estatísticas e comparação de oferta. O gráfico não anima no scroll nem repete animação nas atualizações. Histórico vazio é informado sem gerar dados de exemplo.


### Prioridade entre lista e despesa avulsa

Antes de classificar produto + valor como despesa, a Ayla consulta a lista ativa real (PENDING inclui IN_CART). Produto correspondente: atualiza preço e check, sem gasto avulso; o checkout contabiliza o total. Produto ausente: registra como gasto, quando os dados estão claros. Pedido explícito para adicionar na lista ou contexto inequívoco de compra em andamento permite produto novo; pedido explícito de gasto fora da lista permite despesa independente. Listas arquivadas não entram nessa decisão. Consulta falhou não significa produto ausente; múltiplos candidatos exigem esclarecer apenas qual produto. Hipóteses e pesquisas de preço não geram despesas.

A integração financeira também possui uma proteção no Java: finance_record de EXPENSE cujo produto corresponde ao nome ativo normalizado atualiza o preço total e marca o carrinho, sem criar despesa. Ignora acentos/maiúsculas e prefixos comuns como compra de. Responde destination=SHOPPING para a Ayla confirmar a operação correta. outsideShoppingList=true só deve ser enviado por pedido expresso de gasto independente. Recibo por turno/produto torna repetição idempotente; o CRUD financeiro direto do frontend permanece independente.


## Lista em texto e frontend (V41)

O fluxo de enquete foi retirado: sem ferramenta Hermes, endpoint de criação ou ouvintes de votos no Node. V40 permanece imutável no histórico Flyway; V41 inativa pedidos, mensagens e eventos antigos. As tabelas antigas ficam somente como registros históricos, sem processamento.

Ao solicitar a lista, household_list retorna dados atuais e frontendUrl. A Ayla apresenta itens/preços/carrinho em texto e envia o link /compras em linha própria. Isso vale também para pedidos em conversas secundárias; abrir o frontend continua exigindo login. O endereço usa AYLA_WEB_BASE_URL ou detecção LAN existente.

Frontend: lista em linhas, com preço mascarado, quantidade, unidade e tipo de preço editáveis diretamente. Os campos salvam ao concluir a alteração, com alternativa Salvar e erros por item. Preço informado coloca no carrinho; o check permite marcar/desmarcar sem excluir. Totais continuam calculados no Java. Produtos pegos têm check e destaque próprio. Histórico, criação, exclusão e finalização de compra permanecem disponíveis.

GET /api/v1/shopping-list/items aceita order=NORMAL (nome A–Z) ou CART_LAST (pendentes primeiro, carrinho por último); ordenação ocorre antes da paginação. O frontend mostra até 50 itens por página e salva a preferência no navegador por usuário. Campos mobile têm alvos maiores, preços com teclado numérico e resumo do carrinho acessível ao rolar. Atualizações em segundo plano não sobrescrevem edições em andamento.

Testes adicionados para centavos, preço/estado do carrinho, preferência por usuário, edição mobile/ordenação e link em ambos os escopos. Escritos sem execução a pedido do usuário.
