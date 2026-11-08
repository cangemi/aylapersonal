"""Offline source-size audit. Does not start Hermes, run tests or contact any model/API."""
import importlib.util
import json
from pathlib import Path
import re
import sys
import textwrap

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]


def measure():
    path = ROOT / 'docker/hermes/plugins/ayla-finance/__init__.py'
    spec = importlib.util.spec_from_file_location('ayla_schema_source_audit', path)
    plugin = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(plugin)  # Defines local contracts only; never calls register/handlers.
    groups = {}
    for name in ['TOOLS', 'INVESTMENT_TOOLS', 'MEMORY_TOOLS', 'HOUSEHOLD_TOOLS', 'CALENDAR_TOOLS', 'ALARM_TOOLS', 'CONTEXT_TOOLS']:
        schemas = [{'name': key, 'description': description, 'parameters': {'type': 'object', 'properties': properties,
                    'required': required, 'additionalProperties': False}}
                   for key, (description, properties, required) in getattr(plugin, name).items()]
        groups[name] = {'count': len(schemas), 'json_utf8_bytes': len(json.dumps(schemas, ensure_ascii=False, separators=(',', ':')).encode())}
    service = (ROOT / 'src/main/java/ayla/chat/service/ChatService.java').read_text(encoding='utf-8')
    service = service[service.index('body.put("instructions"'):service.index('var result = client.post')]
    literal_bytes = sum(len(json.loads(value).encode()) for value in re.findall(r'"(?:\\.|[^"\\])*"', service))
    policy = (ROOT / 'src/main/java/ayla/chat/service/AylaInstructions.java').read_text(encoding='utf-8')
    policy_bytes = sum(len(textwrap.dedent(block).encode()) for block in re.findall(r'"""(.*?)"""', policy, re.S))
    result = {'kind': 'source_measurement_not_provider_tokens',
              'soul_utf8_bytes': (ROOT / 'docker/hermes/SOUL.md').stat().st_size,
              'java_instruction_literals_utf8_bytes': literal_bytes + policy_bytes,
              'custom_tools': groups, 'real_tokens': None,
              'note': 'Static UTF-8 source literals and compact schema JSON. Excludes dynamic data and the Hermes built-in prompt. Not wire tokenization, billing or a quality benchmark.'}
    baseline = json.loads((ROOT / 'docs/ai-context-baseline.json').read_text(encoding='utf-8'))
    before = baseline['soul_utf8_bytes'] + baseline['java_instruction_literals_utf8_bytes']
    after = result['soul_utf8_bytes'] + result['java_instruction_literals_utf8_bytes']
    tools_before = sum(item['json_utf8_bytes'] for item in baseline['custom_tools'].values())
    tools_after = sum(item['json_utf8_bytes'] for item in groups.values())
    result['comparison'] = {'static_instruction_bytes_before': before, 'static_instruction_bytes_after': after,
        'static_instruction_bytes_reduction_percent': round((before - after) / before * 100, 2),
        'custom_schema_bytes_before': tools_before, 'custom_schema_bytes_after': tools_after,
        'custom_schema_bytes_reduction_percent': round((tools_before - tools_after) / tools_before * 100, 2),
        'real_input_token_reduction_percent': None, 'latency_change_ms': None}
    return result


if __name__ == '__main__':
    data = measure()
    (ROOT / 'docs/ai-context-after.json').write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(data['comparison'], ensure_ascii=False))
