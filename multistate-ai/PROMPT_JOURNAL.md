# PROMPT_JOURNAL.md — unedited Claude scaffold transcript (W7 D1)

## Prompt (ai_practice pattern)

```
Scaffold a Python sidecar for multistate-api under multistate-ai/.

Requirements:
- Pydantic v2 models: Tenant (camelCase aliases tenantId, createdAt),
  NexusReviewRequest, NexusReviewResult. extra=forbid, frozen=True.
- Money as Decimal. Validators on tenant_id prefix and high-confidence rationale.
- settings.py with BaseSettings, env_prefix MULTISTATE_AI_, SecretStr for API key.
- client.py: synchronous httpx.Client, tenacity retry (3 attempts, exponential
  jitter), structured logging with correlation_id, x-correlation-id header.
- Use typing.List, typing.Optional where needed.
- proxy_api_key: str in settings.
```

## Claude first-cut output (unedited — representative)

```python
# models.py (excerpt)
from typing import Optional, List
from pydantic import BaseModel, Field

class Tenant(BaseModel):
  id: str
  tenant_id: str = Field(alias="tenantId")
  created_at: datetime = Field(alias="createdAt")
  amount: float  # Claude used float for money

class NexusReviewRequest(BaseModel):
  correlation_id: str = Field(alias="correlationId")
  tenant: Tenant
  tags: Optional[List[str]] = None

# settings.py (excerpt)
class MultistateAiSettings(BaseSettings):
  proxy_base_url: str
  proxy_api_key: str  # NOT SecretStr
  tenant_id: str

# client.py (excerpt)
from typing import Any
@retry(stop=stop_after_attempt(3), ...)
def _call(self, request):
  payload: dict[str, Any] = request.model_dump(mode="json", by_alias=True)
  response = self._client.post("/v1/review", json=payload, headers={
    "authorization": f"Bearer {self._settings.proxy_api_key}",
  })
```

## Engineer review notes (see PYTHON.md for final deltas)

- float → Decimal for money
- str → SecretStr for API key
- List/Optional → list / X | None
- dict[str, Any] → model_dump_json bytes on wire
- HTTPStatusError retry narrowed to 5xx only via _is_retryable()
