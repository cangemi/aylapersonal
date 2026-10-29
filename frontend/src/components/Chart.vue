<script setup>
import {
  Chart,
  BarController,
  BarElement,
  CategoryScale,
  LinearScale,
  Tooltip,
  Legend,
} from 'chart.js'
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { money } from '../lib/format'
Chart.register(BarController, BarElement, CategoryScale, LinearScale, Tooltip, Legend)
const props = defineProps({ series: { type: Array, default: () => [] } })
const canvas = ref()
let chart, observer
function render() {
  chart?.destroy()
  const styles = getComputedStyle(document.documentElement),
    text = styles.getPropertyValue('--muted').trim(),
    grid = styles.getPropertyValue('--line').trim()
  chart = new Chart(canvas.value, {
    type: 'bar',
    data: {
      labels: props.series.map((s) =>
        /^\d{4}-\d{2}-\d{2}$/.test(s.label)
          ? s.label.slice(8) + '/' + s.label.slice(5, 7)
          : s.label,
      ),
      datasets: [
        {
          label: 'Receitas',
          data: props.series.map((s) => s.incomeCents),
          backgroundColor: '#81c6ac',
          borderRadius: 4,
          maxBarThickness: 18,
        },
        {
          label: 'Despesas',
          data: props.series.map((s) => s.expenseCents),
          backgroundColor: '#b7a0ef',
          borderRadius: 4,
          maxBarThickness: 18,
        },
      ],
    },
    options: {
      animation: window.matchMedia('(prefers-reduced-motion: reduce)').matches
        ? false
        : { duration: 400 },
      responsive: true,
      maintainAspectRatio: false,
      interaction: { mode: 'index', intersect: false },
      plugins: {
        legend: { display: false },
        tooltip: { callbacks: { label: (item) => `${item.dataset.label}: ${money(item.raw)}` } },
      },
      scales: {
        x: {
          grid: { display: false },
          border: { display: false },
          ticks: { color: text, maxTicksLimit: 8 },
        },
        y: {
          grid: { color: grid },
          border: { display: false },
          ticks: {
            color: text,
            maxTicksLimit: 5,
            precision: 0,
            callback: (v) => money(Math.round(v)),
          },
        },
      },
    },
  })
}
onMounted(() => {
  render()
  observer = new MutationObserver(render)
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
})
watch(
  () => props.series,
  () => {
    if (canvas.value) render()
  },
  { deep: true },
)
onBeforeUnmount(() => {
  chart?.destroy()
  observer?.disconnect()
})
</script>
<template>
  <div class="chart">
    <canvas ref="canvas" role="img" aria-label="Gráfico de receitas e despesas do período" />
  </div>
</template>
