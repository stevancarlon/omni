# Security policy

## Reporting a vulnerability

Do not open a public issue. Use GitHub's private vulnerability reporting:

https://github.com/stevancarlon/omni/security/advisories/new

Include affected versions, impact, sanitized reproduction steps, and a proposed
fix if available. Do not access other people's data or test against accounts you
do not own.

High-priority reports include unauthorized Accessibility actions,
sensitive-app guard bypasses, exported-component abuse, authentication or token
disclosure, insecure signing/update behavior, screenshot leakage, and model
output that bypasses action validation.

## Supported versions

Omni is alpha software and receives security fixes on a best-effort basis. Only
the latest commit on `main` is currently supported. No version is designated as
long-term supported.

Only artifacts attached to releases in this repository and signed with the
documented official certificate should be treated as official. Forks and
third-party builds are operated by their distributors.
