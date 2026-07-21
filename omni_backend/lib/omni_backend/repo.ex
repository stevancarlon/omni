defmodule OmniBackend.Repo do
  use Ecto.Repo,
    otp_app: :omni_backend,
    adapter: Ecto.Adapters.Postgres
end
