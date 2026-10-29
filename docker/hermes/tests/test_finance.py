import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
from urllib.error import HTTPError, URLError
from io import BytesIO

source = Path(__file__).resolve().parents[1] / 'plugins' / 'ayla-finance' / '__init__.py'
spec = importlib.util.spec_from_file_location('ayla_finance', source)
finance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(finance)


class FinanceTest(unittest.TestCase):
    def setUp(self):
        self.environment = patch.dict(finance.os.environ, {
            'AYLA_FINANCE_BRIDGE_KEY': 'test-key-' + 'a' * 32,
            'AYLA_JAVA_BASE_URL': 'http://java:8080'})
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.entry = {'type': 'EXPENSE', 'amountCents': 3590, 'description': 'Almoço', 'category': 'Alimentação'}

    def response(self, value):
        response = Mock()
        response.__enter__ = Mock(return_value=BytesIO(json.dumps(value).encode()))
        response.__exit__ = Mock(return_value=False)
        return response

    def test_simple_record_reaches_java_without_internal_arguments(self):
        arguments = {'type': 'EXPENSE', 'amount': '12', 'unit': 'CENTAVOS', 'description': 'Bala'}
        with patch.object(finance, 'urlopen', return_value=self.response({'amountCents': 12})) as http:
            result = finance.call('record', arguments, session_id='trusted-session')
            self.assertTrue(result['success'])
            self.assertEqual(result['data']['amount'], 'R$ 0,12')
            request = http.call_args.args[0]
            self.assertEqual(json.loads(request.data), arguments)
            self.assertEqual(request.get_header('X-ayla-session'), 'trusted-session')
            self.assertEqual(request.full_url, 'http://java:8080/internal/hermes/finance/record')
        with patch.object(finance, 'urlopen') as http:
            invalid = finance.call('record', {'entry': self.entry, 'itemIndex': 0}, session_id='real')
            self.assertFalse(invalid['success'])
            self.assertIn('itemIndex', invalid['unexpectedFields'])
            self.assertIn('unit', invalid['missingFields'])
            http.assert_not_called()
    def test_rejects_missing_native_context_and_model_identity_override(self):
        with patch.object(finance, 'urlopen') as http:
            self.assertIn('error', finance.call('create', {'entry': self.entry, 'itemIndex': 0}, session_id='real'))
            self.assertIn('error', finance.call('summary', {}, session_id=''))
            for parameter in ('userId', 'session_id', 'url', 'Authorization'):
                self.assertIn('error', finance.call('summary', {parameter: 'fake'}, session_id='real'))
            http.assert_not_called()

    def test_read_uses_java_calculations_without_turn_or_llm(self):
        with patch.object(finance, 'urlopen', return_value=self.response({'balanceCents': 10010})) as http:
            result = finance.call('summary', {'from': '2026-01-01', 'to': '2026-01-31'}, session_id='real')
            self.assertEqual(result['data']['balance'], 'R$ 100,10')
            self.assertNotIn('balanceCents', result['data'])
            self.assertEqual(finance.format_brl(15), 'R$ 0,15')
            self.assertEqual(finance.format_brl(-15), '-R$ 0,15')
            self.assertEqual(finance.format_brl(1500), 'R$ 15,00')
            self.assertEqual(http.call_args.args[0].full_url, 'http://java:8080/internal/hermes/finance/summary')

    def test_http_conflict_does_not_claim_a_write(self):
        error = HTTPError('http://java', 409, 'Conflict', {}, BytesIO(b'{"error":"Versao alterada"}'))
        with patch.object(finance, 'urlopen', side_effect=error):
            result = finance.call('delete', {'id': 'entry', 'version': 0}, session_id='real')
            self.assertFalse(result['success'])
            self.assertEqual(result['status'], 409)

    def test_network_failure_does_not_claim_success(self):
        with patch.object(finance, 'urlopen', side_effect=URLError('offline')):
            self.assertFalse(finance.call('summary', {}, session_id='real')['success'])

    def test_unconfigured_bridge_fails_closed(self):
        with patch.dict(finance.os.environ, {'AYLA_FINANCE_BRIDGE_KEY': ''}), patch.object(finance, 'urlopen') as http:
            self.assertIn('error', finance.call('summary', {}, session_id='real'))
            http.assert_not_called()

    def test_registration_closures_keep_operations_separate(self):
        ctx = Mock()
        with patch.dict('sys.modules', {'agent.context_engine': SimpleNamespace(ContextEngine=object)}):
            finance.register(ctx)
        self.assertEqual(ctx.register_context_engine.call_args.args[0].name, 'ayla-conversation')
        self.assertEqual(ctx.register_tool.call_count, 15)
        ctx.register_hook.assert_not_called()
        self.assertIn("finance_record", [item.kwargs["name"] for item in ctx.register_tool.call_args_list])
        tool = next(item.kwargs for item in ctx.register_tool.call_args_list if item.kwargs['name'] == 'conversation_context_search')
        self.assertEqual(tool['toolset'], 'ayla_context')
        with patch.object(finance, 'urlopen', return_value=self.response({'items': []})) as http:
            result = json.loads(tool['handler']({'query': 'filme'}, session_id='primary-session'))
            self.assertTrue(result['success'])
            self.assertEqual(http.call_args.args[0].full_url, 'http://java:8080/internal/hermes/conversation-context/search')
            self.assertEqual(http.call_args.args[0].get_header('X-ayla-session'), 'primary-session')
        with patch.object(finance, 'call', return_value={'success': True}) as execute:
            for registration in ctx.register_tool.call_args_list:
                tool = registration.kwargs
                if tool['toolset'] != 'ayla_finance':
                    continue
                self.assertEqual(tool['toolset'], 'ayla_finance')
                self.assertNotIn('session_id', tool['schema']['parameters']['properties'])
                response = tool['handler']({}, session_id='native-session')
                self.assertIsInstance(response, str)
                self.assertTrue(json.loads(response)['success'])
                self.assertEqual(execute.call_args.args[0], tool['name'].removeprefix('finance_').removeprefix('investment_'))
                self.assertEqual(execute.call_args.kwargs['session_id'], 'native-session')

    def test_old_executor_notice_and_answers_do_not_override_current_question_or_tools(self):
        import copy
        messages = [
            {'role': 'system', 'content': 'Hoje 23/10. Consulte o banco.'},
            {'role': 'user', 'content': 'gastei 12'},
            {'role': 'user', 'content': finance.EXECUTOR_NOTICE + ' without calling any more tools.'},
            {'role': 'assistant', 'content': 'Hoje 22/10 gastou R$ 65,00, erro antigo.'},
            {'role': 'user', 'content': 'foram centavos com uma bala'},
            {'role': 'assistant', 'tool_calls': [{'id': 'new', 'function': {'name': 'finance_record'}}]},
            {'role': 'tool', 'tool_call_id': 'new', 'content': '{"success":true,"amount":"R$ 0,12"}'},
        ]
        archived = copy.deepcopy(messages)
        selected = finance.select_recent_context(messages)
        self.assertEqual(messages, archived)
        self.assertEqual(selected[-3:], messages[-3:])
        self.assertIn('gastei 12', selected[1]['content'])
        self.assertNotIn('65,00', json.dumps(selected))
        self.assertNotIn(finance.EXECUTOR_NOTICE, json.dumps(selected))

    def test_clarification_and_current_edit_delete_results_survive_context_selection(self):
        for operation in ('finance_update', 'finance_delete', 'finance_summary'):
            with self.subTest(operation=operation):
                current = [
                    {'role': 'user', 'content': 'sim, o da padaria'},
                    {'role': 'assistant', 'tool_calls': [{'id': 'current', 'function': {'name': operation}}]},
                    {'role': 'tool', 'tool_call_id': 'current', 'content': '{"success":true}'},
                ]
                old = [
                    {'role': 'user', 'content': 'Quero corrigir o gasto.'},
                    {'role': 'assistant', 'tool_calls': [{'id': 'old', 'function': {'name': 'finance_list'}}]},
                    {'role': 'tool', 'tool_call_id': 'old', 'content': 'resultado antigo'},
                    {'role': 'assistant', 'content': 'É o gasto da padaria?'},
                ]
                selected = finance.select_recent_context(old + current)
                self.assertEqual(selected[-3:], current)
                self.assertIn('É o gasto da padaria?', str(selected))
                self.assertNotIn('resultado antigo', str(selected))


    def test_images_remain_multimodal_and_old_receipts_are_only_context(self):
        old_image = {'type': 'image_url', 'image_url': {'url': 'data:image/png;base64,old'}}
        new_image = {'type': 'image_url', 'image_url': {'url': 'data:image/png;base64,new'}}
        previous = {'role': 'user', 'content': [old_image]}
        current = {'role': 'user', 'content': [{'type': 'text', 'text': 'Minha nota'}, new_image]}
        selected = finance.select_recent_context([previous, {'role': 'assistant', 'content': 'Registrado.'}, current])
        self.assertEqual(selected[-1], current)
        self.assertEqual(selected[0]['content'][-1], old_image)
        self.assertIn('Não registre novamente', selected[0]['content'][0]['text'])
        self.assertEqual(previous['content'], [old_image])

    def test_household_tools_use_live_java_data_and_never_accept_model_identity(self):
        ctx = Mock()
        with patch.dict('sys.modules', {'agent.context_engine': SimpleNamespace(ContextEngine=object)}):
            finance.register(ctx)
        tools = [call.kwargs for call in ctx.register_tool.call_args_list if call.kwargs['toolset'] == 'ayla_household']
        self.assertEqual({tool['name'] for tool in tools}, {'household_list', 'household_apply'})
        with patch.object(finance, 'urlopen', return_value=self.response({'items': [{'name': 'Arroz', 'quantity': 2}], 'total': 1})) as http:
            tool = next(tool for tool in tools if tool['name'] == 'household_list')
            result = json.loads(tool['handler']({'target': 'PANTRY'}, session_id='native-session'))
            self.assertTrue(result['success'])
            self.assertEqual(result['data']['total'], 1)
            self.assertEqual(http.call_args.args[0].full_url, 'http://java:8080/internal/hermes/household/list')
            self.assertEqual(http.call_args.args[0].get_header('X-ayla-session'), 'native-session')
        with patch.object(finance, 'urlopen') as http:
            self.assertFalse(finance.call('list', {'target': 'PANTRY', 'userId': 'other'}, session_id='real', namespace='household')['success'])
            self.assertFalse(finance.call('apply', {'actions': []}, session_id='', namespace='household')['success'])
            http.assert_not_called()
        with patch.object(finance, 'urlopen', side_effect=URLError('offline')):
            result = finance.call('apply', {'actions': [{'operation': 'GENERATE'}]}, session_id='real', namespace='household')
            self.assertFalse(result['success'])
            self.assertIn('lote não foi aplicado', result['instruction'])
            self.assertNotIn('operação financeira', result['error'])

    def test_memory_transport_is_scoped_to_native_session_and_keeps_data_out_of_finance(self):
        arguments = {'query': 'cachorro Thor'}
        with patch.object(finance, 'urlopen', return_value=self.response([{'content': 'O cachorro se chama Thor.'}])) as http:
            result = finance.call('search', arguments, session_id='trusted-session', namespace='memory')
            self.assertTrue(result['success'])
            request = http.call_args.args[0]
            self.assertEqual(request.full_url, 'http://java:8080/internal/hermes/memory/search')
            self.assertEqual(request.get_header('X-ayla-session'), 'trusted-session')
            self.assertEqual(json.loads(request.data), arguments)
            self.assertIn('silenciosa', result['instruction'])
        with patch.object(finance, 'urlopen') as http:
            self.assertFalse(finance.call('search', {'query': 'Thor', 'userId': 'other'}, session_id='real', namespace='memory')['success'])
            self.assertFalse(finance.call('search', arguments, session_id='', namespace='memory')['success'])
            http.assert_not_called()

    def test_memory_registration_uses_java_and_never_accepts_user_identity_from_model(self):
        ctx = Mock()
        with patch.dict('sys.modules', {'agent.context_engine': SimpleNamespace(ContextEngine=object)}):
            finance.register(ctx)
        tools = [call.kwargs for call in ctx.register_tool.call_args_list if call.kwargs['toolset'] == 'ayla_memory']
        self.assertEqual({tool['name'] for tool in tools}, {'personal_memory_save', 'personal_memory_search', 'personal_memory_delete'})
        with patch.object(finance, 'call', return_value={'success': True}) as execute:
            for tool in tools:
                self.assertNotIn('userId', tool['schema']['parameters']['properties'])
                tool['handler']({}, session_id='native-session')
                self.assertEqual(execute.call_args.kwargs, {'session_id': 'native-session', 'namespace': 'memory'})
                self.assertEqual(execute.call_args.args[0], tool['name'].removeprefix('personal_memory_'))

if __name__ == '__main__':
    unittest.main()


