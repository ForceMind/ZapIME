#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""仅供 CI 独立临时 Compose 项目；包含数据库清空/新服务器恢复的合成灾备演练。"""
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
SERVICE = ROOT / "services/sync"

def main():
    env = dict(os.environ, COMPOSE_PROJECT_NAME="zapime-ci-" + secrets.token_hex(6),
               POSTGRES_PASSWORD=secrets.token_hex(32))
    def command(args, capture=False):
        result = subprocess.run(args, cwd=SERVICE, env=env, check=True,
                                stdout=subprocess.PIPE if capture else None, text=True)
        return result.stdout if capture else None
    def compose(*args, capture=False):
        return command(["docker", "compose", *args], capture)
    def api(method, path, token, body=None):
        headers = {"Authorization": "Bearer " + token, "Content-Type": "application/json"}
        request = urllib.request.Request("http://127.0.0.1:8787" + path,
            data=json.dumps(body).encode() if body is not None else None, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            return error.code, json.load(error)
    try:
        compose("up", "-d", "--build", "--wait", "db", "sync")
        owner = json.loads(compose("exec", "-T", "sync", "/usr/local/bin/zapime-sync", "provision", "CI owner", capture=True))
        status, member = api("POST", "/v1/devices", owner["token"], {"name": "CI second device"})
        assert status == 201
        with tempfile.TemporaryDirectory(prefix="zapime-recovery-ci-") as directory:
            directory = Path(directory)
            fixture, packet, change_file = [directory / name for name in ("private-fixture.json", "ciphertext.json", "change.json")]
            probe = ROOT / "target/debug/examples/sync_fixture"
            subprocess.run([str(probe), "seal", owner["account_id"], str(fixture), str(packet)], check=True)
            operation = json.loads(packet.read_text())
            status, first = api("POST", "/v1/changes", owner["token"], operation)
            assert status == 200 and first["revision"] == 1
            status, retry = api("POST", "/v1/changes", owner["token"], operation)
            assert status == 200 and retry == first
            status, pulled = api("GET", "/v1/changes?after=0", member["token"])
            assert status == 200 and pulled["changes"] == [first]
            change_file.write_text(json.dumps(first))
            subprocess.run([str(probe), "verify", str(fixture), str(change_file)], check=True)
            status, _ = api("DELETE", "/v1/devices/" + member["device_id"], owner["token"])
            assert status == 200 and api("GET", "/v1/changes", member["token"])[0] == 401
            stored = compose("exec", "-T", "db", "psql", "-U", "zapime", "-d", "zapime", "-Atc",
                             "SELECT change::text FROM zapime_records", capture=True)
            assert "永久收藏恢复探针" not in stored and owner["token"] not in stored
            backup = directory / "database.dump"
            command(["bash", "backup.sh", str(backup)])
            # 项目名随机且只包含本脚本的合成数据；保留备份后销毁原数据库卷。
            compose("down", "--volumes")
            compose("up", "-d", "--wait", "db")
            command(["bash", "restore.sh", str(backup)])
            # 非空库不能再次覆盖。
            refusal = subprocess.run(["bash", "restore.sh", str(backup)], cwd=SERVICE, env=env,
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            assert refusal.returncode != 0
            compose("up", "-d", "--wait", "sync")
            status, page = api("GET", "/v1/changes?after=0", owner["token"])
            assert status == 200 and page["head"] == 1 and page["changes"] == [first]
            assert api("GET", "/v1/changes", member["token"])[0] == 401
            change_file.write_text(json.dumps(page["changes"][0]))
            subprocess.run([str(probe), "verify", str(fixture), str(change_file)], check=True)
        print("PASS: two-device ciphertext exchange, revoke, empty-server database restore and client key recovery")
    finally:
        compose("down", "--volumes")

if __name__ == "__main__":
    main()
