# This file is responsible for configuring your application
# and its dependencies with the aid of the Config module.
#
# This configuration file is loaded before any dependency and
# is restricted to this project.

# General application configuration
import Config

if File.exists?(".env") do
  ".env"
  |> File.stream!()
  |> Stream.map(&String.trim/1)
  |> Enum.each(fn
    "" ->
      :ok

    "#" <> _comment ->
      :ok

    line ->
      line = String.replace_prefix(line, "export ", "")

      case String.split(line, "=", parts: 2) do
        [key, value] ->
          value =
            value
            |> String.trim()
            |> String.trim("\"")
            |> String.trim("'")

          if System.get_env(key) == nil do
            System.put_env(key, value)
          end

        _ ->
          :ok
      end
  end)
end

config :omni_backend,
  ecto_repos: [OmniBackend.Repo],
  generators: [timestamp_type: :utc_datetime, binary_id: true]

# Configure the endpoint
config :omni_backend, OmniBackendWeb.Endpoint,
  url: [host: "localhost"],
  adapter: Bandit.PhoenixAdapter,
  render_errors: [
    formats: [json: OmniBackendWeb.ErrorJSON],
    layout: false
  ],
  pubsub_server: OmniBackend.PubSub,
  live_view: [signing_salt: "mwMpOWX0"]

# Configure Elixir's Logger
config :logger, :default_formatter,
  format: "$time $metadata[$level] $message\n",
  metadata: [:request_id]

# Use Jason for JSON parsing in Phoenix
config :phoenix, :json_library, Jason

# Stripe
config :stripity_stripe, api_key: System.get_env("STRIPE_SECRET_KEY")

# Import environment specific config. This must remain at the bottom
# of this file so it overrides the configuration defined above.
import_config "#{config_env()}.exs"
