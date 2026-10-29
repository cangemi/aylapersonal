"""Update the pinned image's obsolete Edge SDK using upstream 7.3.1 (HTTP 403 fix).
Hermes PM owns the runtime environment; the adapter also forwards configured Edge pitch.
"""
from pathlib import Path
import os
import subprocess
import pm

root = Path('/opt/hermes')
project = root / 'pyproject.toml'
original = project.read_text()
if 'edge-tts==7.2.7' in original:
    project.write_text(original.replace('edge-tts==7.2.7', 'edge-tts==7.3.1'))
    try:
        subprocess.run([str(pm.uv_launcher('uv')), 'lock', '--python', '/opt/hermes/.venv/bin/python',
                        '--upgrade-package', 'edge-tts'], cwd=root,
                       env=dict(os.environ, UV_CACHE_DIR='/tmp/ayla-voice-uv-cache'), check=True, timeout=180)
    except BaseException:
        project.write_text(original)
        raise

# The pinned Hermes adapter passes voice/rate but omits Edge's supported pitch.
# Forward the configured subtle pitch without changing the selected voice/provider.
provider = root / 'tools/tts_tool_providers.py'
source = provider.read_text()
original_kwargs = 'kwargs = {"voice": edge_config.get("voice", DEFAULT_EDGE_VOICE)}'
with_pitch = 'kwargs = {"voice": edge_config.get("voice", DEFAULT_EDGE_VOICE), "pitch": str(edge_config.get("pitch", "+0Hz"))}'
if original_kwargs in source:
    provider.write_text(source.replace(original_kwargs, with_pitch, 1))
elif with_pitch not in source:
    raise RuntimeError('Review Edge pitch compatibility for this Hermes version')

# Preview uses request-local Edge parameters, never PUT /api/config or temporary files.
# Kept as a guarded, idempotent extension of the pinned authenticated dashboard router.
audio_router = root / 'hermes_cli/web_routers/audio.py'
source = audio_router.read_text()
preview_marker = '# AYLA_STATELESS_VOICE_PREVIEW_V1'
if preview_marker not in source:
    if '@router.post("/api/audio/speak")' not in source:
        raise RuntimeError('Review audio preview compatibility for this Hermes version')
    audio_router.write_text(source + '''

# AYLA_STATELESS_VOICE_PREVIEW_V1
from pydantic import BaseModel as _AylaBaseModel, Field as _AylaField
from typing import Literal as _AylaLiteral

class _AylaVoicePreview(_AylaBaseModel):
    model_config = {"extra": "forbid"}
    voice: _AylaLiteral["pt-BR-FranciscaNeural", "pt-BR-ThalitaMultilingualNeural", "pt-BR-AntonioNeural"]
    speed: float = _AylaField(ge=0.75, le=1.50)
    pitchHz: int = _AylaField(ge=-50, le=50, strict=True)

@router.post("/api/audio/ayla-preview")
async def ayla_voice_preview(payload: _AylaVoicePreview):
    with http_failure("Voice preview failed", 500, "Voice preview unavailable"):
        import edge_tts
        speech = edge_tts.Communicate(
            "Oi! Que bom conversar com você. Vamos deixar essa voz do seu jeito?",
            voice=payload.voice,
            rate=f"{round((payload.speed - 1.0) * 100):+d}%",
            pitch=f"{payload.pitchHz:+d}Hz")
        audio = bytearray()
        async for chunk in speech.stream():
            if chunk["type"] == "audio":
                audio.extend(chunk["data"])
                if len(audio) > 6 * 1024 * 1024:
                    raise HTTPException(status_code=413, detail="Preview exceeds size limit")
        if not audio:
            raise HTTPException(status_code=502, detail="Empty preview")
        return {"ok": True, "provider": "edge", "mime_type": "audio/mpeg",
                "data_url": "data:audio/mpeg;base64," + base64.b64encode(audio).decode("ascii")}
''')

