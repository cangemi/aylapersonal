"""Read-only provider observers. No prompts, error prose, credentials or billing identities leave Hermes."""
import base64
import ast
import contextvars
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
import hashlib
import hmac
import json
import logging
import math
import os
import queue
import re
import threading
import time
from urllib.request import Request, urlopen
from urllib.parse import urlsplit
import uuid

log = logging.getLogger(__name__)
_attempt = contextvars.ContextVar('ayla_provider_attempt', default=None)
_attempt_clock = contextvars.ContextVar('ayla_provider_attempt_clock', default=None)
_structured = threading.local()
_events = queue.Queue(maxsize=256)
_started = set()
_lock = threading.Lock()
_ID = re.compile(r'[A-Za-z0-9][A-Za-z0-9._:/@+\-]{0,254}\Z')


def identifier(value):
    return value if isinstance(value, str) and _ID.fullmatch(value) else None


def session_identifier(value):
    return value if isinstance(value, str) and re.fullmatch(r'[A-Za-z0-9_-]{1,128}', value) else None


def counter(value):
    return value if isinstance(value, int) and not isinstance(value, bool) and 0 <= value <= 9007199254740991 else None


def wire_bytes(value):
    return len(json.dumps(value, ensure_ascii=False, separators=(',', ':'), default=lambda _: None).encode('utf-8'))


def request_metrics(kwargs):
    metrics = {}
    request = kwargs.get('request') or {}
    messages = kwargs.get('request_messages')
    if not isinstance(messages, list) and isinstance(request, dict):
        messages = request.get('messages')
    if isinstance(messages, list):
        system = [item for item in messages if isinstance(item, dict) and item.get('role') in ('system', 'developer')]
        history = [item for item in messages if isinstance(item, dict) and item.get('role') not in ('system', 'developer')]
        memory = 0
        for item in system:
            text = item.get('content')
            if isinstance(text, str):
                memory += sum(len(part.encode('utf-8')) for part in re.findall(r'\[AYLA_MEMORY_DATA\](.*?)\[/AYLA_MEMORY_DATA\]', text, re.S))
        metrics.update(systemBytes=max(0, wire_bytes(system) - memory), historyBytes=wire_bytes(history), memoryBytes=memory)
    if isinstance(request, dict) and isinstance(request.get('tools'), list):
        metrics['toolBytes'] = wire_bytes(request['tools'])
    for source, target in [('tool_count', 'toolCount'), ('retry_count', 'retryCount'), ('approx_input_tokens', 'approxInputTokens')]:
        if counter(kwargs.get(source)) is not None:
            metrics[target] = kwargs[source]
    return metrics


def finish_metrics(kwargs):
    context = _attempt.get() or {}
    metrics = dict(context.get('metrics') or {})
    usage = kwargs.get('usage') or {}
    if isinstance(usage, dict):
        fields = [('input_tokens', 'inputTokens'), ('prompt_tokens', 'inputTokens'), ('output_tokens', 'outputTokens'),
                  ('completion_tokens', 'outputTokens'), ('total_tokens', 'totalTokens'),
                  ('cache_read_tokens', 'cacheReadTokens'), ('cache_write_tokens', 'cacheWriteTokens')]
        for source, target in fields:
            if counter(usage.get(source)) is not None and target not in metrics:
                metrics[target] = usage[source]
        details = usage.get('prompt_tokens_details') or {}
        if isinstance(details, dict) and counter(details.get('cached_tokens')) is not None:
            metrics.setdefault('cacheReadTokens', details['cached_tokens'])
    if counter(kwargs.get('retry_count')) is not None:
        metrics['retryCount'] = kwargs['retry_count']
    if counter(kwargs.get('status_code')) is not None:
        metrics['httpStatus'] = kwargs['status_code']
    started = _attempt_clock.get()
    if started is not None:
        metrics['durationMs'] = max(0, int((time.monotonic() - started) * 1000))
    return metrics


def utc(value=None):
    return datetime.fromtimestamp(time.time() if value is None else value, timezone.utc).isoformat()


