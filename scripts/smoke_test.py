#!/usr/bin/env python3
"""Exercise the packaged v1 API, PostgreSQL-compatible schema, and restart persistence."""
import base64
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
import uuid

root = Path(__file__).resolve().parents[1]
command = root / "build/install/community-api/bin/community-api"
if not command.is_file():
    raise SystemExit("Run ./gradlew installDist first")

with tempfile.TemporaryDirectory(prefix="community-v1-smoke-") as temporary:
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    password = secrets.token_urlsafe(24)
    env = {key: value for key, value in os.environ.items() if not key.startswith("SMTP_")}
    env.update(HOST="127.0.0.1", PORT=str(port), APP_ENV="test",
        DATABASE_URL=f"jdbc:h2:file:{temporary}/community;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        DATABASE_USER="sa", DATABASE_PASSWORD="", BOOTSTRAP_CLIENT_NAME="Smoke client",
        BOOTSTRAP_EMAIL="admin@example.test", BOOTSTRAP_PASSWORD=password,
        API_ENCRYPTION_KEY=base64.urlsafe_b64encode(secrets.token_bytes(32)).decode().rstrip("="))
    env.pop("BOOTSTRAP_CLIENT_ID", None)
    result = subprocess.run([str(command), "bootstrap"], env=env, cwd=root, capture_output=True, text=True, check=True)
    brand = re.search(r"Brand ID \(X-Brand-Id\): ([a-f0-9-]+)", result.stdout).group(1)
    for key in list(env):
        if key.startswith("BOOTSTRAP_"):
            del env[key]
    base = f"http://127.0.0.1:{port}"

    def request(method, path, payload=None, token=None, key=None, expected=200, brand_header=True):
        headers = {"X-Brand-Id": brand} if brand_header else {}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        if key:
            headers["Idempotency-Key"] = key
        data = None
        if payload is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(payload).encode()
        call = urllib.request.Request(base + path, data=data, headers=headers, method=method)
        try:
            response = urllib.request.urlopen(call, timeout=10)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            content = response.read()
            assert response.status == expected, f"{method} {path}: {response.status} != {expected}; {content[:300]!r}"
            assert response.headers["X-Request-ID"]
            return json.loads(content) if content else None

    def start(log):
        process = subprocess.Popen([str(command)], env=env, cwd=root, stdout=log, stderr=subprocess.STDOUT)
        try:
            for _ in range(150):
                if process.poll() is not None:
                    raise RuntimeError("Server exited before becoming ready")
                try:
                    if request("GET", "/v1/health/ready", brand_header=False)["status"] == "ok":
                        return process
                except (urllib.error.URLError, TimeoutError):
                    time.sleep(0.1)
            raise RuntimeError("Server startup timed out")
        except BaseException:
            process.terminate()
            process.wait(timeout=15)
            raise

    def stop(process):
        process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()

    with open(Path(temporary) / "server.log", "w") as log:
        process = start(log)
        try:
            assert request("GET", "/v1/configuration")["brandId"] == brand
            assert request("GET", "/v1/configuration", brand_header=False, expected=400)["code"] == "VALIDATION_ERROR"
            admin = request("POST", "/v1/auth/password/login",
                {"identifier": "admin@example.test", "password": password})["accessToken"]
            modules = {name: True for name in ("parcels lockers cameras visitors pets reservations vehicles announcements "
                "requests occurrences events maintenance documents contacts").split()}
            condo = {"name": "Smoke condominium", "address": "Example 100", "timeZone": "UTC", "modules": modules}
            key = str(uuid.uuid4())
            first = request("POST", "/v1/admin/condominiums", condo, admin, key, 201)
            replay = request("POST", "/v1/admin/condominiums", condo, admin, key, 201)
            package_path = prefix + "/packages/" + package["id"]
            credential = request("POST", package_path + "/credential", {}, resident)["credential"]
            collected = request("POST", package_path + "/confirm-pickup", {"collectorId": invitation["userId"], "credential": credential}, admin)
            assert collected["status"] == "COLLECTED"
            facility = request("POST", prefix + "/facilities", {"name": "Meeting room", "timeZone": "UTC", "capacity": 10}, admin, expected=201)["id"]
            tomorrow = datetime.datetime.now(datetime.timezone.utc).replace(hour=12, minute=0, second=0, microsecond=0) + datetime.timedelta(days=1)
            reservation = {"facilityId": facility, "startsAt": tomorrow.isoformat(), "endsAt": (tomorrow + datetime.timedelta(hours=1)).isoformat()}
            request("POST", prefix + "/reservations", reservation, resident, "smoke-booking", 201)
            request("POST", prefix + "/reservations", reservation, resident, "conflicting-booking", 409)
        finally:
            stop(process)
        process = start(log)
        try:
            persisted = request("GET", package_path, token=resident)
            assert persisted["status"] == "COLLECTED"
        finally:
            stop(process)
print("Smoke test passed: bootstrap, login, invitation, delivery, pickup, reservation conflict, and persistence after restart.")
