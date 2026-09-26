# API walkthrough

The complete request and response contract is [openapi.yaml](openapi.yaml).
It describes 157 implemented operations, including the public authentication
routes and the separate credential required for locker provider events.

This walkthrough uses a local development client created with the `bootstrap`
command in the [README](../README.md#getting-started). Start the API first with
`./gradlew run`. The examples require Bash, curl, jq, and Python 3.9 or later.
Use a test client: these calls create real application records in its database.

## 1. Sign in as the bootstrapped administrator

Use the client ID printed by bootstrap and the administrator credentials you
configured. The password is read from the terminal without echoing it.

```bash
export API_BASE=http://localhost:8080
export TENANT_ID=replace-with-bootstrap-client-id
export ADMIN_EMAIL=admin@example.test
