"""Keep Nous's required OAuth lock/store local to the active Hermes profile.

The pinned upstream shares this store across profiles by default. An unwritable
override breaks token refresh; a writable global override leaks identities.
Resolve the home on every call so multiplexed requests keep their ContextVar scope.
"""
import ast
from pathlib import Path


PROFILE_AUTH_FUNCTION = '''def _nous_shared_auth_dir() -> Path:
    """AYLA_PROFILE_NOUS_AUTH_V1: never import another profile's OAuth state."""
    from hermes_constants import get_hermes_home
    return get_hermes_home() / ".ayla-nous-auth"
'''


def scope_profile_auth(source: str) -> str:
    tree = ast.parse(source)
    functions = [node for node in tree.body
                 if isinstance(node, ast.FunctionDef) and node.name == "_nous_shared_auth_dir"]
    if len(functions) != 1:
        raise RuntimeError("Review Nous authentication compatibility for this Hermes version")
    function = functions[0]
    if function.args.args or function.args.kwonlyargs or function.args.vararg or function.args.kwarg:
        raise RuntimeError("Review Nous authentication signature for this Hermes version")
    lines = source.splitlines(keepends=True)
    previous = "".join(lines[function.lineno - 1:function.end_lineno])
    if "HERMES_SHARED_AUTH_DIR" not in previous and "AYLA_PROFILE_NOUS_AUTH_V1" not in previous:
        raise RuntimeError("Review Nous authentication implementation for this Hermes version")
    result = "".join(lines[:function.lineno - 1]) + PROFILE_AUTH_FUNCTION + "".join(lines[function.end_lineno:])
    ast.parse(result)
    return result


if __name__ == "__main__":
    target = Path("/opt/hermes/hermes_cli/auth_nous.py")
    original = target.read_text(encoding="utf-8")
    updated = scope_profile_auth(original)
    if updated != original:
        target.write_text(updated, encoding="utf-8")
    print("[ayla] Nous OAuth storage scoped to each Hermes profile.", flush=True)
