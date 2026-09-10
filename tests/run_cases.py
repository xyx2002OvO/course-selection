#!/usr/bin/env python3
"""Execute HTTP/E2E cases from tests/case.yaml against the demo stack."""
from __future__ import annotations

import argparse
import base64
import json
import ssl
import subprocess
import sys
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
CASE_FILE = Path(__file__).resolve().parent / "case.yaml"
TERMINAL = {"SUCCESS", "REJECTED", "CANCELLED"}
STOCK = {101: 2, 102: 2, 103: 1, 104: 2, 105: 2}


class MiniYaml:
    """Minimal YAML loader for this catalog: maps, lists, scalars, comments."""

    def load(self, text: str) -> Any:
        lines = []
        for raw in text.splitlines():
            if not raw.strip() or raw.lstrip().startswith("#"):
                continue
            lines.append(raw.rstrip())
        items, _ = self._parse(lines, 0, 0)
        if not items:
            return {}
        if len(items) == 1:
            return items[0]
        return items

    def _parse(self, lines: list[str], i: int, indent: int) -> tuple[list[Any], int]:
        values: list[Any] = []
        mapping: dict[str, Any] | None = None
        while i < len(lines):
            line = lines[i]
            cur = len(line) - len(line.lstrip(" "))
            if cur < indent:
                break
            if cur > indent:
                raise ValueError(f"Bad indent at line {i + 1}: {line}")
            stripped = line[cur:]
            if stripped.startswith("- "):
                if mapping is not None:
                    values.append(mapping)
                    mapping = None
                item_text = stripped[2:]
                if ": " in item_text or (item_text.endswith(":") and not item_text.startswith("{")):
                    key, rest = self._split_key(item_text)
                    node: dict[str, Any] = {}
                    if rest is None:
                        child, i = self._parse(lines, i + 1, cur + 2)
                        node[key] = self._collapse(child)
                    else:
                        node[key] = rest
                        i += 1
                    while i < len(lines):
                        nxt = lines[i]
                        nxt_indent = len(nxt) - len(nxt.lstrip(" "))
                        if nxt_indent < cur + 2:
                            break
                        if nxt.lstrip().startswith("- "):
                            break
                        key2, rest2 = self._split_key(nxt[nxt_indent:])
                        if rest2 is None:
                            child, i = self._parse(lines, i + 1, nxt_indent + 2)
                            node[key2] = self._collapse(child)
                        else:
                            node[key2] = rest2
                            i += 1
                    values.append(node)
                    continue
                values.append(self._scalar(item_text))
                i += 1
                continue
            if mapping is None:
                mapping = {}
            key, rest = self._split_key(stripped)
            if rest is None:
                child, i = self._parse(lines, i + 1, cur + 2)
                mapping[key] = self._collapse(child)
            else:
                mapping[key] = rest
                i += 1
        if mapping is not None:
            values.append(mapping)
        return values, i

    def _collapse(self, items: list[Any]) -> Any:
        if not items:
            return {}
        if len(items) == 1:
            return items[0]
        return items

    def _split_key(self, text: str) -> tuple[str, Any]:
        if text.endswith(":") and not text.startswith('"'):
            return text[:-1], None
        key, raw = text.split(":", 1)
        raw = raw.strip()
        if raw == "" or raw == "|":
            return key.strip(), None
        return key.strip(), self._scalar(raw)

    def _scalar(self, text: str) -> Any:
        if text in ("null", "~", ""):
            return None
        if text in ("true", "True"):
            return True
        if text in ("false", "False"):
            return False
        if text.startswith("[") and text.endswith("]"):
            inner = text[1:-1].strip()
            if not inner:
                return []
            return [self._scalar(part.strip()) for part in inner.split(",")]
        if len(text) >= 2 and ((text[0] == text[-1] == '"') or (text[0] == text[-1] == "'")):
            return text[1:-1]
        try:
            if "." in text:
                return float(text)
            return int(text)
        except ValueError:
            return text


class CaseError(AssertionError):
    pass


