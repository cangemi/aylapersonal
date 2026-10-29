import { describe, it, expect, vi } from 'vitest'
import { createChunkRecovery, isChunkLoadError } from './chunkRecovery'

describe('recuperação de arquivos antigos após publicar o frontend', () => {
  it('recupera a rota mantendo a sessão, sem recarregar em loop', () => {
    const values = new Map([['ayla.session', 'existing-session']])
    const storage = {
      getItem: (key) => values.get(key),
      setItem: (key, value) => values.set(key, value),
    }
    const navigate = vi.fn(),
      error = new TypeError('Failed to fetch dynamically imported module: /assets/Dashboard-old.js')
    let time = 100000
    const options = { storage, navigate, now: () => time }
    const recover = createChunkRecovery(options)
    expect(recover(error, '/financeiro')).toBe(true)
    expect(navigate).toHaveBeenCalledExactlyOnceWith('/financeiro')
    expect(values.get('ayla.session')).toBe('existing-session')
    expect(recover(error, '/chat')).toBe(false)
    expect(createChunkRecovery(options)(error, '/chat')).toBe(false)
    time += 60001
    expect(createChunkRecovery(options)(error, '//other.example')).toBe(true)
    expect(navigate).toHaveBeenLastCalledWith('/')
  })
  it('does not reload for application errors or when loop protection is unavailable', () => {
    const navigate = vi.fn()
    const recover = createChunkRecovery({
      storage: {
        getItem: () => {
          throw new Error('blocked')
        },
      },
      navigate,
    })
    expect(recover(new Error('Dados inválidos.'))).toBe(false)
    expect(recover(new Error('Importing a module script failed.'))).toBe(false)
    expect(isChunkLoadError(new Error('Unable to preload CSS for /assets/old.css'))).toBe(true)
    expect(navigate).not.toHaveBeenCalled()
  })
})