def credentials():
    """Profile-local auth read; fingerprint survives OAuth token refresh without disclosing claims."""
    from hermes_constants import get_hermes_home, profile_name_for_home
    home = get_hermes_home()
    profile = profile_name_for_home(home) or 'default'
    from hermes_cli.auth import get_provider_auth_state
    state = get_provider_auth_state('nous') or {}
    token = state.get('access_token')
    identity = 'profile:' + profile
    if isinstance(token, str) and token:
        try:
            part = token.split('.')[1]
            claims = json.loads(base64.urlsafe_b64decode(part + '=' * (-len(part) % 4)))
            sub, org = claims.get('sub'), claims.get('org_id')
            if isinstance(sub, str) and sub:
                identity = json.dumps(['https://portal.nousresearch.com', sub, org], separators=(',', ':'))
            else:
                identity = 'credential:' + token
        except (IndexError, ValueError, TypeError):
            identity = 'credential:' + token
    key = os.environ.get('AYLA_FINANCE_BRIDGE_KEY', '')
    return {'profile': profile, 'credentialScope': hmac.new(key.encode(), identity.encode(), hashlib.sha256).hexdigest(),
            'credentialObservedAt': utc()}


def publish(payload):
    payload = {**payload, 'provider': 'nous', 'eventId': str(uuid.uuid4()), 'detectedAt': utc()}
    try:
        _events.put_nowait(payload)
    except queue.Full:
        log.warning('Observador de disponibilidade: fila cheia; evento descartado sem afetar a conversa.')


def deliver(payload):
    base = os.environ.get('AYLA_JAVA_BASE_URL', 'http://host.docker.internal:8080').rstrip('/')
    key = os.environ.get('AYLA_FINANCE_BRIDGE_KEY', '')
    parsed = urlsplit(base)
    if len(key) < 32 or parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.username or parsed.query or parsed.fragment:
        return False
    request = Request(base + '/internal/hermes/provider-events', data=json.dumps(payload, allow_nan=False).encode(),
                      headers={'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json'}, method='POST')
    with urlopen(request, timeout=2) as response:
        return response.status == 204


def transport():
    while True:
        payload = _events.get()
        try:
            for attempt in range(3):
                try:
                    if deliver(payload):
                        break
                except Exception:
                    pass  # Never log HTTP bodies, auth headers or credentials.
                if attempt < 2:
                    time.sleep(1)
        finally:
            _events.task_done()


def error_body(value):
    if isinstance(value, dict):
        result = value
    elif isinstance(value, str):
        result = {}
        # SDKs commonly prefix the provider JSON with "Error code: 429 -".
        for match in list(re.finditer(r'\{', value[:16000]))[:12]:
            try:
                candidate, _ = json.JSONDecoder().raw_decode(value[match.start():])
                if isinstance(candidate, dict):
                    result = candidate
                    break
            except ValueError:
                continue
        if not result and len(value) <= 16000:
            # The SDK formats dicts with Python quotes rather than JSON in error.message.
            start, end = value.find('{'), value.rfind('}')
            if start >= 0 and end > start:
                try:
                    candidate = ast.literal_eval(value[start:end + 1])
                    if isinstance(candidate, dict):
                        result = candidate
                except (ValueError, SyntaxError, RecursionError):
                    pass
    else:
        return {}
    nested = result.get('error')
    return {**result, **nested} if isinstance(nested, dict) else result


def retry_seconds(body, headers=None, now=None):
    now = time.time() if now is None else now
    value = body.get('retry_after', body.get('retry_after_seconds'))
    if value is None and headers:
        value = next((v for k, v in headers.items() if k.lower() == 'retry-after'), None)
    if isinstance(value, bool) or value is None:
        return None
    try:
        seconds = float(value)
    except (TypeError, ValueError):
        try:
            seconds = parsedate_to_datetime(str(value)).timestamp() - now
        except (TypeError, ValueError, OverflowError):
            return None
    return math.ceil(seconds) if math.isfinite(seconds) and 0 <= seconds <= 31536000 else None


