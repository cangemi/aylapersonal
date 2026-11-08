"""Apply Ayla's free-only runtime policy, then keep the official image bootstrap."""
import os
from pathlib import Path
import json
import shutil
import subprocess
import secrets

MANAGED_PLUGINS = ("ayla-research", "ayla-finance", "ayla-provider-monitor")

# Native dashboard speech defaults to WhatsApp-compatible Opus; session contexts override this.
os.environ.setdefault("HERMES_SESSION_PLATFORM", "whatsapp")
os.environ.setdefault("AYLA_PROVIDER_MONITOR_BACKGROUND", "1")
home = Path(os.environ.get("HERMES_HOME", "/opt/data"))
home.mkdir(parents=True, exist_ok=True)
path = home / "config.yaml"
# JSON is valid YAML. This file is managed by Ayla; auth and sessions use separate files.
config = {}
config["model"] = {"provider": "nous", "default": "nous/welcome"}
# Fallback chain keeps chat working when the primary free model is rate-limited by
# Nous; models must stay on Nous and remain free-only (see ayla.hermes.HermesModels).
config["fallback_model"] = [
    {"provider": "nous", "model": model} for model in (
        "upstage/solar-mini4:free", "meituan/longcat-2.5:free",
        "reflection/beam-1.0-501b-a23b:free", "stealth/crouton-alpha")]
config["stt"] = {"enabled": True, "provider": "local", "language": "pt", "local": {"model": "base", "device": "cpu", "compute_type": "int8", "language": "pt", "unload_after_idle_seconds": 300}}
# Java reapplies the PostgreSQL voice settings before synthesis; these are initial defaults.
config["tts"] = {"provider": "edge", "edge": {"voice": "pt-BR-FranciscaNeural", "speed": 1.02, "pitch": "+2Hz"}}
config["compression"] = {"enabled": False}
config["context"] = {"engine": "ayla-conversation"}
config["agent"] = {"max_turns": 8, "reasoning_effort": {"enabled": False}, "api_max_retries": 1, "auto_recovery_cycles": 0}
# Use native progressive disclosure; configure.py checks image compatibility before startup.
config["tools"] = {"tool_search": {"enabled": "on", "listing": "auto"}, "connectors": {"enabled": False}}
config["auxiliary"] = {
    "vision": {"provider": "nous", "model": "stepfun/step-3.7-flash:free"},
    "title_generation": {"enabled": False, "model_upgrade_enabled": False},
    "background_review": {"enabled": False},
}
config["providers"] = {"nous": {"models": {"stepfun/step-3.7-flash:free": {"supports_vision": True}}}}
config["platform_toolsets"] = {"api_server": ["search", "ayla_memory", "ayla_finance", "ayla_household", "ayla_context"], "cli": ["search", "ayla_memory", "ayla_finance", "ayla_household", "ayla_context"]}
config["memory"] = {
    "memory_enabled": False,
    "user_profile_enabled": False,
    "memory_char_limit": 2200,
    "user_char_limit": 1375,
    "nudge_interval": 5,
}
config["plugins"] = {"enabled": ["ayla-research", "ayla-finance"], "hook_callback_timeout": 120, "entries": {"ayla-research": {"llm": {"allow_provider_override": True, "allowed_providers": ["nous"], "allow_model_override": True, "allowed_models": ["*"]}}}}
config.setdefault("web", {}).update({"backend": "ddgs", "search_backend": "ddgs", "keyless_fallback": False})
path.write_text(json.dumps(config, ensure_ascii=False, indent=2))
for plugin_id in MANAGED_PLUGINS:
    plugin = home / "plugins" / plugin_id
    plugin.mkdir(parents=True, exist_ok=True)
    for name in ("plugin.yaml", "__init__.py"):
        shutil.copyfile(Path("/opt/ayla/plugins") / plugin_id / name, plugin / name)
persona = Path("/opt/ayla/SOUL.md")
active_persona = home / "SOUL.md"
prior_managed_persona = active_persona.read_bytes() if active_persona.is_file() else None
if active_persona.exists() and active_persona.read_bytes() != persona.read_bytes():
    backup = home / "SOUL.md.before-ayla"
    if not backup.exists():
        shutil.copyfile(active_persona, backup)
shutil.copyfile(persona, active_persona)
# Seed only the explicitly requested communication preference; preserve existing memories.
user_memory = home / "memories" / "USER.md"
if not user_memory.exists():
    user_memory.parent.mkdir(parents=True, exist_ok=True)
    user_memory.write_text(
        "Prefere conversar em português brasileiro, de forma informal, dinâmica e divertida, "
        "com sugestões práticas quando forem úteis.\n", encoding="utf-8")
# Provision native search and local Whisper through Hermes PM before the gateway starts.
# Keep package state writable by the same user that runs Hermes (not root).
import pwd
hermes_user = pwd.getpwnam("hermes")
os.chown(home, hermes_user.pw_uid, hermes_user.pw_gid)
# New users clone a neutral template, never the owner's data or credentials.
template = home / "profiles" / "ayla-internal-template"
marker = template / ".ayla-neutral-template"
if template.is_symlink() or marker.is_symlink() or (template.exists() and not marker.is_file()):
    raise RuntimeError("Reserved neutral Hermes template is not managed by Ayla")
template.mkdir(parents=True, exist_ok=True)
marker.write_text("1\n", encoding="utf-8")
for folder in ("memories", "sessions", "plugins", "skills", "logs", "workspace"):
    target = template / folder
    if target.is_symlink():
        raise RuntimeError("Neutral Hermes template contains a symlink")
    target.mkdir(exist_ok=True)
