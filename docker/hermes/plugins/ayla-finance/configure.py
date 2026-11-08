"""Update only Ayla-managed context settings, preserving each profile's model and auth."""
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
sys.path.insert(0, '/opt/hermes')
import hermes_yaml as yaml


def apply(home):
    home = Path(home).resolve()
    supported = Path('/opt/hermes/tools/tool_search.py').is_file()
    mode = os.environ.get('AYLA_TOOL_SEARCH_MODE', 'on')
    if mode not in ('on', 'off'):
        raise ValueError('AYLA_TOOL_SEARCH_MODE must be on or off')
    if not supported:
        mode = 'off'
        print('[ayla] Native Tool Search unavailable; retaining complete tool schemas.', flush=True)
    turns = int(os.environ.get('AYLA_AGENT_MAX_TURNS', '8'))
    if not 4 <= turns <= 30:
        raise ValueError('AYLA_AGENT_MAX_TURNS must be between 4 and 30')
    profiles = [home, home / 'profiles' / 'ayla-internal-template', *sorted((home / 'profiles').glob('u-*'))]
    changed = 0
    for profile in profiles:
        path = profile / 'config.yaml'
        if profile.is_symlink() or path.is_symlink() or not profile.resolve().is_relative_to(home):
            raise ValueError('Unsafe profile configuration path')
        if not path.is_file():
            continue
        config = yaml.safe_load(path.read_text(encoding='utf-8')) or {}
        tools = config.setdefault('tools', {})
        search = tools.get('tool_search')
        search = dict(search) if isinstance(search, dict) else {}
        search.update(enabled=mode, listing='auto', search_default_limit=5, max_search_limit=20)
        tools['tool_search'] = search
        # Ayla's existing capabilities are local plugins, not hosted connector discovery.
        tools.setdefault('connectors', {})['enabled'] = False
        config.setdefault('agent', {})['max_turns'] = turns if mode == 'on' else max(4, int(config.get('agent', {}).get('max_turns', 4)))
        # A provider cooldown must not hold an attended conversation in a long retry loop.
        # Model changes happen before new turns in Java, never by replaying an uncertain action.
        config['agent'].update(api_max_retries=1, auto_recovery_cycles=0)
        updated = json.dumps(config, ensure_ascii=False, indent=2) + '\n'
        if updated == path.read_text(encoding='utf-8'):
            continue
        backup = profile / 'config.yaml.before-ayla-context'
        if backup.is_symlink():
            raise ValueError('Unsafe context settings backup')
        if not backup.exists():
            shutil.copy2(path, backup)
        stat = path.stat()
        with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=profile, prefix='.ayla-context-', delete=False) as output:
            temporary = Path(output.name)
            output.write(updated)
        try:
            os.chmod(temporary, stat.st_mode)
            os.chown(temporary, stat.st_uid, stat.st_gid)
            os.replace(temporary, path)
            changed += 1
        finally:
            temporary.unlink(missing_ok=True)
    print(f'[ayla] Context settings: tool_search={mode}; updated_profiles={changed}; native_support={supported}.', flush=True)


if __name__ == '__main__':
    apply(sys.argv[1])
