function localPortugueseVoice() {
  const voices = window.speechSynthesis?.getVoices() || []
  const portuguese = voices.filter(({ localService, lang }) => localService && /^pt(?:[-_]|$)/i.test(lang))
  const brazilian = portuguese.filter(({ lang }) => /^pt[-_]br$/i.test(lang))
  return brazilian.find(({ name }) => /luciana|maria|fernanda|francisca|vit[oó]ria/i.test(name))
    || brazilian.find(({ default: preferred }) => preferred)
    || brazilian[0] || portuguese[0]
}

export function primeDeviceSpeech() {
  const voice = localPortugueseVoice()
  if (!voice || !window.SpeechSynthesisUtterance) return
  const silent = new SpeechSynthesisUtterance('.')
  silent.voice = voice
  silent.lang = voice.lang
  silent.volume = 0
  window.speechSynthesis.speak(silent)
}

export function playDeviceSpeech(turn, { onEnd, onError }) {
  const voice = localPortugueseVoice()
  if (!voice || !window.SpeechSynthesisUtterance) return null
  const synth = window.speechSynthesis
  synth.cancel()
  const spoken = new SpeechSynthesisUtterance(turn.speech)
  const chuckle = turn.chuckle ? new SpeechSynthesisUtterance('Ha, ha!') : null
  const lines = chuckle ? [chuckle, spoken] : [spoken]
  let finished = false
  let startTimeout
  function finish(callback) {
    if (finished) return
    finished = true
    clearTimeout(startTimeout)
    callback()
  }
  function speak(line) {
    startTimeout = setTimeout(() => finish(() => {
      synth.cancel()
      onError()
    }), 2500)
    synth.speak(line)
  }
  for (const line of lines) {
    line.voice = voice
    line.lang = voice.lang
    line.rate = line === chuckle ? 1.17 : 1.05
    line.pitch = line === chuckle ? 1.13 : 1
    line.onstart = () => clearTimeout(startTimeout)
    line.onerror = () => finish(onError)
  }
  spoken.onend = () => finish(onEnd)
  if (chuckle) chuckle.onend = () => {
    if (finished) return
    clearTimeout(startTimeout)
    speak(spoken)
  }
  synth.resume()
  speak(lines[0])
  return () => {
    finished = true
    clearTimeout(startTimeout)
    for (const line of lines) line.onstart = line.onend = line.onerror = null
    synth.cancel()
  }
}