def observe_classification(**kwargs):
    """Transform hook used strictly as observer: returning None preserves native retry/fallback policies."""
    if kwargs.get('provider') != 'nous':
        return None
    body = error_body(kwargs.get('error_body'))
    headers = None
    response = getattr(kwargs.get('error'), 'response', None)
    if response is not None:
        headers = getattr(response, 'headers', None)
        if not body:
            try:
                body = error_body(response.json())
            except Exception:
                pass
    _structured.details = {'body': body, 'retry': retry_seconds(body, headers), 'model': kwargs.get('model')}
    return None


def before_request(**kwargs):
    if kwargs.get('provider') != 'nous':
        _attempt.set(None)
        return
    try:
        context = {**credentials(), 'model': identifier(kwargs.get('model')), 'sessionId': session_identifier(kwargs.get('session_id')), 'metrics': request_metrics(kwargs)}
        _attempt.set(context)
        _attempt_clock.set(time.monotonic())
        _structured.details = None
        publish({**context, 'state': 'REQUEST'})
    except Exception:
        _attempt.set(None)


def request_error(**kwargs):
    if kwargs.get('provider') != 'nous':
        return
    try:
        context = _attempt.get() or credentials()
        context = {**context, 'model': identifier(kwargs.get('model')), 'sessionId': session_identifier(kwargs.get('session_id'))}
        details = getattr(_structured, 'details', None)
        _structured.details = None
        err = kwargs.get('error') or {}
        body = error_body(err.get('message') if isinstance(err, dict) else None)
        if details and details.get('model') == kwargs.get('model'):
            body = {**body, **details.get('body', {})}
        reason = body.get('reason', body.get('code', kwargs.get('reason')))
        limited = kwargs.get('status_code') == 429 or body.get('status') == 429 or reason in {'rate_limited', 'rate_limit', 'rate_limit_exceeded', 'quota_exceeded'}
        seconds = details.get('retry') if details else retry_seconds(body)
        if seconds is None:
            seconds = retry_seconds(body)
        alternates = body.get('alternates', [])
        publish({**context, 'state': 'RATE_LIMITED' if limited else 'ERROR',
                 'metrics': finish_metrics(kwargs), 'retryAfterSeconds': seconds if limited else None,
                 'retryAttemptAt': None,  # A provider delay is not a scheduled Hermes retry.
                 'alternates': [item for item in alternates if identifier(item)][:12] if isinstance(alternates, list) else []})
    except Exception:
        log.warning('Observador de disponibilidade não conseguiu normalizar um evento.')


def request_success(**kwargs):
    if kwargs.get('provider') != 'nous':
        return
    if kwargs.get('finish_reason') in ('error', 'failed'):
        return request_error(**kwargs)
    try:
        context = _attempt.get() or credentials()
        publish({**context, 'state': 'AVAILABLE', 'model': identifier(kwargs.get('model')),
                 'sessionId': session_identifier(kwargs.get('session_id')), 'metrics': finish_metrics(kwargs)})
    except Exception:
        pass


def tool_observed(**kwargs):
    # Never export tool name, arguments, result or error prose.
    try:
        context = _attempt.get()
        if context is None:
            return
        metrics = {'toolCalls': 1}
        if counter(kwargs.get('duration_ms')) is not None:
            metrics['durationMs'] = kwargs['duration_ms']
        publish({**context, 'sessionId': session_identifier(kwargs.get('session_id')) or context.get('sessionId'),
                 'state': 'TOOL', 'metrics': metrics})
    except Exception:
        pass


def quota_period(label):
    text = label.casefold()
    for patterns, period in [(('daily', 'day', 'diári'), 'DAILY'), (('weekly', 'week', 'semana'), 'WEEKLY'),
                             (('monthly', 'month', 'mensal'), 'MONTHLY'), (('minute',), 'MINUTE'), (('hour',), 'HOUR')]:
        if any(item in text for item in patterns):
            return period
    return 'SUBSCRIPTION' if 'subscription' in text else 'UNKNOWN'


