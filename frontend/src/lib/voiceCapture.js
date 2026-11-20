import frameProcessor from './voiceFrames.worklet.js?url&no-inline'

const PREROLL_FRAMES = 24
const END_SILENCE_MS = 900
const MAX_TURN_MS = 45000

export async function openVoiceCapture({ signal, isPlaying, canInterruptPlayback, onSpeech, onUtterance, onLevel, onError }) {
  const AudioContext = window.AudioContext || window.webkitAudioContext
  if (!navigator.mediaDevices?.getUserMedia || !AudioContext || !window.AudioWorkletNode)
    throw new Error('Para usar o microfone, abra a Ayla em HTTPS em um navegador com suporte a áudio.')

  const context = new AudioContext()
  let stream, source, processor, muted = false
  let preroll = [], frames = null, onset = 0, silence = 0, duration = 0, voiced = 0
  let noise = 0.003
  let blockedPlayback = false, echoUntil = 0
  function reset() {
    preroll = []
    frames = null
    onset = silence = duration = voiced = 0
  }
  function close() {
    signal.removeEventListener('abort', close)
    if (processor) {
      processor.port.onmessage = null
      processor.port.close()
      processor.disconnect()
    }
    source?.disconnect()
    stream?.getTracks().forEach((track) => track.stop())
    if (context.state !== 'closed') void context.close().catch(() => {})
    reset()
  }
  function finish() {
    if (!frames) return
    const captured = frames
    const hasSpeech = voiced >= 180
    reset()
    onUtterance(hasSpeech ? wav(captured, context.sampleRate) : null)
  }
  try {
    signal.throwIfAborted()
    signal.addEventListener('abort', close, { once: true })
    const ready = context.resume()
    void ready.catch(() => {})
    stream = await navigator.mediaDevices.getUserMedia({
      audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
    })
    signal.throwIfAborted()
    await ready
    await context.audioWorklet.addModule(frameProcessor)
    signal.throwIfAborted()
    source = context.createMediaStreamSource(stream)
    processor = new AudioWorkletNode(context, 'ayla-voice-frames', { channelCount: 1 })
    const track = stream.getAudioTracks()[0]
    track.addEventListener('ended', () => onError('O microfone foi desativado.'), { signal })
    processor.addEventListener('processorerror', () => onError('A captura de áudio foi interrompida.'), { signal })
    processor.port.onmessage = ({ data: { rms, samples } }) => {
      if (muted || signal.aborted) return
      const playing = isPlaying()
      if (playing && !canInterruptPlayback()) {
        if (!blockedPlayback) reset()
        blockedPlayback = true
        onLevel(0)
        return
      }
      if (blockedPlayback) {
        blockedPlayback = false
        echoUntil = performance.now() + 250
      }
      if (performance.now() < echoUntil) return
      const threshold = Math.max(playing ? 0.027 : 0.009, noise * (playing ? 5 : 3.2))
      const frameMs = (samples.length / context.sampleRate) * 1000
      const loud = rms >= threshold
      onLevel(Math.min(1, rms * 8))
      if (frames) {
        frames.push(samples)
        duration += frameMs
        if (rms >= threshold * 0.65) {
          silence = 0
          voiced += frameMs
        } else silence += frameMs
        if (silence >= END_SILENCE_MS) finish()
        else if (duration >= MAX_TURN_MS) {
          reset()
          onUtterance(null, 'A fala passou de 45 segundos. Tente de novo com uma pausa entre as partes.')
        }
        return
      }
      if (!playing && !loud) noise = 0.97 * noise + 0.03 * rms
      preroll.push(samples)
      if (preroll.length > PREROLL_FRAMES) preroll.shift()
      onset = loud ? onset + frameMs : Math.max(0, onset - frameMs * 2)
      if (onset < (playing ? 210 : 100)) return
      frames = preroll
      preroll = []
      voiced = onset
      duration = frames.length * frameMs
      silence = 0
      onSpeech()
    }
    source.connect(processor)
    // The node only observes the mic. Its unmodified output is silence, so it never monitors the user's mic.
    processor.connect(context.destination)
    return {
      finish,
      setMuted(value) {
        muted = value
        track.enabled = !muted
        reset()
        onLevel(0)
      },
    }
  } catch (error) {
    close()
    throw error
  }
}

function wav(frames, inputRate) {
  const rate = 16000
  const pcm = new Float32Array(frames.reduce((length, frame) => length + frame.length, 0))
  let offset = 0
  for (const frame of frames) {
    pcm.set(frame, offset)
    offset += frame.length
  }
  const ratio = inputRate / rate
  const size = Math.floor(pcm.length / ratio)
  const buffer = new ArrayBuffer(44 + size * 2)
  const view = new DataView(buffer)
  for (const [start, tag] of [[0, 'RIFF'], [8, 'WAVE'], [12, 'fmt '], [36, 'data']]) {
    for (let i = 0; i < tag.length; i++) view.setUint8(start + i, tag.charCodeAt(i))
  }
  view.setUint32(4, buffer.byteLength - 8, true)
  view.setUint32(16, 16, true)
  view.setUint16(20, 1, true)
  view.setUint16(22, 1, true)
  view.setUint32(24, rate, true)
  view.setUint32(28, rate * 2, true)
  view.setUint16(32, 2, true)
  view.setUint16(34, 16, true)
  view.setUint32(40, size * 2, true)
  for (let i = 0; i < size; i++) {
    const from = Math.floor(i * ratio)
    const to = Math.max(from + 1, Math.floor((i + 1) * ratio))
    let sample = 0
    for (let j = from; j < to; j++) sample += pcm[j]
    sample = Math.max(-1, Math.min(1, sample / (to - from)))
    view.setInt16(44 + i * 2, sample * (sample < 0 ? 32768 : 32767), true)
  }
  return new Blob([buffer], { type: 'audio/wav' })
}
