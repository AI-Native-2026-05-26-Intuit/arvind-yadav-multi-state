"""Pandas corpus loader + sentence-transformers embedding pass.

The loader is the single seam between raw source documents and the
pgvector corpus. It validates with Pandas (de-dup, length bounds),
embeds with sentence-transformers in batches, and emits
NDArray[np.float32] vectors so the downstream insert can stream them
straight into pgvector via register_vector(conn).
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

import numpy as np
import pandas as pd
from numpy.typing import NDArray
from sentence_transformers import SentenceTransformer

MODEL_NAME = "all-MiniLM-L6-v2"
EMBEDDING_DIM = 384  # MiniLM output dimension; matches vector(384).


class EmbeddingModel(Protocol):
    """Minimal encode surface used by embed_dataframe (SentenceTransformer-compatible)."""

    def encode(
        self,
        sentences: list[str],
        batch_size: int = 64,
        normalize_embeddings: bool = True,
        convert_to_numpy: bool = True,
    ) -> NDArray[np.float64]: ...


@dataclass(frozen=True, slots=True)
class CorpusRow:
    """One chunk + its embedding, ready for pgvector insert."""

    doc_id: str
    chunk_idx: int
    chunk_text: str
    embedding: NDArray[np.float32]
    model_version: str
    tenant_id: str


def load_corpus(path: Path) -> pd.DataFrame:
    """Read a parquet or jsonl corpus into a Pandas DataFrame.

    Expected columns: doc_id, chunk_idx, chunk_text, tenant_id.
    Dedups on (doc_id, chunk_idx) and validates length bounds.
    """
    if path.suffix == ".parquet":
        df = pd.read_parquet(path)
    elif path.suffix in (".jsonl", ".json"):
        df = pd.read_json(path, lines=True)
    else:
        raise ValueError(f"unsupported corpus extension: {path.suffix}")

    required = {"doc_id", "chunk_idx", "chunk_text", "tenant_id"}
    missing = required - set(df.columns)
    if missing:
        raise ValueError(f"corpus missing columns: {sorted(missing)}")

    # m:1 invariant: each (doc_id, chunk_idx) row appears exactly once.
    df = df.drop_duplicates(subset=["doc_id", "chunk_idx"], keep="first")

    df = df.query("1 <= chunk_text.str.len() <= 8000", engine="python")
    return df.reset_index(drop=True)


def embed_dataframe(
    df: pd.DataFrame,
    model: EmbeddingModel | None = None,
    batch_size: int = 64,
) -> list[CorpusRow]:
    """Embed each row's chunk_text in batches; return CorpusRow values."""
    m: EmbeddingModel = model if model is not None else SentenceTransformer(MODEL_NAME)
    texts: list[str] = df["chunk_text"].tolist()
    # Single batched encode replaces a Python per-row loop.
    vectors = m.encode(
        texts,
        batch_size=batch_size,
        normalize_embeddings=True,
        convert_to_numpy=True,
    ).astype(np.float32)
    rows: list[CorpusRow] = []
    for idx, vec in enumerate(vectors):
        rows.append(
            CorpusRow(
                doc_id=str(df.iloc[idx]["doc_id"]),
                chunk_idx=int(df.iloc[idx]["chunk_idx"]),
                chunk_text=str(df.iloc[idx]["chunk_text"]),
                embedding=vec,
                model_version=MODEL_NAME,
                tenant_id=str(df.iloc[idx]["tenant_id"]),
            )
        )
    return rows
