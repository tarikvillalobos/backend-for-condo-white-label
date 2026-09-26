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
read -r -s -p 'Administrator password: ' ADMIN_PASSWORD; printf '\n'

ADMIN_SESSION=$(jq -nc \
  --arg tenantId "$TENANT_ID" --arg email "$ADMIN_EMAIL" \
  --arg password "$ADMIN_PASSWORD" \
  '{tenantId:$tenantId,email:$email,password:$password,device:"API walkthrough"}' |
  curl -fsS "$API_BASE/api/v1/auth/login" \
    -H 'Content-Type: application/json' --data-binary @-)
ADMIN_TOKEN=$(printf '%s' "$ADMIN_SESSION" | jq -er .accessToken)
```

For the following calls, define a helper that sends JSON from standard input:

```bash
api_json() {
  local method="$1" token="$2" path="$3"
  shift 3
  curl -fsS -X "$method" "$API_BASE$path" \
    -H "Authorization: Bearer $token" \
    -H 'Content-Type: application/json' "$@" --data-binary @-
