"""Ingest helpers referenced by the Airflow TaskFlow DAG (importable stubs)."""

from __future__ import annotations

from langchain_core.documents import Document


def load_from_source(tenants: list[str]) -> list[str]:
    """Return doc_ids queued for ingest for the given tenants."""
    del tenants
    return []


def fetch_docs(doc_ids: list[str]) -> list[Document]:
    """Fetch raw documents by id for the chunker."""
    return [
        Document(page_content=f"placeholder for {doc_id}", metadata={"doc_id": doc_id})
        for doc_id in doc_ids
    ]
