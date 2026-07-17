# Build from the app repo root so the multistate-ai path dependency resolves:
#   docker build -f multistate-mcp-server/Dockerfile .
FROM python:3.12-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && pip install --no-cache-dir uv

COPY multistate-ai /src/multistate-ai
COPY multistate-mcp-server /src/multistate-mcp-server
WORKDIR /src/multistate-mcp-server

RUN uv pip install --system --no-cache .

ENV MULTISTATE_MCP_HOST=0.0.0.0
ENV MULTISTATE_MCP_PORT=8080

EXPOSE 8080

# SSE without Auth returns mcp_error_code 4030 — process is still healthy.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD ["python", "-c", "import urllib.error,urllib.request,sys; \ntry:\n urllib.request.urlopen('http://127.0.0.1:8080/sse', timeout=3)\nexcept urllib.error.HTTPError as e:\n sys.exit(0 if e.code in (401, 403) else 1)\nexcept Exception:\n sys.exit(1)"]

ENTRYPOINT ["multistate-mcp-server-sse"]
