"""Read canonical session execution state without acquiring an owner epoch or running work."""
from pathlib import Path
from contextlib import closing
import sqlite3


def snapshot(home, session):
    path = Path(home) / 'state.db'
    if not path.is_file():
        return {'state': 'MISSING', 'unknown': 0, 'queued': 0, 'running': 0}
    with closing(sqlite3.connect(path.resolve().as_uri() + '?mode=ro', uri=True, timeout=2)) as database:
        database.execute('BEGIN')
        exists = database.execute('SELECT 1 FROM sessions WHERE id=?', (session,)).fetchone()
        counts = dict(database.execute("""
            SELECT status, count(*) FROM session_admissions
            WHERE target_session_id=? AND status IN ('queued','started','unknown') GROUP BY status
        """, (session,)))
        workers = database.execute("SELECT count(*) FROM worker_executions WHERE session_id=? AND status='unknown'", (session,)).fetchone()[0]
    unknown = counts.get('unknown', 0) + workers
    return {'state': 'PAUSED' if unknown else 'READY' if exists else 'MISSING',
            'unknown': unknown, 'queued': counts.get('queued', 0), 'running': counts.get('started', 0)}
