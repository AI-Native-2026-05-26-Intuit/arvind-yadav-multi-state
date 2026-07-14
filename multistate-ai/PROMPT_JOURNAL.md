# PROMPT_JOURNAL.md — unedited Claude scaffold transcripts (W7 D1)

## Transcript 1 — models.py scaffolding

### Prompt

```
Scaffold multistate-ai/src/multistate_ai/models.py for the multistate-api sidecar.

Requirements:
- Pydantic v2 BaseModels: Tenant, NexusReviewRequest, NexusReviewResult
- extra=forbid, frozen=True on every model
- Tenant mirrors Java record: alias tenantId, createdAt; populate_by_name=True
- Money field as float is fine for now
- Use typing.List and typing.Optional for collection fields
- At least one field_validator and one model_validator(mode="after")
```

### Claude first-cut output (unedited)

```python
from typing import List, Optional
from datetime import datetime
from pydantic import BaseModel, Field, field_validator, model_validator

class Tenant(BaseModel):
    id: str
    tenant_id: str = Field(alias="tenantId")
    created_at: datetime = Field(alias="createdAt")
    amount: float

    @field_validator("tenant_id")
    @classmethod
    def _tenant_must_have_prefix(cls, v: str) -> str:
        if not v.startswith("tenant-"):
            raise ValueError("tenant_id must start with 'tenant-'")
        return v

class NexusReviewRequest(BaseModel):
    correlation_id: str = Field(alias="correlationId")
    tenant: Tenant
    tags: Optional[List[str]] = None

class NexusReviewResult(BaseModel):
    correlation_id: str = Field(alias="correlationId")
    tenant_id: str
    label: str
    confidence: float
    rationale: str

    @model_validator(mode="after")
    def _high_confidence_requires_rationale(self):
        if self.confidence >= 0.9 and len(self.rationale) < 16:
            raise ValueError("high-confidence results require a rationale of >=16 chars")
        return self
```

---

## Transcript 2 — client.py scaffolding

### Prompt

```
Scaffold multistate-ai/src/multistate_ai/client.py — synchronous httpx client
for the W3 D1 LLM proxy.

Requirements:
- MultistateAiSettings with proxy_api_key: str
- httpx.Client with timeout
- tenacity @retry stop_after_attempt(3), wait_exponential_jitter
- Retry on httpx.HTTPStatusError (all status errors)
- Structured logging with correlation_id
- x-correlation-id header propagation
- Use dict[str, Any] for JSON payload from model_dump
```

### Claude first-cut output (unedited)

```python
from typing import Any
import httpx
from tenacity import retry, stop_after_attempt, wait_exponential_jitter

class LlmProxyClient:
    def __init__(self, settings: MultistateAiSettings) -> None:
        self._settings = settings
        self._client = httpx.Client(
            base_url=str(settings.proxy_base_url),
            timeout=httpx.Timeout(settings.proxy_timeout_seconds),
        )

    @retry(stop=stop_after_attempt(3), wait=wait_exponential_jitter(initial=0.5, max=8.0))
    def _call(self, request: NexusReviewRequest) -> NexusReviewResult:
        payload: dict[str, Any] = request.model_dump(mode="json", by_alias=True)
        response = self._client.post(
            "/v1/review",
            json=payload,
            headers={
                "x-correlation-id": request.correlation_id,
                "authorization": f"Bearer {self._settings.proxy_api_key}",
            },
        )
        response.raise_for_status()
        return NexusReviewResult.model_validate_json(response.content)
```

---

## Engineer review notes

See [PYTHON.md](PYTHON.md) for every deviation applied before merge.
