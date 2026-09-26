#!/usr/bin/env python3
"""Validate OpenAPI and check coverage against this repository's Ktor route layout."""
import json
from pathlib import Path
import re

from openapi_spec_validator import validate_spec
import yaml


root = Path(__file__).resolve().parents[1]
spec = yaml.safe_load((root / "docs/openapi.yaml").read_text())
validate_spec(spec)
actual = set()
for file in (root / "src/main/kotlin/com/community/api").rglob("*.kt"):
    stack = []
    community = file.parent.name == "community" and file.name not in ["NotificationRoutes.kt", "CommunityRoutes.kt"]
    for line in file.read_text().splitlines():
        match = re.match(r'^(\s*)(route|get|post|put|patch|delete)(?:\(("[^"]*"|path)\))?\s*\{', line)
        if not match:
            continue
        indent, kind, value = match.groups()
        depth = len(indent)
        while stack and stack[-1][0] >= depth:
            stack.pop()
        suffixes = ["/api/v1", "/api/v1/locations/{locationId}"] if value == "path" else [json.loads(value) if value else ""]
        prefix = stack[-1][1] if stack else ["/api/v1/locations/{locationId}" if community else ""]
        combined = [a + b for a in prefix for b in suffixes]
        if kind == "route":
            stack.append((depth, combined))
        else:
            actual.update((kind, path) for path in combined)
methods = {"get", "post", "put", "patch", "delete", "head", "options", "trace"}
documented = {(method, path) for path, item in spec["paths"].items() for method in item if method in methods}
assert actual == documented, f"Missing: {actual - documented}; extra: {documented - actual}"
seen = set()
for path, verbs in spec["paths"].items():
    for method, operation in verbs.items():
        if method not in methods:
            continue
        assert operation["operationId"] not in seen
        seen.add(operation["operationId"])
        parameters = {p["name"] for p in operation.get("parameters", []) if p.get("in") == "path"}
        assert parameters == set(re.findall(r"\{(.*?)\}", path)), path
        for status, response in operation["responses"].items():
            if status == "204":
                assert "content" not in response
print(f"OpenAPI validated: {len(actual)} operations with matching Ktor routes.")
