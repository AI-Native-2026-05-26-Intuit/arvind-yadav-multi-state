## Summary

W5 Day 4 — serverless tenant lookup Lambda (`com.uptimecrew.multi_state.lambda.TenantLookupHandler`) behind API Gateway HTTP API v2, DynamoDB read model, SnapStart on `arm64`, least-privilege IAM, JSON structured logs, EMF custom metrics (`MultistateDev`), and GitHub Actions serverless CI with OIDC deploy (no long-lived AWS keys).

**Gradle note:** this repo uses `./gradlew tenantLookupJar test` instead of `mvn test` (same behaviour, documented in README).

**OIDC role ARN:** `<paste vars.AWS_DEPLOY_ROLE_ARN — not committed>`

---

## Test output

### `sam validate --lint && ./gradlew tenantLookupJar test && sam build --use-container && sam local invoke`

```text
$ sam validate --lint
/Users/ayadav23/Desktop/uptimecrew-multistate/template.yaml is a valid SAM Template

$ ./gradlew tenantLookupJar test --tests 'com.uptimecrew.multi_state.lambda.*'
BUILD SUCCESSFUL

$ sam build
Build Succeeded

$ sam local invoke TenantLookupFunction --event events/get-tenant.json --env-vars env.json
Invoking com.uptimecrew.multi_state.lambda.TenantLookupHandler::handleRequest (java21)
Using local image: public.ecr.aws/lambda/java:21-rapid-arm64.
START RequestId: 0d76eeb4-4d3c-4b82-9fc5-945802efbd32 Version: $LATEST
[main] INFO com.uptimecrew.multi_state.lambda.TenantLookupHandler - lookup attempt {"correlationId":"local-smoke-corr-1","tenantId":"tnt_synth_001","remainingMs":9679}
[main] INFO com.uptimecrew.multi_state.lambda.EmfPublisher - {"_aws":{...},"Service":"tenant-lookup","TenantLookupSuccess":1}
[main] INFO com.uptimecrew.multi_state.lambda.TenantLookupHandler - lookup success correlationId=local-smoke-corr-1 tenantId=tnt_synth_001 remainingMs=8909
END RequestId: 0d76eeb4-4d3c-4b82-9fc5-945802efbd32
REPORT RequestId: 0d76eeb4-4d3c-4b82-9fc5-945802efbd32  Init Duration: 0.04 ms  Duration: 1094.23 ms  Billed Duration: 1095 ms  Memory Size: 1024 MB  Max Memory Used: 1024 MB
{"statusCode": 200, "headers": {"Content-Type": "application/json", "x-correlation-id": "local-smoke-corr-1"}, "body": "{\"id\":\"tnt_synth_001\",\"legalName\":\"tenant-synth.example.internal\",\"primaryState\":\"CA\",\"status\":\"ACTIVE\",\"capturedAt\":1783036800.000000000,\"totalAllocated\":12500.00}", "isBase64Encoded": false}
```

**Local invoke fix:** SAM only injects env vars declared in `template.yaml`; `--env-vars` overrides existing keys but cannot add new ones. Added `DYNAMODB_ENDPOINT_URL` parameter (default `""`) to the template, lazy `DynamoDbClient` init with `endpointOverride` when set, and `TenantRecord` numeric (`N`) support for `totalAllocated`. Mac/Rancher: use `http://host.docker.internal:8000` in `env.json` (see `env.json.example`).

### Deployed smoke — `curl <HttpApiUrl>/tenants/tnt_synth_001`

```text
PASTE: HttpApiUrl from stack outputs

PASTE: curl -s -D - <HttpApiUrl>/tenants/tnt_synth_001
      (expect HTTP 200 + JSON body with tnt_synth_001)
```

### Correlation-id probe

```bash
curl -s -D - -o /dev/null -H 'x-correlation-id: probe-123' <HttpApiUrl>/tenants/tnt_synth_001 | grep -i x-correlation-id
```

```text
PASTE: x-correlation-id: probe-123
```

---

## Cold vs warm latency (SnapStart)

From `./scripts/sam-cold-warm.sh` + `aws logs tail /aws/lambda/multistate-tenant-lookup-dev --since 10m --filter-pattern REPORT`:

