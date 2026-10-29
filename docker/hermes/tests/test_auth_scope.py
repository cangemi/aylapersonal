import contextvars
import importlib.util
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import patch

source = Path(__file__).resolve().parents[1] / "prepare_auth.py"
spec = importlib.util.spec_from_file_location("prepare_auth", source)
prepare_auth = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare_auth)

UPSTREAM = '''from pathlib import Path
def _nous_shared_auth_dir() -> Path:
    """HERMES_SHARED_AUTH_DIR or the global store."""
    return Path("/global/shared")
'''


class AuthScopeTest(unittest.TestCase):
    def test_concurrent_profiles_have_independent_writable_oauth_stores(self):
        active_home = contextvars.ContextVar("active_home")
        constants = SimpleNamespace(get_hermes_home=active_home.get)
        namespace = {}
        exec(prepare_auth.scope_profile_auth(UPSTREAM), namespace)
        resolve = namespace["_nous_shared_auth_dir"]
        with TemporaryDirectory() as directory, patch.dict(sys.modules, hermes_constants=constants):
            def write_and_read(profile):
                active_home.set(Path(directory) / profile)
                store = resolve()
                store.mkdir(parents=True)
                (store / "nous_auth.lock").touch()
                (store / "nous_auth.json").write_text(profile)
                return store, (resolve() / "nous_auth.json").read_text()

            with ThreadPoolExecutor(max_workers=2) as workers:
                results = list(workers.map(write_and_read, ["owner", "other-user"]))
            self.assertNotEqual(results[0][0], results[1][0])
            self.assertEqual(["owner", "other-user"], [result[1] for result in results])
            self.assertFalse((Path(directory) / "shared").exists())

    def test_patch_is_idempotent_and_rejects_unknown_upstream(self):
        patched = prepare_auth.scope_profile_auth(UPSTREAM)
        self.assertEqual(patched, prepare_auth.scope_profile_auth(patched))
        with self.assertRaises(RuntimeError):
            prepare_auth.scope_profile_auth("def new_auth(): pass\n")


if __name__ == "__main__":
    unittest.main()
