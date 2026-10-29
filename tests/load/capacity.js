// Progressive read-only test. Import the same requests/preflight as the small smoke test.
import run, { setup as preflight } from './api.js'

const levels = (__ENV.LEVELS || '5,10,25,50').split(',').map(Number)
if (!levels.length || levels.length > 12 || levels.some(n => !Number.isInteger(n) || n < 1 || n > 1000)) {
  throw new Error('LEVELS deve conter até 12 números inteiros entre 1 e 1000, separados por vírgulas.')
}
const stages = levels.flatMap(target => [
  { duration: __ENV.RAMP_DURATION || '15s', target },
  { duration: __ENV.HOLD_DURATION || '45s', target },
])
stages.push({ duration: '15s', target: 0 })
export const options = {
  scenarios: {
    capacity: { executor: 'ramping-vus', startVUs: 0, stages, gracefulRampDown: '10s' },
  },
  thresholds: {
    'http_req_failed{phase:load}': [
      'rate<0.01',
      { threshold: 'rate<0.05', abortOnFail: true, delayAbortEval: '30s' },
    ],
    'http_req_duration{phase:load}': ['p(95)<' + (__ENV.P95_MS || '1500')],
    checks: ['rate>0.99'],
  },
}
export const setup = preflight
export default run
