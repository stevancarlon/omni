# Security policy

## Reporting

Do not open public issues for vulnerabilities. Use a private GitHub security advisory at:

https://github.com/stevancarlon/omni-backend/security/advisories/new

Include affected versions, impact, sanitized reproduction steps, and a proposed fix if available. Never include live credentials, real screenshots, user prompts, access tokens, or database records.

## Operational warning

`COMMUNITY_MODE=true` intentionally allows anonymous local sessions and bypasses paid entitlements. It is for private development only. Exposing a community-mode server publicly can let strangers consume provider credits and store agent data.

Operators are responsible for TLS, firewalling, secret storage, provider retention, backups, database access, abuse controls, and deletion policies. Omni is alpha software and receives security fixes on a best-effort basis.
