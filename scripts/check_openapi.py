#!/usr/bin/env python3
"""Validate the published contract and require a concrete Ktor handler per operation."""
from collections import Counter
from pathlib import Path
import re
import yaml
from openapi_spec_validator import validate_spec

root = Path(__file__).resolve().parents[1]
spec = yaml.safe_load((root / "docs/openapi.yaml").read_text())
validate_spec(spec)
methods = {"get", "post", "put", "patch", "delete"}
operations = {}
for path, item in spec["paths"].items():
    for method, operation in item.items():
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
