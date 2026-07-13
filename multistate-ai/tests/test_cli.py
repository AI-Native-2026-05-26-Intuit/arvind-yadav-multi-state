from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

from multistate_ai.cli import main

_FIXTURES = Path(__file__).parent / "fixtures"


def test_cli_validates_and_prints_json(
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    request_path = _FIXTURES / "sample_request.json"
    monkeypatch.setattr(sys, "argv", ["multistate-ai", str(request_path)])
    main()
    output = json.loads(capsys.readouterr().out)
    assert output["correlationId"] == "corr-task1-smoke"
    assert output["tenant"]["tenantId"] == "tenant-a"


def test_cli_usage_exits_when_path_missing(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(sys, "argv", ["multistate-ai"])
    with pytest.raises(SystemExit, match="usage"):
        main()
