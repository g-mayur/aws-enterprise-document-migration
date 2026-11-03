# Contributing

1. Open an issue describing the use case or defect.
2. Keep connector-specific behavior inside a connector module.
3. Include unit tests and, where possible, integration tests using disposable resources.
4. Document authentication, permissions, pagination, rate limits, version semantics and unsupported capabilities.
5. Run `mvn -f application/pom.xml clean verify` and `terraform -chdir=infrastructure fmt -check -recursive`.
6. Never commit credentials, customer data, real manifests, state files or account-specific identifiers.

All contributions are submitted under Apache-2.0.