def normalize_quotas(snapshot, account=None):
    quotas = []
    if snapshot is None or getattr(snapshot, 'unavailable_reason', None):
        return quotas
    for window in getattr(snapshot, 'windows', ()):
        value = getattr(window, 'used_percent', None)
        label = getattr(window, 'label', None)
        if isinstance(value, bool) or not isinstance(value, (float, int)) or not math.isfinite(value) or not 0 <= value <= 100 or not isinstance(label, str):
            continue
        reset = getattr(window, 'reset_at', None)
        if label == 'Subscription' and reset is None:
            renewal = getattr(getattr(account, 'subscription', None), 'current_period_end', None)
            if isinstance(renewal, str):
                try:
                    parsed = datetime.fromisoformat(renewal.replace('Z', '+00:00'))
                    if parsed.tzinfo is not None:
                        reset = parsed
                except ValueError:
                    pass
        family = any(word in label.casefold() for word in ('large', 'top-tier', 'opus', 'sonnet'))
        display_label = 'Créditos da assinatura (US$)' if label == 'Subscription' else label[:100]
        quotas.append({'label': display_label, 'period': quota_period(label), 'usagePercentage': value,
                       'used': None, 'limit': None, 'remaining': None,
                       'resetsAt': reset.isoformat() if isinstance(reset, datetime) and reset.tzinfo is not None else None,
                       'scope': 'MODEL_FAMILY' if family else 'MODEL' if getattr(window, 'scope', '') == 'model' else 'ACCOUNT', 'model': None})
    return quotas[:20]


def query_quota():
    context = credentials()
    try:
        from hermes_cli.nous_account import get_nous_portal_account_info
        from agent.account_usage import build_nous_credits_snapshot
    except ImportError:
        return {**context, 'state': 'QUOTA', 'quotas': [], 'quotaStatus': 'UNAVAILABLE'}
    try:
        # Official account API through Hermes' profile-scoped OAuth helper; no browser scraping or model calls.
        account = get_nous_portal_account_info(force_fresh=True)
        if not getattr(account, 'logged_in', False):
            return {**context, 'state': 'QUOTA', 'quotas': [], 'quotaStatus': 'UNAVAILABLE'}
        if getattr(account, 'source', '') == 'error' or not getattr(account, 'fresh', False):
            return {**context, 'state': 'QUOTA', 'quotas': [], 'quotaStatus': 'ERROR'}
        # OAuth can refresh during that call. Re-read its stable identity before publishing the snapshot.
        refreshed = credentials()
        if refreshed['credentialScope'] != context['credentialScope']:
            # Do not label a previous account's quota as the newly connected account.
            return {**refreshed, 'state': 'QUOTA', 'quotas': [], 'quotaStatus': 'ERROR'}
        context = refreshed
        quotas = normalize_quotas(build_nous_credits_snapshot(account), account)
        return {**context, 'state': 'QUOTA', 'quotas': quotas, 'quotaStatus': 'KNOWN' if quotas else 'UNAVAILABLE'}
    except Exception:
        return {**context, 'state': 'QUOTA', 'quotas': [], 'quotaStatus': 'ERROR'}


def poll_quotas():
    while True:
        try:
            publish(query_quota())
        except Exception:
            pass
        time.sleep(60)


def register(ctx):
    for name, callback in [('pre_api_request', before_request), ('api_request_error', request_error),
                           ('post_api_request', request_success), ('transform_api_error_classification', observe_classification),
                           ('post_tool_call', tool_observed)]:
        try:
            ctx.register_hook(name, callback)
        except (ValueError, TypeError, AttributeError):
            log.warning('Hook de disponibilidade não suportado nesta versão: %s', name)
    if os.environ.get('AYLA_PROVIDER_MONITOR_BACKGROUND') != '1' or len(os.environ.get('AYLA_FINANCE_BRIDGE_KEY', '')) < 32:
        return
    try:
        from hermes_constants import get_hermes_home
        home = str(get_hermes_home())
        with _lock:
            if home in _started:
                return
            _started.add(home)
        threading.Thread(target=transport, name='ayla-provider-events', daemon=True).start()
        context = contextvars.copy_context()
        threading.Thread(target=lambda: context.run(poll_quotas), name='ayla-provider-quota', daemon=True).start()
    except Exception:
        log.warning('Monitor de disponibilidade não pôde iniciar neste perfil.')
