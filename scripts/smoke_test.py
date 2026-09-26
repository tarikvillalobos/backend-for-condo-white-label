#!/usr/bin/env python3
"""Exercise the packaged server with an isolated, temporary persistent database."""
import datetime
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request


root = Path(__file__).resolve().parents[1]
command = root / "build/install/community-api/bin/community-api"
if not command.is_file():
    raise SystemExit("Run ./gradlew installDist first")

with tempfile.TemporaryDirectory(prefix="community-smoke-") as temporary:
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    env = {key: value for key, value in os.environ.items() if not key.startswith("SMTP_")}
    password = secrets.token_urlsafe(32)
    env.update(HOST="127.0.0.1", PORT=str(port), APP_ENV="test",
        DATABASE_URL=f"jdbc:h2:file:{temporary}/community;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        DATABASE_USER="sa", DATABASE_PASSWORD="", BOOTSTRAP_CLIENT_NAME="Smoke client",
        BOOTSTRAP_EMAIL="admin@example.test", BOOTSTRAP_PASSWORD=password)
    env.pop("BOOTSTRAP_CLIENT_ID", None)
    result = subprocess.run([str(command), "bootstrap"], env=env, cwd=root, capture_output=True, text=True, check=True)
    tenant = re.search(r"Client created: ([a-f0-9-]+)", result.stdout).group(1)
    for key in list(env):
        if key.startswith("BOOTSTRAP_"):
            del env[key]
    base = f"http://127.0.0.1:{port}"

    def request(method, path, payload=None, token=None, key=None, expected=200):
        headers = {}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        if key:
            headers["Idempotency-Key"] = key
        data = None
        if payload is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(payload).encode()
        req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
        try:
            response = urllib.request.urlopen(req, timeout=10)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            assert response.status == expected, f"{method} {path}: expected {expected}, received {response.status}"
            assert response.headers["X-Request-ID"]
            content = response.read()
            return json.loads(content) if content else None

