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
