# Security Policy

## Reporting a vulnerability

Please do not report security vulnerabilities in public GitHub issues, discussions, or pull requests.

If GitHub's **Private vulnerability reporting** option is enabled for this repository, use **Security → Report a vulnerability** to submit a private report. Include a description of the issue, its potential impact, affected versions or components, and clear reproduction steps where possible.

If private vulnerability reporting is unavailable, contact the repository maintainer privately through GitHub. Do not publish exploit details while a report is being reviewed.

Please do not include production credentials, access keys, tokens, personal data, or customer documents in a report.

## Deployment security baseline

Deployments should be reviewed and configured for the target environment. At minimum:

- Apply least-privilege IAM permissions and separate deployment/runtime roles.
- Use private networking where appropriate and restrict inbound access.
- Require TLS for data in transit.
- Encrypt S3 buckets and other supported data stores at rest.
- Store secrets in an approved secrets manager; never commit credentials.
- Redact credentials, tokens, and sensitive document content from logs.
- Protect Terraform state with an appropriately secured, encrypted, access-controlled backend.
- Review bucket policies, KMS permissions, retention, backup, and recovery settings.

The sample Terraform and application configuration are a starting point, not a complete security assessment or a certification of production readiness. Validate security controls against your organization's requirements before handling production data.
