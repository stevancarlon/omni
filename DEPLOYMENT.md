# Deploying the Omni backend

The default configuration is intended for local development. Before exposing an
instance to the internet:

- Keep `COMMUNITY_MODE=false`. Community mode permits anonymous sessions and
  bypasses billing; it should only be used on a trusted local machine.
- Generate a strong `SECRET_KEY_BASE`, use a dedicated database account, enable
  TLS, and set an explicit `PHX_HOST`.
- Configure provider and billing credentials in the host's secret store, never in
  Git or a container image.
- Restrict database and application logs because agent prompts can contain private
  screen content.
- Put request, storage, and spend limits in front of all paid AI and speech
  providers.
- Back up the database, test restoration, and document deletion/retention policy.

Run the full project check before deploying:

```bash
mix precommit
```

Deploy only from a tagged commit and retain a rollback path for both the release
and database migrations.
