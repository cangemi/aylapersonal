<script setup>
import { money } from '../lib/format'
import { periodStatus } from '../lib/investmentAnalytics'
defineProps({ rows: Array, label: String, heading: { type: String, default: 'Período' } })
const pct = (value) => value == null ? '—' : Number(value).toLocaleString('pt-BR', { maximumFractionDigits: 2 }) + '%'
</script>
<template>
  <div class="performance-table-wrap" tabindex="0" :aria-label="label">
    <table class="performance-table">
      <caption class="sr-only">{{ label }}</caption>
      <thead><tr><th scope="col">{{ heading }}</th><th scope="col">Rendimento</th><th scope="col" title="Ganho dividido pelo saldo inicial do período mais os aportes. Não é taxa anualizada nem TIR.">Ganho / base</th><th scope="col">Aportes</th><th scope="col">Resgates</th><th scope="col">Proventos</th><th scope="col">Saldo ao fim</th><th scope="col">Cobertura</th></tr></thead>
      <tbody><tr v-for="row in rows" :key="row.key">
        <th scope="row">{{ row.label || row.key }}</th>
        <td :class="{ 'performance-loss': row.gainCents < 0 }">{{ row.gainCents == null ? '—' : money(row.gainCents) }}<small v-if="!row.complete && row.count">{{ money(row.observedGainCents) }} de variação registrada</small></td>
        <td>{{ pct(row.gainPercent) }}</td><td>{{ money(row.depositsCents) }}</td><td>{{ money(row.withdrawalsCents) }}</td><td>{{ money(row.incomeCents) }}</td><td>{{ money(row.balanceCents) }}</td><td><small>{{ periodStatus(row) }}</small></td>
      </tr></tbody>
    </table>
  </div>
</template>
<style>
.performance-table-wrap { overflow-x: auto; max-width: 100%; }
.performance-table { border-collapse: collapse; width: 100%; font-size: .82rem; }
.performance-table th, .performance-table td { padding: .8rem .65rem; border-bottom: 1px solid var(--line); text-align: right; white-space: nowrap; }
.performance-table th:first-child { text-align: left; }
.performance-table small { display: block; white-space: normal; min-width: 110px; color: var(--muted); font-size: .73rem; }
.performance-loss { color: var(--red); }
</style>
