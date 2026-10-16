#!/usr/bin/env python3
"""Isolated real-Netty smoke. Requires local PG trust on 127.0.0.1:55432,
createdb/dropdb, JDK17, and a built installDist. Generates credentials in memory;
does not print HTTP bodies, environment, tokens, or passwords."""
import base64
import hashlib
import json
import os
import pathlib
import secrets
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid

root = pathlib.Path(__file__).resolve().parents[2]
fixtures = json.loads((root / "src/test/resources/contract-fixtures.json").read_text())
database = "fsm_smoke_" + uuid.uuid4().hex
db_options = ["-h", "127.0.0.1", "-p", "55432"]
subprocess.run(["createdb", *db_options, database], check=True, capture_output=True)
password = secrets.token_urlsafe(24)
server = None
checks = []


def request(method, path, body=None, token=None, key=None, content_type="application/json"):
    if isinstance(body, dict):
        body = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode()
    headers = {"Content-Type": content_type}
    if token:
        headers["Authorization"] = "Bearer " + token
    if key:
        headers["Idempotency-Key"] = key
    req = urllib.request.Request("http://127.0.0.1:58080" + path, data=body,
                                 headers=headers, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        data = response.read()
        assert response.headers.get("X-Request-Id")
        return response.status, data, response.headers


try:
    with tempfile.TemporaryDirectory(prefix="fsm-netty-smoke-") as work:
        environment = {
            "PATH": os.defpath,
            "JAVA_HOME": os.environ["JAVA_HOME"],
            "JAVA_OPTS": "-Xmx256m",
            "DATABASE_URL": "jdbc:postgresql://127.0.0.1:55432/" + database,
            "DATABASE_USER": "vercel-sandbox",
            "DATABASE_PASSWORD": "",
            "JWT_SECRET_BASE64": base64.b64encode(secrets.token_bytes(32)).decode(),
            "PHOTO_STORAGE_DIR": work + "/photos",
            "SEED_DEMO": "true",
            "DEMO_LOGIN_1": "smoke.one",
            "DEMO_LOGIN_2": "smoke.two",
            "DEMO_PASSWORD_1": password,
            "DEMO_PASSWORD_2": secrets.token_urlsafe(24),
            "PORT": "58080",
        }
        # Startup requires a nonblank database password even though local test PG
        # uses trust; this random value is private and not actually verified by PG.
        environment["DATABASE_PASSWORD"] = secrets.token_urlsafe(24)
        with open(work + "/server.log", "wb") as log:
            server = subprocess.Popen([str(root / "build/install/fsm-backend/bin/fsm-backend")],
                                      env=environment, stdout=log, stderr=log)
            for attempt in range(100):
                if server.poll() is not None:
                    raise RuntimeError("server startup failed; no credentials printed")
                try:
                    if request("GET", "/health")[0] == 200:
                        break
                except (OSError, urllib.error.URLError):
                    pass
                time.sleep(.2)
            else:
                raise RuntimeError("server health timeout")
            checks.append("health")
            status, data, _ = request("POST", "/api/v1/auth/login",
                                      {"login": "smoke.one", "password": password})
            assert status == 200
            tokens = json.loads(data)
            access = tokens["accessToken"]
            status, data, _ = request("POST", "/api/v1/auth/refresh",
                                      {"refreshToken": tokens["refreshToken"]})
            assert status == 200 and json.loads(data)["refreshToken"] == tokens["refreshToken"]
            checks.append("login-refresh")
            status, data, _ = request("GET", "/api/v1/sync/snapshot", token=access)
            snapshot = json.loads(data)
            assert status == 200 and len(snapshot["workOrders"]) == 2
            assert request("GET", "/api/v1/work-orders/10000000-0000-4000-8000-000000000104",
                           token=access)[0] == 404
            checks.append("snapshot-scope")
            status, data, _ = request("POST", "/api/v1/auth/login", b'{"login":"x","login":"y","password":"z"}')
            assert status == 400 and json.loads(data)["error"]["code"] == "MALFORMED_REQUEST"
            status, data, _ = request("POST", "/api/v1/auth/login", b" " * 262145)
            assert status == 413 and json.loads(data)["error"]["code"] == "REQUEST_TOO_LARGE"
            assert json.loads(data)["error"]["details"] == {"maxBytes": 262144}
            checks.append("strict-json-request-limit")
            upload = fixtures["photoUpload"]
            multipart = base64.b64decode(upload["multipartBodyBase64"])
            path = upload["path"]
            key = str(uuid.uuid4())
            one = request("POST", path, multipart, access, key,
                          upload["headers"]["Content-Type"])
            assert one[0] == 201
            two = request("POST", path, multipart, access, key,
                          upload["headers"]["Content-Type"])
            assert one[:2] == two[:2]
            photo = json.loads(one[1])["photo"]
            status, binary, headers = request("GET", photo["contentPath"], token=access)
            assert status == 200 and hashlib.sha256(binary).hexdigest() == photo["sha256"]
            assert headers["ETag"] == '"' + photo["sha256"] + '"'
            checks.append("multipart-private-binary-replay")
            body = fixtures["closeNoPhotoRequest"]
            body["result"]["photoIds"] = [photo["serverPhotoId"]]
            key = str(uuid.uuid4())
            close_path = fixtures["close"]["path"]
            closed = request("POST", close_path, body, access, key)
            assert closed[0] == 200
            assert json.loads(closed[1])["workOrder"]["revision"] == 2
            assert request("POST", close_path, body, access, key)[:2] == closed[:2]
            assert request("POST", close_path, body, access, str(uuid.uuid4()))[0] == 409
            checks.append("close-receipt-replay-closed")
            server.terminate()
            server.wait(timeout=10)
            server = subprocess.Popen([str(root / "build/install/fsm-backend/bin/fsm-backend")],
                                      env=environment, stdout=log, stderr=log)
            for attempt in range(100):
                if server.poll() is not None:
                    raise RuntimeError("restart failed; sensitive details omitted")
                try:
                    if request("GET", "/health")[0] == 200:
                        break
                except (OSError, urllib.error.URLError):
                    pass
                time.sleep(.2)
            else:
                raise RuntimeError("restart health timeout")
            assert request("POST", close_path, body, access, key)[:2] == closed[:2]
            assert request("GET", photo["contentPath"], token=access)[1] == binary
            checks.append("real-process-restart-receipt-blob-persisted")
            assert request("POST", "/api/v1/auth/logout",
                           {"refreshToken": tokens["refreshToken"]})[0] == 204
            assert request("GET", "/api/v1/work-orders", token=access)[0] == 401
            checks.append("logout-revocation")
            # Same peer already has one successful login; failure count is bounded
            # and the final response must carry a retry hint, never credential detail.
            final = None
            for attempt in range(11):
                final = request("POST", "/api/v1/auth/login", {"login": "unknown", "password": "wrong"})
            assert final[0] == 429 and int(final[2]["Retry-After"]) > 0
            assert json.loads(final[1])["error"]["code"] == "RATE_LIMITED"
            checks.append("login-rate-limit")
finally:
    if server and server.poll() is None:
        server.terminate()
        try:
            server.wait(timeout=10)
        except subprocess.TimeoutExpired:
            server.kill()
            server.wait(timeout=5)
    subprocess.run(["dropdb", *db_options, database], check=True, capture_output=True)
print("SMOKE_OK " + ", ".join(checks))