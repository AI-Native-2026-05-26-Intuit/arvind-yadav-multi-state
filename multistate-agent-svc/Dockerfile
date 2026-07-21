# multistate-agent-svc/Dockerfile
FROM python:3.12-slim

WORKDIR /app

# System deps for psycopg / healthcheck
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=ghcr.io/astral-sh/uv:0.6.14 /uv /usr/local/bin/uv

# Path deps: copy sibling packages expected by [tool.uv.sources]
COPY multistate-ai /deps/multistate-ai
COPY multistate-mcp-server /deps/multistate-mcp-server
COPY multistate-agent-svc /app

# Rewrite path sources for the image layout
RUN sed -i 's|path = "../multistate-ai"|path = "/deps/multistate-ai"|' pyproject.toml \
 && sed -i 's|path = "../multistate-mcp-server"|path = "/deps/multistate-mcp-server"|' pyproject.toml

RUN UV_HTTP_TIMEOUT=300 uv pip install --system \
      --allow-insecure-host pypi.org \
      --allow-insecure-host files.pythonhosted.org \
      .

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/healthz || exit 1

CMD ["uvicorn", "multistate_agent_svc.app:app", "--host", "0.0.0.0", "--port", "8080"]