class Runner:
    def __init__(self, doc: dict[str, Any], args: argparse.Namespace) -> None:
        self.doc = doc
        self.args = args
        cfg = dict(doc.get("config") or {})
        self.base = args.base_url.rstrip("/") if args.base_url else cfg.get("base_url", "http://localhost:18080")
        self.password = args.password or cfg.get("password", "demo-pass")
        self.term = int(cfg.get("term", 202601))
        self.timeout = float(cfg.get("timeout_sec", 10))
        poll = cfg.get("poll") or {}
        self.poll_timeout = float(args.wait if args.wait is not None else poll.get("timeout_sec", 30))
        self.vars: dict[str, Any] = {}
        self.compose_dir = ROOT

    def expand(self, value: Any) -> Any:
        if not isinstance(value, str) or "${" not in value:
            return value
        out = value
        while "${" in out:
            start = out.index("${")
            end = out.index("}", start)
            token = out[start + 2:end]
            cur: Any = self.vars
            for part in token.split("."):
                if isinstance(cur, dict) and part in cur:
                    cur = cur[part]
                else:
                    raise CaseError(f"unresolved placeholder ${{{token}}}")
            out = out[:start] + str(cur) + out[end + 1:]
        return out

    def auth(self, user: str | int | None) -> dict[str, str]:
        if user in (None, "none"):
            return {}
        token = base64.b64encode(f"{user}:{self.password}".encode()).decode()
        return {"Authorization": f"Basic {token}"}

    def url(self, path: str) -> str:
        if path.startswith("http"):
            return path
        return self.base + path

    def http(self, method: str, path: str, *, headers: dict[str, str] | None = None,
             body: dict[str, Any] | None = None, raw: bytes | None = None) -> tuple[int, dict[str, str], Any, str]:
        data = raw
        hdrs = dict(headers or {})
        hdrs.setdefault("Connection", "close")
        data = raw
        if body is not None:
            data = json.dumps(body).encode()
            hdrs.setdefault("Content-Type", "application/json")
        last_err: Exception | None = None
        for attempt in range(5):
            req = Request(self.url(path), data=data, method=method.upper(), headers=hdrs)
            ctx = ssl.create_default_context()
            try:
                with urlopen(req, timeout=self.timeout, context=ctx) as resp:
                    text = resp.read().decode("utf-8", "replace")
                    headers_out = {k: v for k, v in resp.headers.items()}
                    parsed = self._json(text)
                    return resp.status, headers_out, parsed, text
            except HTTPError as e:
                text = e.read().decode("utf-8", "replace")
                headers_out = {k: v for k, v in e.headers.items()} if e.headers else {}
                return e.code, headers_out, self._json(text), text
            except (URLError, TimeoutError, ConnectionError, OSError) as e:
                last_err = e
                time.sleep(0.4 * (attempt + 1))
        raise CaseError(f"{method} {path} failed: {last_err}") from last_err

    def _json(self, text: str) -> Any:
        if not text:
            return None
        try:
            return json.loads(text)
        except json.JSONDecodeError:
            return text

    def submit(self, student: int, course: int, request_id: str | None = None) -> tuple[str, int, Any, dict[str, str]]:
        rid = self.expand(request_id) if request_id else str(uuid.uuid4())
        headers = self.auth(student)
        headers["Idempotency-Key"] = rid
        status, hdrs, body, _ = self.http(
            "POST", f"/api/terms/{self.term}/selections", headers=headers, body={"courseId": course}
        )
        return rid, status, body, hdrs

    def status(self, student: int, request_id: str, term: int | None = None) -> tuple[int, Any, dict[str, str]]:
        t = self.term if term is None else term
        headers = self.auth(student)
        headers["Idempotency-Key"] = request_id
        code, hdrs, body, _ = self.http("GET", f"/api/terms/{t}/selections/{request_id}", headers=headers)
        return code, body, hdrs

    def poll(self, student: int, request_id: str) -> tuple[int, Any]:
        deadline = time.time() + self.poll_timeout
        interval = 0.4
        last = (0, None)
        while time.time() < deadline:
            code, body, _ = self.status(student, request_id)
            last = (code, body)
            if code == 200 and isinstance(body, dict) and body.get("state") in TERMINAL:
                return code, body
            if code in (400, 401, 403, 404) and code != 200:
                return code, body
            time.sleep(interval)
            interval = min(5.0, interval * 1.6)
        raise CaseError(f"request {request_id} not terminal within {self.poll_timeout}s: {last}")

    def compose(self, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
        cmd = ["docker", "compose", "-f", str(self.compose_dir / "compose.yaml"), *args]
        return subprocess.run(cmd, cwd=self.compose_dir, check=False, text=True, capture_output=True)

    def wait_api(self) -> None:
        deadline = time.time() + 90
        last = ""
        while time.time() < deadline:
            try:
                code, _, body, text = self.http("GET", "/actuator/health")
                last = f"{code} {text}"
                if code == 200 and (not isinstance(body, dict) or body.get("status") == "UP"):
                    return
            except Exception as e:
                last = str(e)
            time.sleep(2)
        raise SystemExit(f"API not ready at {self.base}: {last}")

    def reset_demo(self) -> None:
        sql = (
            "DELETE FROM outbox_event; DELETE FROM enrollment; DELETE FROM selection_request; "
            "UPDATE course SET remaining=capacity;"
        )
        mysql = self.compose(
            "exec", "-T", "mysql", "mysql", "-uselection", f"-p{self.doc['config'].get('db_password', 'selection-dev')}",
            "selection", "-e", sql
        )
        if mysql.returncode != 0:
            raise CaseError(f"mysql reset failed: {mysql.stderr}")
        redis = self.compose("exec", "-T", "redis", "redis-cli", "FLUSHDB")
        if redis.returncode != 0:
            raise CaseError(f"redis flush failed: {redis.stderr}")
        init = self.compose("run", "--rm", "--no-deps", "init")
        if init.returncode != 0:
            raise CaseError(f"init failed: {init.stderr or init.stdout}")

    def expect(self, actual: Any, spec: dict[str, Any], *, headers: dict[str, str] | None = None, status: int | None = None) -> None:
        if spec is None:
            return
        if "status" in spec:
            allowed = spec["status"] if isinstance(spec["status"], list) else [spec["status"]]
            if status not in allowed:
                raise CaseError(f"status {status} not in {allowed}; body={actual}")
        if "json" in spec:
            if not isinstance(actual, dict):
                raise CaseError(f"expected JSON object, got {actual!r}")
            for key, value in spec["json"].items():
                if actual.get(key) != value:
                    raise CaseError(f"json.{key}={actual.get(key)!r} != {value!r} in {actual}")
        if "json_in" in spec:
            if not isinstance(actual, dict):
                raise CaseError(f"expected JSON object, got {actual!r}")
            for key, value in spec["json_in"].items():
                allowed = value if isinstance(value, list) else [value]
                if actual.get(key) not in allowed:
                    raise CaseError(f"json.{key}={actual.get(key)!r} not in {allowed}; body={actual}")
        if "header" in spec:
            headers = headers or {}
            for key, value in spec["header"].items():
                found = next((headers[k] for k in headers if k.lower() == key.lower()), None)
                if found is None:
                    raise CaseError(f"missing header {key}")
                if value is not True and str(found) != str(value):
                    raise CaseError(f"header {key}={found!r} != {value!r}")
        if "code" in spec:
            if not isinstance(actual, dict) or actual.get("code") != spec["code"]:
                raise CaseError(f"code {spec['code']} not in {actual}")

    def run_step(self, step: dict[str, Any]) -> None:
        action = step.get("action")
        try:
            self._run_step(step)
        except Exception as e:
            raise CaseError(f"step {action}: {e}") from e

    def _run_step(self, step: dict[str, Any]) -> None:
        action = step.get("action")
        if action == "reset":
            self.reset_demo()
            return
        if action == "sleep":
            time.sleep(float(step.get("seconds", 1)))
            return
        if action == "compose":
            service = step["service"]
            verb = step["verb"]
            result = self.compose(verb, service)
            if result.returncode != 0:
                raise CaseError(result.stderr or result.stdout)
            if verb in ("start", "stop"):
                self.wait_api()
            if verb == "start" and service == "worker":
                deadline = time.time() + 45
                while time.time() < deadline:
                    try:
                        code, _, _, _ = self.http("GET", "http://localhost:18081/actuator/health")
                        if code == 200:
                            break
                    except CaseError:
                        time.sleep(1)
                else:
                    raise CaseError("worker health not ready after start")
            return
        if action == "http":
            headers = self.auth(step.get("auth"))
            extra = step.get("headers") or {}
            headers.update(extra)
            body = step.get("body")
            raw = step.get("raw_body")
            raw_bytes = raw.encode() if isinstance(raw, str) else None
            status, hdrs, parsed, _ = self.http(
                step.get("method", "GET"), step["path"], headers=headers, body=body, raw=raw_bytes
            )
            self.expect(parsed, step.get("expect") or {}, headers=hdrs, status=status)
            if step.get("as") and isinstance(parsed, dict):
                self.vars[step["as"]] = parsed
            return
        if action == "submit":
            rid, status, body, hdrs = self.submit(int(step["student"]), int(step["course"]), self.expand(step.get("request_id")))
            self.vars[step.get("as") or "last"] = {"id": rid, "student": int(step["student"]), "submit": body, "status": status}
            expect = dict(step.get("expect") or {})
            if "status" not in expect:
                expect["status"] = step.get("expect_status", [200, 202])
            self.expect(body, expect, headers=hdrs, status=status)
            if step.get("poll"):
                code, polled = self.poll(int(step["student"]), rid)
                self.vars[step.get("as") or "last"]["final"] = polled
                self.expect(polled, step.get("final") or {}, status=code)
            return
        if action == "poll":
            ref = self.vars[step["ref"]]
            code, body = self.poll(int(ref["student"]), ref["id"])
            self.vars[step.get("as") or step["ref"]]["final"] = body
            self.expect(body, step.get("expect") or {}, status=code)
            return
        if action == "query":
            student = int(step["student"])
            rid = self.expand(step.get("request_id")) if step.get("request_id") else self.vars[step["ref"]]["id"]
            code, body, hdrs = self.status(student, rid, step.get("term"))
            self.expect(body, step.get("expect") or {}, headers=hdrs, status=code)
            return
        if action == "parallel_submit":
            reqs = step["requests"]
            labelled = []
            with ThreadPoolExecutor(max_workers=len(reqs)) as pool:
                futs = {pool.submit(self.submit, int(item["student"]), int(item["course"])): item for item in reqs}
                for fut, item in futs.items():
                    rid, status, body, _ = fut.result()
                    labelled.append({"id": rid, "student": int(item["student"]), "status": status, "body": body})
            self.vars[step.get("as") or "batch"] = labelled
            expect = step.get("expect") or {}
            if step.get("poll"):
                for item in labelled:
                    if item["status"] >= 400:
                        continue
                    code, body = self.poll(item["student"], item["id"])
                    item["final"] = body
                    item["final_status"] = code
                success = [x for x in labelled if isinstance(x.get("final"), dict) and x["final"].get("state") == "SUCCESS"]
                sold = [x for x in labelled if x["status"] == 409 or (x.get("final") or {}).get("reason") == "SOLD_OUT"]
                if "success_count" in expect and len(success) != expect["success_count"]:
                    raise CaseError(f"success_count {len(success)} != {expect['success_count']}: {labelled}")
                if "min_conflict" in expect and len(sold) < expect["min_conflict"]:
                    raise CaseError(f"not enough sold-out/conflict outcomes: {labelled}")
            return
        if action == "burst_submit":
            n = int(step.get("count", 8))
            student = int(step["student"])
            course = int(step.get("course", 101))
            statuses = []
            for _ in range(n):
                _, status, body, _ = self.submit(student, course)
                statuses.append((status, body))
            if not any(status == 429 for status, _ in statuses):
                raise CaseError(f"expected 429 in burst, got {statuses}")
            return
        raise CaseError(f"unknown action {action}")

    def run_case(self, case: dict[str, Any]) -> str:
        if case.get("reset"):
            self.reset_demo()
        self.vars = {}
        for step in case.get("steps") or []:
            self.run_step(step)
        return "passed"

    def selected(self, case: dict[str, Any]) -> bool:
        tags = set(case.get("tags") or [])
        if not case.get("automated", True):
            return False
        if "slow" in tags and not self.args.slow:
            return False
        if self.args.tag and self.args.tag not in tags and case.get("id") != self.args.tag:
            return False
        if self.args.id and case.get("id") != self.args.id:
            return False
        return True

    def run(self) -> int:
        if self.args.start_stack:
            up = self.compose("up", "-d")
            if up.returncode != 0:
                raise SystemExit(up.stderr or up.stdout)
        self.wait_api()
        results = []
        failed = 0
        skipped = 0
        for suite in self.doc.get("suites") or []:
            for case in suite.get("cases") or []:
                cid = case.get("id")
                if not self.selected(case):
                    skipped += 1
                    results.append((cid, "skipped", ""))
                    continue
                started = time.time()
                try:
                    self.run_case(case)
                    results.append((cid, "passed", f"{time.time() - started:.1f}s"))
                    print(f"PASS  {cid}  {case.get('title')}")
                except Exception as e:
                    failed += 1
                    results.append((cid, "failed", str(e)))
                    print(f"FAIL  {cid}  {case.get('title')}")
                    print(f"      {e}")
                    if self.args.fail_fast:
                        break
            else:
                continue
            break
        print("---")
        print(f"passed={sum(1 for r in results if r[1]=='passed')} failed={failed} skipped={skipped}")
        out = ROOT / "tests" / "out" / "last-report.json"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps([{"id": a, "result": b, "detail": c} for a, b, c in results], ensure_ascii=False, indent=2), encoding="utf-8")
        return 1 if failed else 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Run course-selection YAML cases")
    parser.add_argument("--base-url", default=None)
    parser.add_argument("--password", default=None)
    parser.add_argument("--id", dest="id", default=None, help="Run a single case id")
    parser.add_argument("--tag", default=None)
    parser.add_argument("--slow", action="store_true")
    parser.add_argument("--start-stack", action="store_true")
    parser.add_argument("--fail-fast", action="store_true")
    parser.add_argument("--wait", type=float, default=None)
    args = parser.parse_args()
    text = CASE_FILE.read_text(encoding="utf-8")
    try:
        import yaml  # type: ignore
        doc = yaml.safe_load(text)
    except Exception:
        doc = MiniYaml().load(text)
    if not isinstance(doc, dict):
        raise SystemExit("case.yaml did not parse into a mapping")
    return Runner(doc, args).run()


if __name__ == "__main__":
    sys.exit(main())
