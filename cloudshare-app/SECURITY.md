# Security Policy

CloudShare is a security-focused file-sharing application, and we take
vulnerabilities seriously. This document describes which versions receive
security fixes and how to report a suspected vulnerability.

## Supported Versions

Only the most recent minor release line receives security patches. Given
this project's release cadence, older minor versions are not backported.

| Version | Supported          |
| ------- | ------------------ |
| 2.3.x   | :white_check_mark: |
| 2.2.x   | :x:                 |
| 2.1.x   | :x:                 |
| < 2.1   | :x:                 |

If you're running an unsupported version, please upgrade to the latest
release before reporting an issue — it may already be fixed. See
[`CHANGELOG.md`](CHANGELOG.md) for the full release history.

## Reporting a Vulnerability

**Please do not open a public GitHub issue for security vulnerabilities.**
Public issues are indexed and searchable, and a vulnerability report is
effectively a disclosure the moment it's filed.

Instead, please report suspected vulnerabilities privately using
[GitHub's private vulnerability reporting](https://github.com/Dhruv0306/cloudshare-app/security/advisories/new)
for this repository (Security tab → "Report a vulnerability"). This opens
a private advisory visible only to the maintainer and lets us coordinate
a fix before any public disclosure.

> **Note for maintainers:** this requires "Private vulnerability
> reporting" to be enabled under repo **Settings → Security →
> Vulnerability reporting**. If it's off, the link above 404s — verify
> it's enabled, or fall back to a direct contact method below.

If private advisories aren't available, you can reach the maintainer
directly at **`<maintainer-contact-email>`** *(replace before publishing —
placeholder, not a working address)*.

When reporting, please include as much of the following as you can:

- A description of the vulnerability and its potential impact
- Steps to reproduce, or a proof-of-concept if available
- The affected version(s) and component (e.g. a specific endpoint,
  service class, or Docker Compose configuration)
- Any suggested remediation, if you have one

### What to expect

- **Acknowledgement:** within 5 business days of the report.
- **Triage:** an initial assessment of severity and validity, communicated
  back to you, generally within 10 business days.
- **Resolution:** timeline depends on severity and complexity. Critical
  issues (e.g. authentication bypass, remote code execution, encryption
  key exposure) are prioritized for the fastest reasonable turnaround.
  Lower-severity issues are addressed in the normal release cycle.
- **Disclosure:** once a fix is released, the advisory is published with
  credit to the reporter (unless you'd prefer to remain anonymous).

### Scope

In scope: the application code in this repository (Spring Boot backend,
frontend SPA, Nginx configuration, Docker Compose orchestration, CI/CD
workflows) and its handling of authentication, authorization, encryption,
and file storage.

Out of scope: vulnerabilities in third-party dependencies themselves
(please report those upstream — though a note here on how CloudShare is
affected is still welcome), and issues requiring physical access to a
deployment or a already-compromised host.

## Security Design Reference

For details on the authentication, encryption, and defense-in-depth
architecture referenced above, see
[`docs/system-design/security.md`](docs/system-design/security.md).
