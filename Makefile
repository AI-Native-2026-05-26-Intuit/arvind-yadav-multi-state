# Makefile - the four-command surface every engineer uses daily.
# Targets are designed to be idempotent and friction-free.

.PHONY: up down logs ps smoke clean nuke dev test e2e

up: ## Bring the core stack to healthy.
	docker compose up -d --wait --wait-timeout 90
	docker compose ps

down: ## Stop containers; keep named volumes (DB state preserved).
	docker compose down --remove-orphans

logs: ## Tail logs for every service.
	docker compose logs --follow --tail=200

ps: ## Show service + health state.
	docker compose ps

smoke: ## Run the team smoke script.
	./scripts/smoke.sh

dev: ## Bring up backing services only; app runs on host via `./gradlew bootRun --continuous`. See scripts/dev.md.
	docker compose up -d --wait --wait-timeout 90 postgres mongo redis kafka

test: ## CI integration profile (boots stack + seed-fixtures + smoke).
	docker compose --profile test up -d --wait --wait-timeout 120
	./scripts/smoke.sh

e2e: ## End-to-end profile (adds W4 React frontend + otelcol + jaeger).
	docker compose -f compose.yaml -f compose.profiles.yaml --profile e2e \
		up -d --wait --wait-timeout 150

clean: ## Stop containers; remove anonymous volumes; keep named volumes + images.
	docker compose down --remove-orphans

nuke: ## DANGEROUS: stop containers AND wipe named volumes + locally-built images.
	docker compose down --volumes --remove-orphans --rmi local
