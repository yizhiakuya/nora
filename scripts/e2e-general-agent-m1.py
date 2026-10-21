"""M1 real HTTP acceptance. Creates only prefixed sessions and removes them in finally.

NORA_AUTH_TOKEN supplies credentials. Existing message bodies are read for search verification
but are never printed. The default run needs no model or direct database writes.
Pass --provider and --model together to also verify titles through real model turns.
"""
import argparse
import json
import os
import time
import uuid
from urllib.error import HTTPError
from urllib.parse import quote, urlencode
from urllib.request import ProxyHandler, Request, build_opener

parser = argparse.ArgumentParser()
parser.add_argument("--base", default="http://127.0.0.1:18080")
parser.add_argument("--provider", type=int)
parser.add_argument("--model")
args = parser.parse_args()
if bool(args.provider) != bool(args.model):
    parser.error("Live title acceptance requires both --provider and --model")
client = build_opener(ProxyHandler({}))
token = os.environ.get("NORA_AUTH_TOKEN", "")
prefix = "m1-e2e-" + uuid.uuid4().hex[:10]
created = []
deleted = set()
checks = 0


def call(path, body=None, method=None, expected=200):
    headers = {"Content-Type": "application/json", "Authorization": "Bearer " + token}
    request = Request(args.base + "/api" + path,
                      json.dumps(body).encode() if body is not None else None,
                      headers, method=method or ("POST" if body is not None else "GET"))
    try:
        response = client.open(request, timeout=30)
    except HTTPError as failure:
        response = failure
    with response:
        payload = json.loads(response.read())
        if response.status != expected:
            raise RuntimeError(f"{path.split('?')[0]}: HTTP {response.status}, expected {expected}")
    return payload.get("data") if isinstance(payload, dict) and "code" in payload else payload


def check(condition, label):
    global checks
    if not condition:
        raise RuntimeError(label)
    checks += 1
    print("PASS", label)


def search(query, offset=0, limit=30):
    return call("/chat/sessions/search?" + urlencode({"q": query, "offset": offset, "limit": limit}))


def send_real_turn(session_id, content):
    request = Request(args.base + f"/api/chat/sessions/{session_id}/messages",
                      json.dumps({"content": content, "providerId": args.provider, "model": args.model}).encode(),
                      {"Content-Type": "application/json", "Authorization": "Bearer " + token}, method="POST")
    with client.open(request, timeout=120) as response:
        stream = response.read().decode()
    check("event:done" in stream and "event:error" not in stream, "real model turn completed")


try:
    for index in range(35):
        session_id = f"{prefix}-{index:02d}"
        created.append(session_id)
        project = call(f"/chat/sessions/{session_id}/project", {"projectRoot": ""})
        check(bool(project["projectRoot"]) and bool(project["environment"]), "draft project binding " + str(index))
    first, second = search(prefix), search(prefix, 30)
    check(len(first["items"]) == 30 and first["hasMore"], "first page is bounded and has more")
    check(len(second["items"]) == 5 and not second["hasMore"], "last page ends at exact result count")
    ids = [row["id"] for row in first["items"] + second["items"]]
    check(len(set(ids)) == 35 and set(ids) == set(created), "pages have no duplicate or missing sessions")
    check([row["id"] for row in search(prefix)["items"]] == ids[:30], "stable order across reads")
    check(call(f"/chat/sessions/{created[0]}")["id"] == created[0], "direct session metadata")
    call(f"/chat/sessions/{created[0]}", method="DELETE")
    deleted.add(created[0])
    check(created[0] not in {row["id"] for row in search(prefix, limit=100)["items"]}, "deleted sessions excluded")
    call(f"/chat/sessions/{created[0]}", expected=400)
    call(f"/chat/sessions/{prefix}-missing", expected=400)
    check(True, "deleted and unknown deep links fail explicitly")
    check(not search(prefix + "%_")["items"], "percent and underscore are literal search characters")
    check(not search("' OR 1=1 -- " + prefix)["items"], "query stays a bound SQL parameter")
    for suffix in ["limit=0", "limit=101", "offset=-1", "q=" + "x" * 201]:
        call("/chat/sessions/search?" + suffix, expected=400)
    check(True, "pagination and query boundaries rejected")
    # Use a pre-existing real message rather than inserting artificial messages through SQL.
    corpus = call("/chat/sessions")
    verified = False
    for session in [row for row in corpus if row["messageCount"] > 0][:20]:
        messages = call("/chat/sessions/" + quote(session["id"], safe="") + "/messages")
        for message in messages:
            content = message.get("content") or ""
            fragment = content[-120:].strip()
            if len(fragment) < 40 or fragment.lower() in (session.get("title") or "").lower():
                continue
            matches = search(fragment, limit=100)
            check(session["id"] in {row["id"] for row in matches["items"]}, "message body search finds source session")
            verified = True
            break
        if verified:
            break
    if not verified:
        raise RuntimeError("Body-search acceptance requires an existing real message with at least 40 characters")
    check(isinstance(call("/chat/pending-actions"), list), "pending overview endpoint")
    check(isinstance(call("/chat/runs?status=completed,partial,failed,cancelled&limit=5"), list), "recent runs endpoint")
    check(isinstance(call("/saved-artifacts?limit=5"), list), "saved artifacts endpoint")
    if args.provider:
        session_id = created[1]
        send_real_turn(session_id, "产品验收：请仅回复收到，不要调用工具。标识 " + prefix)
        metadata = call(f"/chat/sessions/{session_id}")
        check(metadata["title"] != "新对话", "project-bound draft receives a first-message title")
        for _ in range(20):
            if metadata["titleGenerated"]:
                break
            time.sleep(0.5)
            metadata = call(f"/chat/sessions/{session_id}")
        check(metadata["titleGenerated"], "project-bound draft receives generated title")
        send_real_turn(session_id, "请仅回复继续收到，不要调用工具。")
        check(call(f"/chat/sessions/{session_id}")["title"] == metadata["title"], "later turns preserve generated title")
finally:
    cleanup_errors = []
    for session_id in created:
        if session_id in deleted:
            continue
        try:
            call(f"/chat/sessions/{session_id}", method="DELETE")
        except Exception as failure:
            cleanup_errors.append(str(failure))
    if cleanup_errors:
        raise RuntimeError("Cleanup failed: " + "; ".join(cleanup_errors))
print(f"PASS {checks} checks; test sessions removed")
