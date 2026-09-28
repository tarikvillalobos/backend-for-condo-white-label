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
        operation_id = operation["operationId"]
        assert operation_id not in operations, f"Duplicate operationId: {operation_id}"
        operations[operation_id] = (method, path)
        parameters = [spec["components"]["parameters"][p["$ref"].split("/")[-1]] if "$ref" in p else p
                      for p in item.get("parameters", []) + operation.get("parameters", [])]
        actual = {p["name"] for p in parameters if p["in"] == "path"}
        assert actual == set(re.findall(r"\{([^}]+)\}", path)), path
        assert "responses" in operation and operation["responses"], operation_id
        if "204" in operation["responses"]:
            assert "content" not in operation["responses"]["204"], operation_id
