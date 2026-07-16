"""RecursiveCharacterTextSplitter at chunk_size=900 / overlap=150.

The chunker is the seam between raw documents and the embedder. The
recursive separator ladder (paragraph -> sentence -> word -> char)
preserves boundaries where it can; the synthetic chunk_id pattern keeps
citations stable across re-ingestion.
"""

from __future__ import annotations

from langchain_core.documents import Document
from langchain_text_splitters import RecursiveCharacterTextSplitter

DEFAULT_CHUNK_SIZE = 900
DEFAULT_OVERLAP = 150


def make_splitter(
    chunk_size: int = DEFAULT_CHUNK_SIZE,
    overlap: int = DEFAULT_OVERLAP,
) -> RecursiveCharacterTextSplitter:
    # Fail loud on the watch-out from Topic 3: overlap >= chunk_size loops.
    if not 0 <= overlap < chunk_size / 2:
        raise ValueError(
            f"overlap must satisfy 0 <= overlap < chunk_size/2; "
            f"got overlap={overlap}, chunk_size={chunk_size}"
        )
    return RecursiveCharacterTextSplitter(
        chunk_size=chunk_size,
        chunk_overlap=overlap,
        separators=["\n\n", "\n", ". ", " ", ""],
        length_function=len,
    )


def chunk_docs(docs: list[Document], chunk_size: int = DEFAULT_CHUNK_SIZE) -> list[Document]:
    splitter = make_splitter(chunk_size=chunk_size)
    chunks = splitter.split_documents(docs)
    for i, c in enumerate(chunks):
        doc_id = c.metadata.get("doc_id", "doc-synth-unknown")
        c.metadata["chunk_id"] = f"chunk-{doc_id}-p{i}"
        c.metadata["chunk_ordinal"] = i
    return chunks
