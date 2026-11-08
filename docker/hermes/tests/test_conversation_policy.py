import importlib.util
import json
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import patch

path = Path(__file__).parents[1] / 'plugins/ayla-finance/configure.py'
spec = importlib.util.spec_from_file_location('conversation_policy', path)
policy = importlib.util.module_from_spec(spec)
with patch.dict(sys.modules, {'hermes_yaml': SimpleNamespace(safe_load=json.loads)}):
    spec.loader.exec_module(policy)


class ConversationPolicyTest(unittest.TestCase):
    def test_bounded_retries_preserve_each_profiles_model_credentials_and_tools(self):
        with TemporaryDirectory() as directory:
            home = Path(directory)
            for profile, model in [(home, 'owner-model'), (home / 'profiles/u-alice', 'alice-model')]:
                profile.mkdir(parents=True, exist_ok=True)
                (profile / 'config.yaml').write_text(json.dumps({'model': {'default': model}, 'agent': {'max_turns': 10}, 'platform_toolsets': {'api_server': ['ayla_finance']}}))
                (profile / '.env').write_text('PROFILE_SECRET=unchanged')
            with patch.object(policy.os, 'chown', create=True):
                policy.apply(home)
                before = [(profile / 'config.yaml').read_bytes() for profile in (home, home / 'profiles/u-alice')]
                policy.apply(home)
            for index, (profile, model) in enumerate([(home, 'owner-model'), (home / 'profiles/u-alice', 'alice-model')]):
                data = json.loads((profile / 'config.yaml').read_text())
                self.assertEqual(data['agent']['api_max_retries'], 1)
                self.assertEqual(data['agent']['auto_recovery_cycles'], 0)
                self.assertEqual(data['model']['default'], model)
                self.assertEqual(data['platform_toolsets']['api_server'], ['ayla_finance'])
                self.assertEqual((profile / '.env').read_text(), 'PROFILE_SECRET=unchanged')
                self.assertEqual((profile / 'config.yaml').read_bytes(), before[index])


if __name__ == '__main__':
    unittest.main()
