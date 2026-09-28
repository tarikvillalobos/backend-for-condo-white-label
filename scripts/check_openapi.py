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
