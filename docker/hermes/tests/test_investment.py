import importlib.util
import json
from io import BytesIO
from pathlib import Path
import unittest
from unittest.mock import Mock, patch

source = Path(__file__).resolve().parents[1] / 'plugins' / 'ayla-finance' / '__init__.py'
spec = importlib.util.spec_from_file_location('portfolio_tools', source)
plugin = importlib.util.module_from_spec(spec)
spec.loader.exec_module(plugin)


class InvestmentToolsTest(unittest.TestCase):
    def test_full_crud_and_analysis_are_exposed_with_versioned_mutations(self):
        self.assertTrue({'portfolio', 'analytics', 'get_position', 'save_position', 'delete_position',
                         'event', 'update_event', 'delete_event'}.issubset(plugin.INVESTMENT_TOOLS))
        self.assertIn('version', plugin.INVESTMENT_TOOLS['delete_position'][2])
        self.assertIn('eventId', plugin.INVESTMENT_TOOLS['update_event'][2])
        self.assertEqual(plugin.INVESTMENT_TOOLS['save_position'][2], [])
    def test_portfolio_uses_owned_session_and_formats_money_without_exposing_client_identity(self):
        response = Mock()
        response.__enter__ = Mock(return_value=BytesIO(json.dumps({'balanceCents': 125050, 'positions': []}).encode()))
        response.__exit__ = Mock(return_value=False)
        with patch.dict(plugin.os.environ, {'AYLA_FINANCE_BRIDGE_KEY': 'x' * 32, 'AYLA_JAVA_BASE_URL': 'http://java:8080'}), \
                patch.object(plugin, 'urlopen', return_value=response) as http:
            result = plugin.call('portfolio', {}, session_id='owned-session', namespace='investment')
            self.assertTrue(result['success'])
            self.assertEqual(result['data']['balance'], 'R$ 1.250,50')
            self.assertEqual(http.call_args.args[0].full_url, 'http://java:8080/internal/hermes/investment/portfolio')
            self.assertEqual(http.call_args.args[0].get_header('X-ayla-session'), 'owned-session')
            rejected = plugin.call('portfolio', {'userId': 'another-user'}, session_id='owned-session', namespace='investment')
            self.assertFalse(rejected['success'])
            self.assertEqual(http.call_count, 1)

    def test_events_require_a_real_position_and_version_before_any_request(self):
        with patch.object(plugin, 'urlopen') as http:
            result = plugin.call('event', {'eventType': 'VALUATION', 'amount': '1000', 'date': '2026-11-04'}, session_id='owned', namespace='investment')
            self.assertFalse(result['success'])
            http.assert_not_called()
