<script setup>
import Icon from '../components/Icon.vue'
const groups = [
  {
    title: 'No WhatsApp',
    description: 'Comandos enviados por você, pelo seu próprio número, na conversa desejada.',
    commands: [
      {
        code: 'configurar Ayla0101',
        text: 'Torna a conversa atual a principal: a Ayla passa a responder todas as suas mensagens ali.',
      },
      {
        code: 'tirar Ayla0101',
        text: 'Desativa a Ayla na conversa atual.',
      },
      {
        code: 'Ayla0101 <pedido>',
        text: 'Chama a Ayla uma única vez em qualquer conversa. Ela lê as últimas 10 mensagens como contexto.',
      },
    ],
  },
  {
    title: 'Contexto ao chamar a Ayla',
    description: 'Escreva junto com Ayla0101 para controlar quantas mensagens ela deve ler.',
    commands: [
      { code: 'Ayla0101 … 30 mensagens', text: 'Lê a quantidade informada, até 100.' },
      { code: 'Ayla0101 … leia mais', text: 'Lê 20 mensagens.' },
      { code: 'Ayla0101 … leia menos', text: 'Lê 5 mensagens.' },
      {
        code: 'Ayla0101 … sem histórico',
        text: 'Não lê o histórico. Também vale “sem contexto” ou “só essa”.',
      },
      {
        code: 'Responder uma mensagem + Ayla0101',
        text: 'Cite uma mensagem, foto ou documento para a Ayla analisar exatamente aquele conteúdo.',
      },
    ],
  },
  {
    title: 'Pedidos do dia a dia',
    description: 'Escreva naturalmente, no WhatsApp ou na aba Conversar.',
    commands: [
      { code: 'Gastei 25 reais no mercado', text: 'Registra uma despesa ou receita.' },
      { code: 'Quanto gastei este mês?', text: 'Consulta o financeiro com os dados atuais.' },
      { code: 'Anote arroz e feijão na lista de compras', text: 'Adiciona itens à lista.' },
      { code: 'O que tenho na despensa?', text: 'Consulta ou atualiza a despensa.' },
      {
        code: 'Em que categoria estou gastando mais?',
        text: 'Mostra gastos por categoria, ritmo e tendência.',
      },
      {
        code: 'Investi 200 reais na caixinha',
        text: 'Registra um aporte na categoria Investimento.',
      },
      {
        code: 'Quanto minha caixinha rendeu este mês?',
        text: 'Rendimento do dia, do mês e projeção, com o CDI atual.',
      },
      {
        code: 'E se eu investir 300 por mês durante 1 ano?',
        text: 'Simula a caixinha com aportes mensais.',
      },
      {
        code: 'Me mostra a página do financeiro',
        text: 'Na conversa principal, a Ayla envia o link da página. Vale também para investimentos, despensa e lista de compras.',
      },
      { code: 'Me manda um áudio', text: 'A Ayla responde por voz, além do cabeçalho.' },
    ],
  },
]
</script>
<template>
  <div class="page-heading">
    <div>
      <span class="eyebrow">TUDO À MÃO</span>
      <h1>Como falar com a Ayla.</h1>
      <p>Comandos e exemplos para usar no WhatsApp e no chat.</p>
    </div>
    <span class="stat-icon lavender"><Icon name="help" :size="24" /></span>
  </div>
  <div class="tip-strip">
    <Icon name="shield" /><span
      >Toda resposta da Ayla no WhatsApp começa com <code>-------Ayla--------</code>. Mensagens de
      outras pessoas nunca acionam a Ayla.</span
    >
  </div>
  <section v-for="group in groups" :key="group.title" class="panel command-group">
    <div class="panel-heading">
      <div>
        <h2>{{ group.title }}</h2>
        <p>{{ group.description }}</p>
      </div>
    </div>
    <dl class="command-list">
      <div v-for="command in group.commands" :key="command.code" class="command-row">
        <dt>
          <code>{{ command.code }}</code>
        </dt>
        <dd>{{ command.text }}</dd>
      </div>
    </dl>
  </section>
</template>
