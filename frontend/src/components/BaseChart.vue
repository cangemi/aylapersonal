<script setup>
import {
  Chart,
  BarController,
  BarElement,
  LineController,
  LineElement,
  PointElement,
  DoughnutController,
  ArcElement,
  CategoryScale,
  LinearScale,
  Tooltip,
  Legend,
  Filler,
} from 'chart.js'
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
Chart.register(
  BarController,
  BarElement,
  LineController,
  LineElement,
  PointElement,
  DoughnutController,
  ArcElement,
  CategoryScale,
  LinearScale,
  Tooltip,
  Legend,
  Filler,
)
// `build(theme)` devolve a configuração do Chart.js; é chamado de novo ao trocar o tema.
const props = defineProps({
  build: { type: Function, required: true },
  label: { type: String, default: 'Gráfico' },
  watchSource: { default: null },
})
const canvas = ref()
let chart, observer
let signature = ''
function render() {
  const styles = getComputedStyle(document.documentElement)
  const config = props.build({
    text: styles.getPropertyValue('--muted').trim(),
    grid: styles.getPropertyValue('--line').trim(),
  })
  const nextSignature = JSON.stringify(config)
  if (chart && signature === nextSignature) return
  signature = nextSignature
  config.data = {
    ...config.data,
    labels: [...(config.data.labels || [])],
    datasets: config.data.datasets.map((dataset) => ({
      ...dataset,
      data: Array.isArray(dataset.data) ? [...dataset.data] : dataset.data,
      ...((dataset.type || config.type) === 'line'
        ? {
            pointRadius: dataset.pointRadius > 0 ? dataset.pointRadius : 2.5,
            pointHoverRadius: 5,
            pointHitRadius: 12,
          }
        : {}),
    })),
  }
  config.options = {
    ...config.options,
    animation:
      !chart && !window.matchMedia('(prefers-reduced-motion: reduce)').matches
        ? { duration: 400 }
        : false,
    transitions: {
      ...config.options?.transitions,
      resize: { animation: { duration: 0 } },
      active: { animation: { duration: 0 } },
    },
  }
  if (chart && chart.config.type === config.type) {
    chart.stop()
    chart.data = config.data
    chart.options = config.options
    chart.update('none')
  } else {
    chart?.destroy()
    chart = new Chart(canvas.value, config)
  }
}
onMounted(() => {
  render()
  observer = new MutationObserver(render)
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
})
watch(
  () => props.watchSource,
  () => canvas.value && render(),
  { deep: true },
)
onBeforeUnmount(() => {
  chart?.destroy()
  observer?.disconnect()
})
</script>
<template>
  <div class="chart"><canvas ref="canvas" role="img" :aria-label="label" /></div>
</template>
