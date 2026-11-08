import importlib.util
from contextlib import closing
from pathlib import Path
import sqlite3
from tempfile import TemporaryDirectory
import unittest

path = Path(__file__).parents[1] / 'plugins/ayla-finance/dashboard/session_state.py'
spec = importlib.util.spec_from_file_location('session_state', path)
state = importlib.util.module_from_spec(spec)
spec.loader.exec_module(state)


class SessionStateTest(unittest.TestCase):
    def setUp(self):
        self.directory = TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.home = Path(self.directory.name)
        with closing(sqlite3.connect(self.home / 'state.db', isolation_level=None)) as database:
            database.executescript('''
                CREATE TABLE sessions(id TEXT);
                CREATE TABLE session_admissions(target_session_id TEXT,status TEXT);
                CREATE TABLE worker_executions(session_id TEXT,status TEXT);
                INSERT INTO sessions VALUES ('session-a');
            ''')

    def test_unknown_execution_is_paused_even_with_zero_active_runs(self):
        with closing(sqlite3.connect(self.home / 'state.db', isolation_level=None)) as database:
            database.executescript("INSERT INTO session_admissions VALUES ('session-a','unknown'),('session-a','queued');")
        result = state.snapshot(self.home, 'session-a')
        self.assertEqual(result, {'state': 'PAUSED', 'unknown': 1, 'queued': 1, 'running': 0})

    def test_unknown_worker_is_also_a_barrier(self):
        with closing(sqlite3.connect(self.home / 'state.db', isolation_level=None)) as database:
            database.execute("INSERT INTO worker_executions VALUES ('session-a','unknown')")
        self.assertEqual(state.snapshot(self.home, 'session-a')['state'], 'PAUSED')

    def test_completed_admission_does_not_pause_the_session(self):
        with closing(sqlite3.connect(self.home / 'state.db', isolation_level=None)) as database:
            database.execute("INSERT INTO session_admissions VALUES ('session-a','terminal')")
        self.assertEqual(state.snapshot(self.home, 'session-a')['state'], 'READY')
        self.assertEqual(state.snapshot(self.home, 'other-session')['state'], 'MISSING')

    def test_other_profile_never_reads_owner_state_or_creates_a_database(self):
        other = self.home / 'other-profile'
        other.mkdir()
        self.assertEqual(state.snapshot(other, 'session-a')['state'], 'MISSING')
        self.assertFalse((other / 'state.db').exists())


if __name__ == '__main__':
    unittest.main()
