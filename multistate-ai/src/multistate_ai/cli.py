"""CLI entrypoint — the one place print() is allowed."""
from __future__ import annotations

import sys
from pathlib import Path

from multistate_ai.models import NexusReviewRequest


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(f"usage: {sys.argv[0]} <request.json>")

    path = Path(sys.argv[1])
    request = NexusReviewRequest.model_validate_json(path.read_bytes())
    print(request.model_dump_json(by_alias=True))


if __name__ == "__main__":
    main()
