# Security Policy

This library verifies and produces signed webhooks, so security reports are taken seriously and handled with
priority.

## Supported versions

The project is pre-1.0. Security fixes are made on `main` and shipped in the next release; only the latest
released version receives fixes.

| Version | Supported |
| --- | --- |
| latest `0.x` release | ✅ |
| older releases | ❌ |

## Reporting a vulnerability

**Please do not open a public issue, discussion, or pull request for security problems.**

Report privately through GitHub Security Advisories:
**[Report a vulnerability](https://github.com/jinternals/webhooks/security/advisories/new)**
(repository → *Security* tab → *Report a vulnerability*).

If you cannot use GitHub, email **pandeymradul@gmail.com** with the subject `[SECURITY] webhooks`.

Please include:

- the affected module(s) and version / commit,
- a description of the issue and its impact,
- steps or a minimal proof-of-concept to reproduce,
- any suggested fix or mitigation.

## What to expect

- Acknowledgement within **3 business days**.
- An initial assessment (confirmed / not a vulnerability / need more info) within **10 business days**.
- Coordinated disclosure: we agree a disclosure date with you, publish a fix and a GitHub Security Advisory
  (requesting a CVE where appropriate), and credit you unless you prefer to stay anonymous.

## Scope

In scope — anything that undermines the toolkit's security guarantees, for example:

- signature verification bypass or forgery (`v1` HMAC-SHA256, `v2a` Ed25519),
- timing side channels in signature comparison,
- replay-window / timestamp validation bypass,
- secret or key leakage via logs, metrics, exceptions, or redaction failures,
- SSRF through producer destination handling or the URL allow-list.

Out of scope:

- the **demo credentials** in `webhooks-sample/` and test resources — they are intentionally public;
- vulnerabilities in third-party dependencies with no demonstrated impact through this library (please report
  those upstream; Dependabot and the weekly OWASP scan track them here);
- misconfiguration, such as running the consumer with verification disabled.
