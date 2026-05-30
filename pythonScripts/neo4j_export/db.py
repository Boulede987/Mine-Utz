from .config import BATCH_SIZE


def run_batch(session, query: str, rows: list[dict]) -> None:
    for offset in range(0, len(rows), BATCH_SIZE):
        session.run(query, rows=rows[offset:offset + BATCH_SIZE])
