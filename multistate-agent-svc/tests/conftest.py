# multistate-agent-svc/tests/conftest.py
"""Shared pytest fixtures / env for agent-svc tests."""

from __future__ import annotations

import os

# Ryuk fails under some Docker Desktop / Rancher Desktop setups.
os.environ.setdefault("TESTCONTAINERS_RYUK_DISABLED", "true")
