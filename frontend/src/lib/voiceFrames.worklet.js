class VoiceFrames extends AudioWorkletProcessor {
  constructor() {
    super()
    this.frameSize = Math.round(sampleRate * 0.02)
    this.frame = new Float32Array(this.frameSize)
    this.offset = 0
    this.energy = 0
  }

  process(inputs) {
    const input = inputs[0]?.[0]
    if (!input) return true
    for (const sample of input) {
      this.frame[this.offset++] = sample
      this.energy += sample * sample
      if (this.offset === this.frameSize) {
        this.port.postMessage(
          { samples: this.frame, rms: Math.sqrt(this.energy / this.frameSize) },
          [this.frame.buffer],
        )
        this.frame = new Float32Array(this.frameSize)
        this.offset = 0
        this.energy = 0
      }
    }
    return true
  }
}

registerProcessor('ayla-voice-frames', VoiceFrames)
