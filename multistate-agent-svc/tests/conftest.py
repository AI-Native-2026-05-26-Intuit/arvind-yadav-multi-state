# multistate-agent-svc/tests/conftest.py
"""Shared pytest fixtures / env for agent-svc tests."""

from __future__ import annotations

import os
import sys
from pathlib import Path

# Ryuk fails under some Docker Desktop / Rancher Desktop setups.
os.environ.setdefault("TESTCONTAINERS_RYUK_DISABLED", "true")

# Make evals/ importable as a top-level package (same as scripts/eval.py).
_ROOT = Path(__file__).resolve().parents[1]
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))
