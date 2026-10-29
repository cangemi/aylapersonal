// Read-only load test; never calls AI providers or writes financial records.
import http from 'k6/http'
import { check, sleep, fail } from 'k6'

export const options = {
  vus: Number(__ENV.VUS || 10),
  duration: __ENV.DURATION || '30s',
  thresholds: {
    'http_req_failed{phase:load}': ['rate<0.01'],
    'http_req_duration{phase:load}': ['p(95)<' + (__ENV.P95_MS || '1500')],
    checks: ['rate>0.99'],
  },
}
const base = (__ENV.BASE_URL || 'http://api-gateway:8080').replace(/\/$/, '')
const financePath = '/api/v1/finance/entries?page=0&size=10'
function parameters(phase, healthProbe = false) {
  return {
    headers: __ENV.ACCESS_TOKEN ? { Authorization: 'Bearer ' + __ENV.ACCESS_TOKEN } : {},
    tags: { name: __ENV.ACCESS_TOKEN ? 'finance-read' : 'readiness', phase },
    timeout: '10s',
    responseType: healthProbe ? 'text' : 'none',
    responseCallback: healthProbe ? http.expectedStatuses(200, 404) : http.expectedStatuses(200),
  }
}
export function setup() {
  if (__ENV.ACCESS_TOKEN) {
    const result = http.get(base + financePath, parameters('preflight'))
    if (result.status !== 200) fail('Pré-verificação financeira falhou (HTTP ' + result.status + '). Confira a API e a validade do token. A carga não foi iniciada.')
    console.info('Pré-verificação OK. Endpoint: ' + financePath)
    return { path: financePath }
  }
  const candidates = __ENV.READINESS_PATH
    ? [__ENV.READINESS_PATH]
    : ['/readyz', '/actuator/health/readiness', '/actuator/health']
  for (const path of candidates) {
    const result = http.get(base + path, parameters('preflight', true))
    if (result.status === 404) continue
    if (result.status !== 200) fail('Pré-verificação falhou em ' + path + ' (HTTP ' + result.status + '). Confira a saúde da API. A carga não foi iniciada.')
    let healthy = false
    try { healthy = result.json('status') === 'UP' } catch (_) { /* Reject HTML/login pages returning HTTP 200. */ }
    if (!healthy) fail('Pré-verificação falhou: ' + path + ' não retornou um indicador JSON status=UP. Confira o endereço da API. A carga não foi iniciada.')
    console.info('Pré-verificação OK. Endpoint: ' + path)
    return { path }
  }
  fail('Nenhum endpoint de saúde foi encontrado (HTTP 404). Confira BASE_URL ou informe READINESS_PATH. A carga não foi iniciada.')
}
export default function (data) {
  if (!data || !data.path) fail('Execute a pré-verificação antes da carga; não use --no-setup.')
  const result = http.get(base + data.path, parameters('load'))
  check(result, { 'status 200': r => r.status === 200 })
  sleep(0.2)
}
