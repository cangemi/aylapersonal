import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

source = Path(__file__).resolve().parents[1] / 'plugins' / 'ayla-research' / '__init__.py'
spec = importlib.util.spec_from_file_location('ayla_research', source)
research = importlib.util.module_from_spec(spec)
spec.loader.exec_module(research)


def plan(action='search', query='assunto desconhecido fonte oficial', public=True, known=False):
    return {'action': action, 'query': query, 'public_information': public, 'known_reliable': known, 'reason': 'avaliação contextual'}


def verdict(sufficient=True, query=''):
    return {'sufficient': sufficient, 'next_query': query, 'reason': 'avaliação das fontes'}


def hits(url='https://example.org/source'):
    return {'success': True, 'data': {'web': [{'title': 'Fonte', 'url': url, 'description': 'Informação verificada'}]}}


class ResearchTest(unittest.TestCase):
    def setUp(self):
        self.llm = Mock()
        self.free = patch.object(research, 'verified_free_model', return_value=True)
        self.free.start()
        self.addCleanup(self.free.stop)

    def decisions(self, *values):
        self.llm.complete_structured.side_effect = [SimpleNamespace(parsed=v) for v in values]

    def run_flow(self, text='Me fale sobre essa tecnologia nova', **kwargs):
        return research.research(text, llm=self.llm, model='verified-free-model', **kwargs)

    def evidence(self, response):
        text = response['context']
        return json.loads(text[text.index('{"status"'):])

    def test_unknown_subject_without_keyword_is_researched(self):
        self.decisions(plan(query='tecnologia emergente documentação'), verdict())
        with patch.object(research, 'native_search', return_value=hits()) as backend:
            result = self.run_flow('Me conta sobre o protocolo Xytrion')
        backend.assert_called_once_with('tecnologia emergente documentação')
        self.assertTrue(self.evidence(result)['sufficient'])

    def test_casual_chat_with_today_is_not_searched(self):
        self.decisions(plan('answer', public=False, known=True))
        with patch.object(research, 'native_search') as backend:
            self.run_flow('Hoje estou cansado e queria conversar')
        backend.assert_not_called()

    def test_stable_knowledge_math_and_creative_requests_can_skip_search(self):
        for text in ('Quanto é 15 vezes 3?', 'Escreva uma mensagem de aniversário', 'Explique uma variável em Java'):
            self.decisions(plan('answer', public=False, known=True))
            with patch.object(research, 'native_search') as backend:
                self.run_flow(text)
            backend.assert_not_called()

    def test_uncertain_public_answer_is_searched_even_if_planner_says_answer(self):
        self.decisions(plan('answer', public=True, known=False), verdict())
        with patch.object(research, 'native_search', return_value=hits()) as backend:
            self.run_flow()
        backend.assert_called_once()

    def test_personal_information_is_not_sent_to_search(self):
        self.decisions(plan('clarify', query='private person detail', public=False))
        with patch.object(research, 'native_search') as backend:
            result = self.run_flow('Quem é meu melhor amigo?')
        backend.assert_not_called()
        self.assertIn('pessoal', result['context'])

    def test_private_search_decision_is_rejected(self):
        self.decisions(plan(public=False))
        with patch.object(research, 'native_search') as backend:
            self.run_flow()
        backend.assert_not_called()

    def test_uncertain_private_answer_requests_clarification(self):
        self.decisions(plan('answer', public=False, known=False))
        with patch.object(research, 'native_search') as backend:
            result = self.run_flow('Qual era meu restaurante preferido quando eu era criança?')
        backend.assert_not_called()
        self.assertIn('pessoal', result['context'])

    def test_followup_uses_subject_from_recent_context(self):
        self.decisions(plan(query='campeonato regional final resultado'), verdict())
        history = [{'role': 'user', 'content': 'Quero saber desse campeonato regional'}, {'role': 'assistant', 'content': 'Vamos verificar'}]
        with patch.object(research, 'native_search', return_value=hits()) as backend:
            self.run_flow('E quem ficou em segundo?', conversation_history=history)
        backend.assert_called_once_with('campeonato regional final resultado')
        payload = json.loads(self.llm.complete_structured.call_args_list[0].kwargs['input'][0]['text'])
        self.assertEqual(payload['recent_conversation'], history)

    def test_context_is_bounded_and_does_not_include_system_or_tool_secrets(self):
        history = [{'role': 'system', 'content': 'secret'}, {'role': 'tool', 'content': 'token'}]
        history += [{'role': 'user', 'content': str(i) * 1200} for i in range(10)]
        context = research.recent_context(history, 'current')
        self.assertEqual(len(context), 6)
        self.assertTrue(all(len(item['content']) <= 900 for item in context))
        self.assertNotIn('secret', json.dumps(context))

    def test_required_flag_forces_public_search_despite_known_answer(self):
        self.decisions(plan('answer', known=True), verdict())
        with patch.object(research, 'native_search', return_value=hits()) as backend:
            self.run_flow(research.REQUIRED_PREFIX + 'documentação oficial')
        backend.assert_called_once()
        payload = json.loads(self.llm.complete_structured.call_args_list[0].kwargs['input'][0]['text'])
        self.assertTrue(payload['search_required'])

    def test_explicit_opt_out_is_respected_even_with_required_flag(self):
        self.decisions(plan('respect_opt_out'))
        with patch.object(research, 'native_search') as backend:
            result = self.run_flow(research.REQUIRED_PREFIX + 'Não use a internet')
        backend.assert_not_called()
        self.assertIn('restringiu', result['context'])

    def test_inconclusive_evidence_refines_query_once(self):
        self.decisions(plan(query='initial query'), verdict(False, 'refined query'), verdict(True))
        with patch.object(research, 'native_search', side_effect=[hits(), hits('https://example.org/other')]) as backend:
            result = self.run_flow()
        self.assertEqual([call.args[0] for call in backend.call_args_list], ['initial query', 'refined query'])
        data = self.evidence(result)
        self.assertTrue(data['sufficient'])
        self.assertEqual(data['attempts'], 2)
        self.assertEqual(len(data['sources']), 2)

    def test_two_inconclusive_searches_do_not_claim_supported_answer(self):
        self.decisions(plan(query='one'), verdict(False, 'two'), verdict(False, 'three'))
        with patch.object(research, 'native_search', return_value=hits()) as backend:
            result = self.run_flow()
        self.assertEqual(backend.call_count, 2)
        self.assertFalse(self.evidence(result)['sufficient'])
        self.assertEqual(self.evidence(result)['status'], 'inconclusive')

    def test_empty_first_search_can_be_refined(self):
        self.decisions(plan(query='one'), verdict(False, 'two'), verdict(True))
        with patch.object(research, 'native_search', side_effect=[{'success': True, 'data': {'web': []}}, hits()]):
            result = self.run_flow()
        self.assertEqual(self.evidence(result)['attempts'], 2)
        self.assertTrue(self.evidence(result)['sufficient'])

    def test_repeated_refinement_is_not_retried_forever(self):
        self.decisions(plan(query='same'), verdict(False, 'SAME'))
        with patch.object(research, 'native_search', return_value=hits()) as backend:
            result = self.run_flow()
        self.assertEqual(backend.call_count, 1)
        self.assertFalse(self.evidence(result)['sufficient'])

    def test_backend_failure_is_distinct_from_no_information(self):
        self.decisions(plan())
        with patch.object(research, 'native_search', return_value={'success': False, 'error': 'secret-token'}):
            result = self.run_flow()
        self.assertEqual(self.evidence(result)['status'], 'failed')
        self.assertNotIn('secret-token', result['context'])

    def test_judge_failure_keeps_sources_without_inventing_confidence(self):
        self.decisions(plan(), None)
        with patch.object(research, 'native_search', return_value=hits()):
            result = self.run_flow()
        data = self.evidence(result)
        self.assertEqual(data['status'], 'partial')
        self.assertFalse(data['sufficient'])
        self.assertEqual(len(data['sources']), 1)

    def test_invalid_planner_json_leaves_native_search_available_without_claiming_search(self):
        self.decisions(None)
        with patch.object(research, 'native_search') as backend:
            result = self.run_flow()
        backend.assert_not_called()
        self.assertIn('ainda pode usar web_search', result['context'])
        self.assertNotIn('[Pesquisa real deste turno]', result['context'])

    def test_invalid_boolean_from_planner_is_not_trusted(self):
        invalid = plan()
        invalid['public_information'] = 'false'
        self.decisions(invalid)
        with patch.object(research, 'native_search') as backend:
            self.run_flow()
        backend.assert_not_called()

    def test_unverified_model_cannot_be_used_for_extra_model_calls(self):
        with patch.object(research, 'verified_free_model', return_value=False):
            self.run_flow()
        self.llm.complete_structured.assert_not_called()

    def test_uses_current_free_model_pinned_to_nous(self):
        self.decisions(plan('answer', known=True))
        self.run_flow()
        args = self.llm.complete_structured.call_args.kwargs
        self.assertEqual(args['model'], 'verified-free-model')
        self.assertEqual(args['provider'], 'nous')
        self.assertNotIn('task', args)

    def test_free_inventory_requires_verified_prices_and_entitlement(self):
        row = {'slug': 'nous', 'authenticated': True, 'models': ['m'], 'pricing': {'m': {'free': True, 'input': 'free', 'output': 'free'}}}
        payload = {'providers': [row]}
        self.assertTrue(research.free_model_in_inventory(payload, 'm'))
        row['unavailable_models'] = ['m']
        self.assertFalse(research.free_model_in_inventory(payload, 'm'))
        row.pop('unavailable_models')
        row['pricing']['m']['free'] = False
        self.assertFalse(research.free_model_in_inventory(payload, 'm'))
        row['pricing'] = {}
        self.assertFalse(research.free_model_in_inventory(payload, 'm'))

    def test_invalid_source_links_are_excluded(self):
        payload = {'success': True, 'data': {'web': [{'url': 'javascript:alert(1)'}, {'url': 'https://u:p@example.org'}, {'url': 'https://example.org/ok'}]}}
        self.assertEqual([hit['url'] for hit in research.clean_results(payload)], ['https://example.org/ok'])

    def test_empty_or_nontext_message_does_not_call_model(self):
        self.assertIsNone(self.run_flow(''))
        self.assertIsNone(self.run_flow([{'type': 'image_url', 'image_url': {'url': 'secret'}}]))
        self.llm.complete_structured.assert_not_called()

    def test_normal_turn_never_prefetches_old_research_but_explicit_flag_still_works(self):
        ctx = Mock()
        research.register(ctx)
        hook = ctx.register_hook.call_args.args[1]
        history = [{'role': 'user', 'content': 'Quem ganhou a Copa de 2026?'},
                   {'role': 'assistant', 'content': '[Pesquisa real deste turno] pesquisa inconclusiva'}]
        with patch.object(research, 'research') as prefetch, patch.object(research, 'native_search') as search:
            for message in ('gastei 15 reais com coca-cola', 'Oi, tudo bem?', 'Quem venceu o torneio?'):
                result = hook(user_message=message, conversation_history=history)
                self.assertIn('mensagem atual', result['context'])
            prefetch.assert_not_called()
            search.assert_not_called()
            ctx.llm.complete_structured.assert_not_called()
            hook(user_message=research.REQUIRED_PREFIX + 'Pesquise o resultado atual')
            prefetch.assert_called_once()

    def test_multimodal_extracts_only_text(self):
        self.assertEqual(research.plain_text([{'type': 'text', 'text': 'Olá'}, {'type': 'image_url', 'url': 'secret'}]), 'Olá')


if __name__ == '__main__':
    unittest.main()
