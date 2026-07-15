"""Shims for langchain-community symbols removed upstream but still imported by ragas."""

from __future__ import annotations

import sys
import types


def install_ragas_import_shims() -> None:
    """Provide ``ChatVertexAI`` so ``import ragas`` succeeds on modern community pkgs."""
    name = "langchain_community.chat_models.vertexai"
    if name in sys.modules:
        return
    mod = types.ModuleType(name)

    class ChatVertexAI:
        """Placeholder; tests never instantiate Vertex AI chat models."""

    mod.ChatVertexAI = ChatVertexAI  # type: ignore[attr-defined]
    sys.modules[name] = mod

    parent_name = "langchain_community.chat_models"
    parent = sys.modules.get(parent_name)
    if parent is not None:
        parent.vertexai = mod  # type: ignore[attr-defined]
