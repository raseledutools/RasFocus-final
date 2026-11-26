#!/usr/bin/env python3
"""Delete ALL documents in Firestore `chat_users` (RasGram users).

Env: FIREBASE_SERVICE_ACCOUNT (service-account JSON), CONFIRM must equal DELETE.
Result summary is written to rasgram_reset_log.txt in repo root.
"""
import json, os, sys, datetime

LOG = "rasgram_reset_log.txt"
def log(msg):
    with open(LOG, "w") as f:
        f.write(f"{datetime.datetime.utcnow():%Y-%m-%d %H:%M:%S} UTC\n{msg}\n")
    print(msg)

if os.environ.get("CONFIRM", "") != "DELETE":
    log("ABORTED: CONFIRM != DELETE"); sys.exit(1)
raw = os.environ.get("FIREBASE_SERVICE_ACCOUNT", "").strip()
if not raw:
    log("FAILED: FIREBASE_SERVICE_ACCOUNT secret set করা নেই।"); sys.exit(1)

from google.oauth2 import service_account
from google.auth.transport.requests import Request, AuthorizedSession

info = json.loads(raw)
creds = service_account.Credentials.from_service_account_info(
    info, scopes=["https://www.googleapis.com/auth/datastore"])
creds.refresh(Request())
s = AuthorizedSession(creds)
base = f"https://firestore.googleapis.com/v1/projects/{info['project_id']}/databases/(default)/documents"

deleted = 0
while True:
    r = s.get(f"{base}/chat_users", params={"pageSize": 300, "mask.fieldPaths": "name"}, timeout=60)
    if r.status_code != 200:
        log(f"FAILED list: {r.status_code} {r.text[:500]}"); sys.exit(1)
    docs = r.json().get("documents", [])
    if not docs:
        break
    for d in docs:
        dr = s.delete(f"https://firestore.googleapis.com/v1/{d['name']}", timeout=30)
        if dr.status_code not in (200, 204):
            log(f"FAILED delete {d['name']}: {dr.status_code} {dr.text[:300]} (deleted so far: {deleted})"); sys.exit(1)
        deleted += 1
log(f"SUCCESS: chat_users থেকে {deleted} টি user মুছে ফেলা হয়েছে।")
