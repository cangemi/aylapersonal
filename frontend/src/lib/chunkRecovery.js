export function isChunkLoadError(error) {
  return /failed to fetch dynamically imported module|importing a module script failed|error loading dynamically imported module|unable to preload css|loading (?:css )?chunk .*failed/i.test(
    error?.message || '',
  )
}

// A tab open during a deployment can still reference removed, versioned chunks.
// Reload only navigation, once per minute, preserving the existing login/session.
export function createChunkRecovery({ storage, navigate, now = Date.now }) {
  let scheduled = false
  return (error, target = '/') => {
    if (!isChunkLoadError(error) || scheduled) return false
    try {
      const time = now(),
        previous = Number(storage.getItem('ayla.chunk-recovery'))
      if (previous && time - previous < 60000) return false
      storage.setItem('ayla.chunk-recovery', String(time))
      scheduled = true
      const safeTarget =
        target.startsWith('/') && !target.startsWith('//') && !target.includes('\\') ? target : '/'
      navigate(safeTarget)
      return true
    } catch {
      // Without persistent loop protection, leave a manual retry instead.
      return false
    }
  }
}
