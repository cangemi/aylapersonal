import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { SourceTextModule, SyntheticModule, createContext } from 'node:vm'

const source = await readFile(new URL('./api.js', import.meta.url), 'utf8')
async function script(responses) {
  const requests = []
  const context = createContext({ __ENV: { BASE_URL: 'http://api.test' }, console: { info() {} } })
  const http = {
    expectedStatuses: (...statuses) => statuses,
    get(url, params) {
      requests.push({ url, params })
      const response = responses.shift()
      assert.ok(response, 'Unexpected extra request')
      return { status: response.status, json() {
        if (response.invalidJson) throw new Error('Not JSON')
        return response.health || 'UP'
      } }
    },
  }
  const module = new SourceTextModule(source, { context })
  await module.link(name => {
    if (name === 'k6/http') return new SyntheticModule(['default'], function () { this.setExport('default', http) }, { context })
    return new SyntheticModule(['check', 'sleep', 'fail'], function () {
      this.setExport('check', () => true)
      this.setExport('sleep', () => {})
      this.setExport('fail', message => { throw new Error(message) })
    }, { context })
  })
  await module.evaluate()
  return { api: module.namespace, requests }
}

test('404 discovery selects the available Actuator route and every load request reuses it', async () => {
  for (const [missing, selected] of [[1, '/actuator/health/readiness'], [2, '/actuator/health']]) {
    const responses = [...Array.from({ length: missing }, () => ({ status: 404 })), { status: 200 }, { status: 200 }]
    const { api, requests } = await script(responses)
    const data = api.setup()
    assert.equal(data.path, selected)
    api.default(data)
    assert.equal(requests.at(-1).url, 'http://api.test' + selected)
    assert.equal(requests.at(-1).params.tags.phase, 'load')
    assert.equal(requests[0].params.tags.phase, 'preflight')
    assert.ok(Object.hasOwn(api.options.thresholds, 'http_req_failed{phase:load}'))
  }
})

test('the observability profile uses readyz directly', async () => {
  const { api, requests } = await script([{ status: 200 }, { status: 200 }])
  const data = api.setup()
  api.default(data)
  assert.equal(data.path, '/readyz')
  assert.equal(requests.length, 2)
  assert.ok(requests.every(r => r.url === 'http://api.test/readyz'))
})

test('unhealthy services and HTML/invalid health responses never fall back to a healthy endpoint', async () => {
  for (const response of [{ status: 503 }, { status: 200, invalidJson: true }, { status: 200, health: 'DOWN' }]) {
    const { api, requests } = await script([response, { status: 200 }])
    assert.throws(() => api.setup(), /Pré-verificação falhou/)
    assert.equal(requests.length, 1)
  }
})
