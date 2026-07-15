"""Tests for Pandas corpus loader + MiniLM embedding pass."""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd
import pytest
from numpy.typing import NDArray

from multistate_ai.corpus import EMBEDDING_DIM, MODEL_NAME, CorpusRow, embed_dataframe, load_corpus

_FIXTURES = Path(__file__).parent / "fixtures"
_SEED = _FIXTURES / "corpus_seed.jsonl"


class _FakeMiniLM:
    """Stand-in that returns float64 vectors so embed_dataframe must cast to float32."""

    def encode(
        self,
        sentences: list[str],
        batch_size: int = 64,
        normalize_embeddings: bool = True,
        convert_to_numpy: bool = True,
    ) -> NDArray[np.float64]:
        del batch_size, normalize_embeddings, convert_to_numpy
        rng = np.random.default_rng(0)
        return rng.standard_normal((len(sentences), EMBEDDING_DIM), dtype=np.float64)


def test_load_corpus_drops_duplicates(tmp_path: Path) -> None:
    path = tmp_path / "dupes.jsonl"
    rows = [
        {
            "doc_id": "tenant-001",
            "chunk_idx": 0,
            "chunk_text": "Wayfair economic nexus floor for state CA.",
            "tenant_id": "tenant-a",
        },
        {
            "doc_id": "tenant-001",
            "chunk_idx": 0,
            "chunk_text": "Duplicate row that must be dropped.",
            "tenant_id": "tenant-a",
        },
        {
            "doc_id": "tenant-002",
            "chunk_idx": 0,
            "chunk_text": "Payroll nexus attaches after remote work days.",
            "tenant_id": "tenant-b",
        },
    ]
    path.write_text("\n".join(json.dumps(r) for r in rows) + "\n")
    df = load_corpus(path)
    assert len(df) == 2
    assert list(df["doc_id"]) == ["tenant-001", "tenant-002"]
    # keep="first" preserves the original chunk_text.
    assert "Wayfair" in str(df.iloc[0]["chunk_text"])


def test_load_corpus_length_bounds_filter(tmp_path: Path) -> None:
    path = tmp_path / "bounds.jsonl"
    rows = [
        {
            "doc_id": "tenant-010",
            "chunk_idx": 0,
            "chunk_text": "",  # below 1
            "tenant_id": "tenant-a",
        },
        {
            "doc_id": "tenant-011",
            "chunk_idx": 0,
            "chunk_text": "ok",
            "tenant_id": "tenant-b",
        },
        {
            "doc_id": "tenant-012",
            "chunk_idx": 0,
            "chunk_text": "x" * 8001,  # above 8000
            "tenant_id": "tenant-c",
        },
        {
            "doc_id": "tenant-013",
            "chunk_idx": 0,
            "chunk_text": "y" * 8000,  # inclusive upper bound
            "tenant_id": "tenant-a",
        },
    ]
    path.write_text("\n".join(json.dumps(r) for r in rows) + "\n")
    df = load_corpus(path)
    assert len(df) == 2
    assert set(df["doc_id"]) == {"tenant-011", "tenant-013"}


def test_embed_dataframe_float32_and_dim() -> None:
    df = pd.DataFrame(
        [
            {
                "doc_id": "tenant-001",
                "chunk_idx": 0,
                "chunk_text": "Economic nexus after Wayfair for remote sellers.",
                "tenant_id": "tenant-a",
            }
        ]
    )
    # Fake model avoids a HuggingFace download in unit tests; still exercises
    # the float32 boundary cast required by the pgvector insert path.
    rows = embed_dataframe(df, model=_FakeMiniLM(), batch_size=8)
    assert len(rows) == 1
    row = rows[0]
    assert isinstance(row, CorpusRow)
    assert row.embedding.dtype == np.float32
    assert row.embedding.shape == (EMBEDDING_DIM,)
    assert row.model_version == MODEL_NAME


def test_corpus_seed_fixture_has_100_rows() -> None:
    df = load_corpus(_SEED)
    assert len(df) == 100
    assert set(df["tenant_id"]) <= {"tenant-a", "tenant-b", "tenant-c"}
