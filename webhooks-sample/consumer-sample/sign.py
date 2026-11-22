#!/usr/bin/env python3
"""Generate a Standard Webhooks signed curl command for the sample app.

Supports both signing schemes, selected by the secret/key prefix:

  • v1  (symmetric HMAC) — secret starts `whsec_`. Signs `webhook-id.webhook-timestamp.body`
    with HMAC-SHA256 using the base64-decoded key, header `webhook-signature: v1,<base64>`.

  • v2a (asymmetric Ed25519) — key starts `whsk_` (an Ed25519 PRIVATE key). Signs Ed25519 over
    SHA-256(`endpoint_id.webhook-id.webhook-timestamp.body`), header `v2a,<base64>`. Requires
    --endpoint-id, which is mixed into the signed content but never sent on the wire (SPEC §19i).
    v2a needs the `cryptography` package: pip install cryptography
"""
import argparse
import base64
import hashlib
import hmac
import json
import shlex
import time
import uuid


def sign_v1(secret: str, msg_id: str, timestamp: int, payload: str) -> str:
    key = secret[len("whsec_"):] if secret.startswith("whsec_") else secret
    key_bytes = base64.b64decode(key)
    to_sign = f"{msg_id}.{timestamp}.{payload}".encode("utf-8")
    digest = hmac.new(key_bytes, to_sign, hashlib.sha256).digest()
    return "v1," + base64.b64encode(digest).decode("utf-8")


def sign_v2a(private_key: str, endpoint_id: str, msg_id: str, timestamp: int, payload: str) -> str:
    try:
        from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    except ImportError as e:  # pragma: no cover - environment-dependent
        raise SystemExit("v2a signing needs the 'cryptography' package: pip install cryptography") from e
    raw = base64.b64decode(private_key[len("whsk_"):])  # the raw 32-byte Ed25519 seed
    key = Ed25519PrivateKey.from_private_bytes(raw)
    content = f"{endpoint_id}.{msg_id}.{timestamp}.{payload}".encode("utf-8")
    digest = hashlib.sha256(content).digest()  # Ed25519 signs the SHA-256 hash, not the raw content
    return "v2a," + base64.b64encode(key.sign(digest)).decode("utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description="Build a signed Standard Webhooks curl.")
    parser.add_argument("--secret", required=True, help="whsec_... (v1) or whsk_... (v2a) key")
    parser.add_argument("--url", required=True, help="target endpoint URL")
    parser.add_argument("--type", required=True, help="event type, e.g. contact.created")
    parser.add_argument("--data", required=True, help="JSON for the 'data' field")
    parser.add_argument(
        "--endpoint-id",
        help="v2a only: the endpoint-id configured on both sides (required when --secret is a whsk_ key)",
    )
    args = parser.parse_args()

    msg_id = f"msg_{uuid.uuid4().hex[:24]}"
    timestamp = int(time.time())
    body = json.dumps(
        {
            "type": args.type,
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "data": json.loads(args.data),
        },
        separators=(",", ":"),
    )

    if args.secret.startswith("whsk_"):
        if not args.endpoint_id:
            raise SystemExit("--endpoint-id is required for a v2a (whsk_) key")
        signature = sign_v2a(args.secret, args.endpoint_id, msg_id, timestamp, body)
    else:
        signature = sign_v1(args.secret, msg_id, timestamp, body)

    print(
        "curl -i -X POST {url} \\\n"
        "  -H 'content-type: application/json' \\\n"
        "  -H {id_hdr} \\\n"
        "  -H {ts_hdr} \\\n"
        "  -H {sig_hdr} \\\n"
        "  -d {body}".format(
            url=shlex.quote(args.url),
            id_hdr=shlex.quote(f"webhook-id: {msg_id}"),
            ts_hdr=shlex.quote(f"webhook-timestamp: {timestamp}"),
            sig_hdr=shlex.quote(f"webhook-signature: {signature}"),
            body=shlex.quote(body),
        )
    )


if __name__ == "__main__":
    main()
