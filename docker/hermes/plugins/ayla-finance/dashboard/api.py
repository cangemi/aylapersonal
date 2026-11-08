"""Read-only official account quota, available even while a profile's chat API is stopped."""
import hmac
import importlib.util
import os
from pathlib import Path
import threading
import time
import uuid
from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field

router = APIRouter()
_lock = threading.Lock()
_cache = {}
_path = Path(__file__).resolve().parents[2] / 'ayla-provider-monitor' / '__init__.py'
_spec = importlib.util.spec_from_file_location('ayla_quota_reader', _path)
_monitor = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_monitor)  # No threads, registration or inference calls.
_state_spec = importlib.util.spec_from_file_location('ayla_session_state', Path(__file__).with_name('session_state.py'))
_state_reader = importlib.util.module_from_spec(_state_spec)
_state_spec.loader.exec_module(_state_reader)


class SnapshotRequest(BaseModel):
    profile: str = Field(pattern=r'^[A-Za-z0-9_-]{1,64}$')
    fresh: bool = False


class SessionStateRequest(BaseModel):
    profile: str = Field(pattern=r'^[A-Za-z0-9_-]{1,64}$')
    session: str = Field(pattern=r'^[A-Za-z0-9_-]{1,128}$')


@router.post('/session-state')
def session_state(body: SessionStateRequest, request: Request):
    expected = os.environ.get('AYLA_FINANCE_BRIDGE_KEY', '')
    supplied = request.headers.get('X-Ayla-Bridge-Key', '')
    if len(expected) < 32 or not hmac.compare_digest(expected.encode(), supplied.encode()):
        raise HTTPException(403, 'Integration not authorized')
    if body.profile == 'ayla-internal-template':
        raise HTTPException(403, 'Profile not available')
    from hermes_cli.web_server_profiles import _config_profile_scope, _resolve_profile_dir
    from hermes_constants import get_hermes_home
    _resolve_profile_dir(body.profile)
    with _config_profile_scope(body.profile):
        return _state_reader.snapshot(get_hermes_home(), body.session)


@router.post('/account')
def account(body: SnapshotRequest, request: Request):
    expected = os.environ.get('AYLA_FINANCE_BRIDGE_KEY', '')
    supplied = request.headers.get('X-Ayla-Bridge-Key', '')
    if len(expected) < 32 or not hmac.compare_digest(expected.encode(), supplied.encode()):
        raise HTTPException(403, 'Integration not authorized')
    if body.profile == 'ayla-internal-template':
        raise HTTPException(403, 'Profile not available')
    # Dashboard auth and the bridge key protect this route. The Java caller resolves the profile
    # from the authenticated Ayla user; never let the browser supply another profile.
    from hermes_cli.web_server_profiles import _config_profile_scope, _resolve_profile_dir
    _resolve_profile_dir(body.profile)
    with _config_profile_scope(body.profile):
        context = _monitor.credentials()
        cache_key = (body.profile, context['credentialScope'])
        with _lock:
            cached = _cache.get(cache_key)
            if not body.fresh and cached and time.monotonic() - cached[0] < 25:
                return cached[1]
        quota = _monitor.query_quota()
        if quota.get('profile') != body.profile:
            raise HTTPException(503, 'Profile scope could not be confirmed')
        result = {'event': {**quota, 'eventId': str(uuid.uuid4()), 'provider': 'nous', 'detectedAt': _monitor.utc()}}
        with _lock:
            if len(_cache) > 256:
                _cache.clear()
            _cache[cache_key] = (time.monotonic(), result)
        return result
