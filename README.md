# OmniBackend

Phoenix API for Omni Android. All provider secrets live here; the Android app only stores an Omni session token.

## Required Environment

Copy `.env.example` to `.env` and fill in the values. Local Mix tasks load `.env` automatically.

- `GOOGLE_WEB_CLIENT_ID` verifies Google ID tokens from Android sign-in.
- `GOOGLE_PLAY_PACKAGE_NAME`, `GOOGLE_PLAY_*_PRODUCT_ID`, and `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` verify Android subscription purchases.
- Prefer `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON_B64` in hosted environments: base64-encode the Google Cloud service account JSON and set that single-line value instead of pasting multiline JSON.
- `DEFAULT_LLM_PROVIDER`, `DEFAULT_LLM_MODEL`, `GROQ_API_KEY`, and `DEEPGRAM_API_KEY` stay backend-side only.

## Local Database

Start Postgres with Docker Compose:

```bash
docker compose up -d postgres
```

Then create and migrate the database:

```bash
mix ecto.create
mix ecto.migrate
```

To start your Phoenix server:

* Run `mix setup` to install and setup dependencies
* Start Phoenix endpoint with `mix phx.server` or inside IEx with `iex -S mix phx.server`

Now you can visit [`localhost:4000`](http://localhost:4000) from your browser.

Ready to run in production? Please [check our deployment guides](https://hexdocs.pm/phoenix/deployment.html).

## Learn more

* Official website: https://www.phoenixframework.org/
* Guides: https://hexdocs.pm/phoenix/overview.html
* Docs: https://hexdocs.pm/phoenix
* Forum: https://elixirforum.com/c/phoenix-forum
* Source: https://github.com/phoenixframework/phoenix
