# multistate-api/INFRA.md
# How this service's AWS substrate is provisioned and what
# deviated from the cfn-author Skill's defaults.

## Stack layout (after today)

| Stack | Purpose |
|---|---|
| `multistate-bootstrap-dev` | Bootstrap S3 bucket + `multistate-api-cfn-deploy` OIDC role for GitHub Actions |
| `multistate-artifacts-dev` | Hardened S3 artefact bucket (KMS, PAB, lifecycle, deny-non-TLS, Retain ×2) |
| `multistate-network-dev` | 3-AZ VPC, 6 subnets, IGW, 1–3 NAT GWs (Conditions-gated), app SG |
| `multistate-app-dev` | RDS Postgres + DB SG + Secrets Manager master credentials |

**Network exports** (consumed via `!ImportValue`):

- `multistate-network-dev-VpcId`
- `multistate-network-dev-VpcCidr`
- `multistate-network-dev-PrivateSubnets`
- `multistate-network-dev-PublicSubnets`
- `multistate-network-dev-AppSgId`

**Bootstrap exports:**

- `multistate-bootstrap-dev-BucketName`
- `multistate-bootstrap-dev-BucketArn`
- `multistate-bootstrap-dev-CfnDeployRoleArn`

## Deploy order

1. `multistate-bootstrap-dev` — bootstrap bucket + CFN-deploy IAM role (`CAPABILITY_NAMED_IAM`)
2. `multistate-artifacts-dev` — artefact bucket (independent of network)
3. `multistate-network-dev` — VPC + subnets + SG
4. `multistate-app-dev` — RDS + secret + `SecretTargetAttachment`

## ChangeSet flow (every stack, every change)

```bash
aws cloudformation create-change-set \
  --stack-name              <stack-name> \
  --change-set-name         <name> \
  --change-set-type         CREATE_OR_UPDATE \
  --template-body           file://cfn/<template>.yaml \
  --capabilities            CAPABILITY_NAMED_IAM \
  --parameters              ParameterKey=EnvName,ParameterValue=dev \
  --region                  us-east-1

aws cloudformation describe-change-set \
  --stack-name <stack-name> --change-set-name <name> \
  --region us-east-1
# paste the JSON diff into the PR body

aws cloudformation execute-change-set \
  --stack-name <stack-name> --change-set-name <name> \
  --region us-east-1

aws cloudformation wait stack-create-complete \
  --stack-name <stack-name> --region us-east-1
```

The `describe-change-set` output enumerates every Replace, Modify, Add, and Remove.
PR reviewers read the diff before the execute step. The bootstrap stack's
`CfnDeployRole` (`arn:aws:iam::279566174801:role/multistate-api-cfn-deploy`) is what
GitHub Actions assumes via OIDC to run these commands.

## Cross-stack reference health check

After `multistate-app-dev` deploys, attempt to delete the network stack:

```bash
aws cloudformation delete-stack --stack-name multistate-network-dev --region us-east-1
```

CloudFormation must refuse with **"Export … is in use by stack"**. Roll back the
deletion attempt. This is the safety the `Export.Name` mechanism buys you.

## Drift detection (deliberate verification)

```bash
aws cloudformation detect-stack-drift \
  --stack-name multistate-artifacts-dev \
  --region us-east-1

aws cloudformation describe-stack-drift-detection-status \
  --stack-drift-detection-id <id> \
  --region us-east-1

aws cloudformation describe-stack-resource-drifts \
  --stack-name multistate-artifacts-dev \
  --region us-east-1 \
  --query "StackResourceDrifts[?StackResourceDriftStatus!='IN_SYNC']"
```

For Task 4 we deliberately drifted the bucket policy in the AWS console (added a
stray Sid), confirmed the drift report flagged `ArtefactBucketPolicy` as MODIFIED,
then reverted by re-running the ChangeSet flow on the original template.

## Resource tagging

All taggable resources carry SCP-required tags plus project tags:

| Key | Value |
|---|---|
| `environment` | `dev` / `staging` / `prod` (from `EnvName`) |
| `trainee` | `arvind_yadav` |
| `team` | `multistate` |
| `Env` | same as `environment` |
| `Project` | `multistate` |

## cfn-author Skill audit notes

**Accepted:** The Skill's 3-AZ subnet layout using `!Cidr [!Ref VpcCidr, 6, 8]` with
`!Select` per AZ is portable across cohort CIDRs and matches the appendix. We kept
this pattern and the `IsDev` / `IsHA` Conditions that gate single-NAT (dev) vs
per-AZ NAT (staging/prod).

**Rejected — OIDC `aud` claim:** The Skill scaffolded `StringLike` on
`token.actions.githubusercontent.com:aud` with a wildcard. The cohort checklist
requires `StringEquals: sts.amazonaws.com` on `aud`; only the `sub` claim uses
`StringLike` for branch/PR refs. Fixed in `multistate-bootstrap-dev.yaml`.

**Rejected — DB password as `NoEcho` Parameter:** The Skill shipped a
`MasterUserPassword` Parameter with `NoEcho: true`. Passwords must resolve via
`{{resolve:secretsmanager:multistate/dev/db-master:SecretString:password}}` at
deploy time so the value never appears in stack state or the Parameters API.
Fixed in `multistate-app-dev.yaml` with `DbMasterSecret` + dynamic references.

**Rejected — missing `UpdateReplacePolicy`:** The Skill paired
`DeletionPolicy: Retain` on S3 buckets and RDS but omitted `UpdateReplacePolicy:
Retain`. A property change that triggers replacement would still destroy data with
only `DeletionPolicy`. Both policies are set on every stateful resource in all four
templates.

## Why cfn-lint AND cfn-nag together

- **cfn-lint** catches schema/syntax issues, intrinsic-function misuse, and
  deprecated resource properties before deploy.
- **cfn-nag** enforces security best practices (encryption, public access, IAM
  wildcards) that cfn-lint does not cover.
- Together they form a static gate in `.github/workflows/cfn-validate.yml` that
  blocks merge on any error or warning (`--fail-on-warnings`).