| Metric | Value (ms) | Pass target |
|--------|------------|-------------|
| Init Duration (cold, call 1) | PASTE | — |
| Duration p99 (cold, call 1) | PASTE | < 600 ms |
| Duration p50 (warm, calls 2–5) | PASTE | < 60 ms |

```text
PASTE: raw REPORT lines from CloudWatch

Pre-SnapStart baseline (Task 1, if captured): PASTE or N/A
SnapStart improvement: PASTE
```

---

## IAM least-privilege proof

```bash
ROLE=$(aws lambda get-function-configuration \
  --function-name multistate-tenant-lookup-dev \
  --query 'Role' --output text | awk -F/ '{print $NF}')
aws iam list-role-policies --role-name "$ROLE"
aws iam get-role-policy --role-name "$ROLE" --policy-name <inline-policy-name>
```

```json
PASTE: get-role-policy PolicyDocument
      (expect dynamodb:GetItem, BatchGetItem, Query, Scan, DescribeTable, etc.
       on specific table ARN — NOT "dynamodb:*" or Resource "*")
```

### Custom metrics

```bash
aws cloudwatch list-metrics --namespace MultistateDev --region us-east-1
```

```text
PASTE: TenantLookupSuccess / TenantNotFound listed
```

---

## CI runs

| Run | URL |
|-----|-----|
| Green serverless Action (final) | PASTE |
| Deliberately-broken template (sam validate --lint failed + sam-diagnostics artefact) | PASTE |

---

## Teardown

```bash
sam delete --stack-name multistate-lambda-dev --region us-east-1
sam delete --stack-name multistate-lambda-sandbox --region us-east-1
aws cloudformation describe-stacks --stack-name multistate-lambda-dev --region us-east-1
aws cloudformation describe-stacks --stack-name multistate-lambda-sandbox --region us-east-1
```

```text
PASTE: sam delete output for multistate-lambda-dev

PASTE: sam delete output for multistate-lambda-sandbox

PASTE: describe-stacks → "Stack with id multistate-lambda-dev does not exist"
PASTE: describe-stacks → "Stack with id multistate-lambda-sandbox does not exist"
```

---

## AI-tool reflection

The AI suggested using a hand-written IAM policy with `Effect: Allow`, `Action: "dynamodb:*"`, `Resource: "*"` for simplicity. I **rejected** that and kept SAM's `DynamoDBReadPolicy: { TableName: !Ref TenantsTable }` connector so the execution role only gets specific read verbs on the one table ARN.

I **accepted** the suggestion to use a root `Makefile` SAM build hook (`build-TenantLookupFunction` unpacks the Gradle fat JAR) instead of pointing `CodeUri` directly at the `.jar` file, because SAM's Java builder treated the jar path as a Gradle project and failed. I also **accepted** adding DynamoDB Local to the PR workflow so `sam local invoke` can return 200 without AWS credentials on the validate job.

I **accepted** adding `DYNAMODB_ENDPOINT_URL` to `template.yaml` (not bare `AWS_ENDPOINT_URL_DYNAMODB` in `--env-vars` alone) after discovering SAM local only passes template-declared env keys into the Lambda container — undeclared keys were silently dropped and the handler hit real AWS DynamoDB.

---

## Deliverables checklist

- [ ] `TenantLookupHandler` with static INIT fields + `BigDecimal` money
- [ ] `template.yaml` validates; SnapStart + `live` alias + `arm64` + p99 alarm
- [x] `./gradlew tenantLookupJar test` green
- [x] `sam build --use-container` + `sam local invoke` green (200 + `tnt_synth_001` via DynamoDB Local + `env.json`)
- [ ] Deployed `curl …/tenants/tnt_synth_001` → 200 + JSON
- [ ] Cold/warm latency table in PR (cold p99 < 600 ms; warm p50 < 60 ms)
- [ ] `aws iam get-role-policy` — specific verbs, no `*` wildcards
- [ ] `LogFormat: JSON` + explicit LogGroup retention + correlation-id + EMF `MultistateDev`
- [ ] `scripts/sam-deploy.sh` + `scripts/sam-smoke.sh` executable
- [ ] `.github/workflows/serverless.yml` green on PR; OIDC sandbox deploy on merge
- [ ] Deliberately-broken run uploaded `sam-diagnostics`
- [ ] `sam delete` both stacks; `describe-stacks` confirms gone
- [ ] README documents deploy + smoke scripts