for relative, content in {
    "config.yaml": json.dumps(config, ensure_ascii=False, indent=2),
    "SOUL.md": persona.read_text(encoding="utf-8"),
    ".env": "API_SERVER_KEY=" + secrets.token_hex(24) + "\n",
}.items():
    target = template / relative
    if target.is_symlink():
        raise RuntimeError("Neutral Hermes template file is a symlink")
    target.write_text(content, encoding="utf-8")
for plugin_id in MANAGED_PLUGINS:
    target = template / "plugins" / plugin_id
    if target.is_symlink():
        raise RuntimeError("Neutral Hermes plugin path is a symlink")
    target.mkdir(exist_ok=True)
    for filename in ("plugin.yaml", "__init__.py"):
        destination = target / filename
        if destination.is_symlink():
            raise RuntimeError("Neutral Hermes plugin file is a symlink")
        shutil.copyfile(Path("/opt/ayla/plugins") / plugin_id / filename, destination)
for target in [template, *template.rglob("*")]:
    if target.is_symlink():
        raise RuntimeError("Neutral Hermes template contains a symlink")
    os.chown(target, hermes_user.pw_uid, hermes_user.pw_gid)
# Update only the managed tool definitions in existing isolated user profiles.
# Preserve their provider login, model, API keys, memories and sessions.
for profile in (home / "profiles").glob("u-*"):
    if not profile.is_dir() or profile.is_symlink():
        continue
    profile_persona = profile / "SOUL.md"
    if profile_persona.is_symlink():
        raise RuntimeError("User profile persona is a symlink")
    if profile_persona.is_file() and prior_managed_persona is not None and profile_persona.read_bytes() == prior_managed_persona:
        backup_persona = profile / "SOUL.md.before-ayla-context"
        if backup_persona.is_symlink():
            raise RuntimeError("User profile persona backup is a symlink")
        if not backup_persona.exists():
            shutil.copyfile(profile_persona, backup_persona)
        shutil.copyfile(persona, profile_persona)
        os.chown(profile_persona, hermes_user.pw_uid, hermes_user.pw_gid)
    # Sync the same managed plugins as the owner and neutral template, including research.
    # User config, credentials, memories and sessions remain untouched.
    for plugin_id in MANAGED_PLUGINS:
        if (profile / "plugins").is_symlink():
            raise RuntimeError("User profile plugin root contains a symlink")
        target = profile / "plugins" / plugin_id
        if target.is_symlink():
            raise RuntimeError("User profile plugin contains a symlink")
        target.mkdir(parents=True, exist_ok=True)
        for filename in ("plugin.yaml", "__init__.py"):
            destination = target / filename
            if destination.is_symlink():
                raise RuntimeError("User profile plugin file contains a symlink")
            shutil.copyfile(Path("/opt/ayla/plugins") / plugin_id / filename, destination)
            os.chown(destination, hermes_user.pw_uid, hermes_user.pw_gid)
        os.chown(target, hermes_user.pw_uid, hermes_user.pw_gid)

dependency_env = dict(os.environ, HOME=str(home))
# The authenticated dashboard quota route is independent of each profile's inference process.
for quota_home in [home, template, *(home / "profiles").glob("u-*")]:
    if not quota_home.is_dir():
        continue
    quota_target = quota_home / "plugins" / "ayla-finance" / "dashboard"
    if quota_home.is_symlink() or quota_target.is_symlink() or (quota_target.parent).is_symlink():
        raise RuntimeError("Unsafe Ayla dashboard plugin path")
    quota_target.mkdir(parents=True, exist_ok=True)
    for quota_filename in ("manifest.json", "api.py", "session_state.py"):
        quota_file = quota_target / quota_filename
        if quota_file.is_symlink():
            raise RuntimeError("Unsafe Ayla dashboard plugin file")
        shutil.copyfile(Path("/opt/ayla/plugins/ayla-finance/dashboard") / quota_filename, quota_file)
        os.chown(quota_file, hermes_user.pw_uid, hermes_user.pw_gid)
    os.chown(quota_target, hermes_user.pw_uid, hermes_user.pw_gid)
subprocess.run(["/opt/hermes/.venv/bin/python", "/opt/ayla/plugins/ayla-finance/configure.py", str(home)],
               cwd="/opt/hermes", env=dependency_env, check=True, timeout=30)
# Must run before any gateway/PM process can import Nous authentication.
subprocess.run(["/opt/hermes/.venv/bin/python", "/opt/ayla/prepare_auth.py"],
               cwd="/opt/hermes", env=dependency_env, check=True, timeout=30)
# The official image pins Edge 7.2.7; upstream 7.3.1 fixes the current service handshake.
try:
    subprocess.run(["/opt/hermes/.venv/bin/python", "/opt/ayla/prepare_voice.py"],
                   cwd="/opt/hermes", env=dependency_env, check=True, timeout=120)
except (subprocess.SubprocessError, OSError) as error:
    # Optional speech preparation must not keep the dashboard and OAuth offline.
    # Authentication isolation above remains mandatory and fails startup on error.
    print("[ayla] Voice preparation failed: " + type(error).__name__ + ". Starting dashboard/chat with the installed voice dependencies.", flush=True)
try:
    subprocess.run(["/command/s6-setuidgid", "hermes", "/opt/hermes/.venv/bin/python", "-c",
                    "import pm; pm.sync_venv(['ddgs', 'stt-whisper', 'edge-tts'], explicit=True)"],
                   cwd="/opt/hermes", env=dependency_env, check=True, timeout=180)
except (subprocess.SubprocessError, OSError) as error:
    print("[ayla] Search/audio dependency preparation failed: " + type(error).__name__ + ". Search/audio will report backend failure.", flush=True)
os.execv("/opt/hermes/docker/entrypoint-dispatch.sh", ["entrypoint-dispatch.sh", "gateway", "run"])



