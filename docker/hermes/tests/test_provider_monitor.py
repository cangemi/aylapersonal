import base64
from datetime import datetime, timezone
import importlib.util
import json
import os
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import patch

path = Path(__file__).parent.parent / 'plugins/ayla-provider-monitor/__init__.py'
spec = importlib.util.spec_from_file_location('provider_monitor_test', path)
monitor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(monitor)


class ProviderMonitorTest(unittest.TestCase):
    def setUp(self):
        self.events = []
        self.context = {'profile': 'u-one', 'credentialScope': 'a' * 64, 'credentialObservedAt': monitor.utc()}
        self.publish = patch.object(monitor, 'publish', self.events.append)
        self.credentials = patch.object(monitor, 'credentials', return_value=self.context)
        self.publish.start()
        self.credentials.start()
        self.addCleanup(self.publish.stop)
        self.addCleanup(self.credentials.stop)
        monitor._attempt.set(None)
        monitor._structured.details = None

    def test_stream_error_during_internal_retry_is_observed_and_scrubbed(self):
        monitor.before_request(provider='nous', model='model-a', session_id='session-one', messages=['PRIVATE'])
        result = monitor.observe_classification(provider='nous', model='model-a', error_body={'reason': 'rate_limited', 'retry_after': 1645, 'alternates': ['model-b'], 'secret': 'TOKEN'})
        self.assertIsNone(result)
        self.assertIsNone(monitor.request_error(provider='nous', model='model-a', session_id='session-one', status_code=429, retry_count=1))
        event = self.events[-1]
        self.assertEqual(event['state'], 'RATE_LIMITED')
        self.assertEqual(event['retryAfterSeconds'], 1645)
        self.assertIsNone(event['retryAttemptAt'])
        self.assertEqual(event['alternates'], ['model-b'])
        self.assertNotIn('PRIVATE', json.dumps(event))
        self.assertNotIn('TOKEN', json.dumps(event))

    def test_error_without_delay_and_success_keep_model_specificity(self):
        monitor.request_error(provider='nous', model='model-a', status_code=429, error={'message': 'Error code: 429 - {"reason":"rate_limited"}'})
        self.assertIsNone(self.events[-1]['retryAfterSeconds'])
        monitor.before_request(provider='nous', model='model-b')
        monitor.request_success(provider='nous', model='model-b')
        self.assertEqual(self.events[-1]['state'], 'AVAILABLE')
        self.assertEqual(self.events[-1]['model'], 'model-b')
        monitor.request_error(provider='nous', model='model-b', status_code=500)
        self.assertEqual(self.events[-1]['state'], 'ERROR')

    def test_real_sdk_python_dict_message_preserves_delay_without_exporting_error_text(self):
        message = "Error code: 429 - " + repr({'status': 429, 'message': "You've reached this model's current fair-share rate limit.", 'reason': 'rate_limited', 'retry_after': 5812, 'alternates': ['other-model:free'], 'secret': 'PRIVATE'})
        monitor.request_error(provider='nous', model='model-a', status_code=429, error={'message': message})
        self.assertEqual(self.events[-1]['retryAfterSeconds'], 5812)
        self.assertEqual(self.events[-1]['alternates'], ['other-model:free'])
        self.assertNotIn('PRIVATE', json.dumps(self.events[-1]))
        self.assertEqual(monitor.error_body("Error - {'retry_after': __import__('os').getcwd()}"), {})

    def test_quota_unknown_is_absent_and_native_known_percentage_is_preserved(self):
        snapshot = types.SimpleNamespace(windows=[types.SimpleNamespace(label='Daily', used_percent=40, reset_at=datetime(2026, 11, 5, tzinfo=timezone.utc), scope='account'), types.SimpleNamespace(label='Tokens', used_percent=None)])
        quotas = monitor.normalize_quotas(snapshot)
        self.assertEqual(len(quotas), 1)
        self.assertEqual(quotas[0]['usagePercentage'], 40)
        self.assertEqual(quotas[0]['period'], 'DAILY')
        self.assertEqual(monitor.normalize_quotas(None), [])

    def test_official_quota_failure_is_explicit(self):
        account_module = types.ModuleType('hermes_cli.nous_account')
        usage_module = types.ModuleType('agent.account_usage')
        account_module.get_nous_portal_account_info = lambda **kw: (_ for _ in ()).throw(TimeoutError())
        usage_module.build_nous_credits_snapshot = lambda account: None
        with patch.dict(sys.modules, {'hermes_cli': types.ModuleType('hermes_cli'), 'agent': types.ModuleType('agent'), 'hermes_cli.nous_account': account_module, 'agent.account_usage': usage_module}):
            self.assertEqual(monitor.query_quota()['quotaStatus'], 'ERROR')

    def test_retry_header_and_invalid_delays(self):
        self.assertEqual(monitor.retry_seconds({}, {'Retry-After': '45'}), 45)
        self.assertIsNone(monitor.retry_seconds({'retry_after': float('nan')}))
        self.assertIsNone(monitor.retry_seconds({'retry_after': True}))

    def test_subscription_credit_percentage_is_not_presented_as_a_token_quota(self):
        snapshot = types.SimpleNamespace(windows=[types.SimpleNamespace(label='Subscription', used_percent=80, reset_at=None, scope='account')])
        account = types.SimpleNamespace(subscription=types.SimpleNamespace(current_period_end='2026-12-01T03:00:00Z'))
        quota = monitor.normalize_quotas(snapshot, account)[0]
        self.assertEqual(quota['label'], 'Créditos da assinatura (US$)')
        self.assertEqual(quota['period'], 'SUBSCRIPTION')
        self.assertEqual(quota['resetsAt'], '2026-12-01T03:00:00+00:00')
        self.assertIsNone(quota['limit'])
        self.assertIsNone(quota['used'])


class CredentialIsolationTest(unittest.TestCase):
    def test_same_account_across_profiles_and_token_refresh_has_same_scope(self):
        native = types.ModuleType('hermes_constants')
        native.get_hermes_home = lambda: '/profile'
        state = {'profile': 'u-one', 'sub': 'account-one', 'nonce': 'one'}
        native.profile_name_for_home = lambda home: state['profile']
        auth = types.ModuleType('hermes_cli.auth')
        def auth_state(provider):
            claims = base64.urlsafe_b64encode(json.dumps({'sub': state['sub'], 'org_id': 'org', 'nonce': state['nonce']}).encode()).decode().rstrip('=')
            return {'access_token': 'header.' + claims + '.signature'}
        auth.get_provider_auth_state = auth_state
        with patch.dict(sys.modules, {'hermes_constants': native, 'hermes_cli.auth': auth}), patch.dict(os.environ, {'AYLA_FINANCE_BRIDGE_KEY': 'secret' * 8}):
            first = monitor.credentials()['credentialScope']
            state.update(profile='u-two', nonce='refreshed')
            self.assertEqual(first, monitor.credentials()['credentialScope'])
            state['sub'] = 'account-two'
            self.assertNotEqual(first, monitor.credentials()['credentialScope'])


if __name__ == '__main__':
    unittest.main()
