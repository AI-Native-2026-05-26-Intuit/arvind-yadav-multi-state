"""Shared DDL apply helper for Testcontainers Postgres migrations."""

from __future__ import annotations

from pathlib import Path

import psycopg


def apply_sql_file(dsn: str, path: Path) -> None:
    """Apply a migration statement-by-statement in autocommit (for CONCURRENTLY)."""
    cleaned_lines: list[str] = []
    for line in path.read_text().splitlines():
        if line.lstrip().startswith("--"):
            continue
        cleaned_lines.append(line)
    statements = [s.strip() for s in "\n".join(cleaned_lines).split(";") if s.strip()]
    with psycopg.connect(dsn, autocommit=True) as conn:
        for stmt in statements:
            conn.execute(stmt)
