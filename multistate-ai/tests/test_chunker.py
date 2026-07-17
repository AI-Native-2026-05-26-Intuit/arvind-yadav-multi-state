"""Unit tests for RecursiveCharacterTextSplitter chunk discipline."""

from __future__ import annotations

import pytest
from langchain_core.documents import Document

from multistate_ai.chunker import chunk_docs, make_splitter


def test_make_splitter_rejects_overlap_ge_half_chunk_size() -> None:
    with pytest.raises(ValueError, match="overlap must satisfy"):
        make_splitter(chunk_size=100, overlap=200)


def test_chunk_ids_stable_and_ordinals_monotonic() -> None:
    # ~5 KB of paragraph-separated prose so the splitter emits multiple chunks.
    paragraph = (
        "Nexus economic thresholds apply when payroll or sales exceed the "
        "statutory floor for a jurisdiction. "
    )
    # Target ~5 KB: paragraph is ~100 chars + separators.
    body = "\n\n".join(paragraph for _ in range(48))
    assert 4500 <= len(body) <= 5500
    doc = Document(page_content=body, metadata={"doc_id": "doc-nexus-5k"})

    first = chunk_docs([doc])
    second = chunk_docs([doc])

    assert len(first) >= 2
    assert [c.metadata["chunk_id"] for c in first] == [c.metadata["chunk_id"] for c in second]
    ordinals = [int(c.metadata["chunk_ordinal"]) for c in first]
    assert ordinals == list(range(len(first)))
    assert all(
        cid.startswith("chunk-doc-nexus-5k-p") for cid in (c.metadata["chunk_id"] for c in first)
    )


def test_average_chunk_length_in_expected_band() -> None:
    # Typical 4-8 KB input; average chunk length should land in [400, 950].
    sentence = (
        "Remote-worker payroll nexus depends on physical presence days and "
        "withholding registration in the destination state. "
    )
    body = "\n\n".join(sentence for _ in range(55))
    assert 4000 <= len(body) <= 8000
    chunks = chunk_docs([Document(page_content=body, metadata={"doc_id": "doc-avg"})])
    assert chunks
    avg = sum(len(c.page_content) for c in chunks) / len(chunks)
    assert 400 <= avg <= 950, f"average chunk length {avg:.1f} outside [400, 950]"
