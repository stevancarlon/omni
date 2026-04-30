import Config

# config/runtime.exs is executed for all environments, including
# during releases. It is executed after compilation and before the
# system starts, so it is typically used to load production configuration
# and secrets from environment variables or elsewhere. Do not define
# any compile-time configuration in here, as it won't be applied.
# The block below contains prod specific runtime configuration.

# ## Using releases
#
# If you use `mix release`, you need to explicitly enable the server
# by passing the PHX_SERVER=true when you start it:
#
#     PHX_SERVER=true bin/omni_backend start
#
# Alternatively, you can use `mix phx.gen.release` to generate a `bin/server`
# script that automatically sets the env var above.
if System.get_env("PHX_SERVER") do
  config :omni_backend, OmniBackendWeb.Endpoint, server: true
end

config :omni_backend, OmniBackendWeb.Endpoint,
  http: [port: String.to_integer(System.get_env("PORT", "4000"))]

# API keys — set via environment variables
config :omni_backend,
  anthropic_api_key: System.get_env("ANTHROPIC_API_KEY"),
  openai_api_key: System.get_env("OPENAI_API_KEY"),
  openrouter_api_key: System.get_env("OPENROUTER_API_KEY"),
  groq_api_key: System.get_env("GROQ_API_KEY"),
  default_llm_provider: System.get_env("DEFAULT_LLM_PROVIDER", "claude"),
  default_llm_model: System.get_env("DEFAULT_LLM_MODEL"),
  deepgram_api_key: System.get_env("DEEPGRAM_API_KEY"),
  google_web_client_id: System.get_env("GOOGLE_WEB_CLIENT_ID"),
  google_play_package_name: System.get_env("GOOGLE_PLAY_PACKAGE_NAME"),
  google_play_pro_product_id: System.get_env("GOOGLE_PLAY_PRO_PRODUCT_ID", "omni_pro_monthly"),
  google_play_unlimited_product_id:
    System.get_env("GOOGLE_PLAY_UNLIMITED_PRODUCT_ID", "omni_unlimited_monthly"),
  google_play_service_account_json: System.get_env("GOOGLE_PLAY_SERVICE_ACCOUNT_JSON"),
  stripe_webhook_secret: System.get_env("STRIPE_WEBHOOK_SECRET")

config :stripity_stripe,
  api_key: System.get_env("STRIPE_SECRET_KEY")

if config_env() == :prod do
  database_url =
    System.get_env("DATABASE_URL") ||
      raise """
      environment variable DATABASE_URL is missing.
      For example: ecto://USER:PASS@HOST/DATABASE
      """

  maybe_ipv6 = if System.get_env("ECTO_IPV6") in ~w(true 1), do: [:inet6], else: []

  config :omni_backend, OmniBackend.Repo,
    # ssl: true,
    url: database_url,
    pool_size: String.to_integer(System.get_env("POOL_SIZE") || "10"),
    # For machines with several cores, consider starting multiple pools of `pool_size`
    # pool_count: 4,
    socket_options: maybe_ipv6

  # The secret key base is used to sign/encrypt cookies and other secrets.
  # A default value is used in config/dev.exs and config/test.exs but you
  # want to use a different value for prod and you most likely don't want
  # to check this value into version control, so we use an environment
  # variable instead.
  secret_key_base =
    System.get_env("SECRET_KEY_BASE") ||
      raise """
      environment variable SECRET_KEY_BASE is missing.
      You can generate one by calling: mix phx.gen.secret
      """

  host = System.get_env("PHX_HOST") || "example.com"

  config :omni_backend, :dns_cluster_query, System.get_env("DNS_CLUSTER_QUERY")

  config :omni_backend, OmniBackendWeb.Endpoint,
    url: [host: host, port: 443, scheme: "https"],
    http: [
      # Enable IPv6 and bind on all interfaces.
      # Set it to  {0, 0, 0, 0, 0, 0, 0, 1} for local network only access.
      # See the documentation on https://hexdocs.pm/bandit/Bandit.html#t:options/0
      # for details about using IPv6 vs IPv4 and loopback vs public addresses.
      ip: {0, 0, 0, 0, 0, 0, 0, 0}
    ],
    secret_key_base: secret_key_base

  # ## SSL Support
  #
  # To get SSL working, you will need to add the `https` key
  # to your endpoint configuration:
  #
  #     config :omni_backend, OmniBackendWeb.Endpoint,
  #       https: [
  #         ...,
  #         port: 443,
  #         cipher_suite: :strong,
  #         keyfile: System.get_env("SOME_APP_SSL_KEY_PATH"),
  #         certfile: System.get_env("SOME_APP_SSL_CERT_PATH")
  #       ]
  #
  # The `cipher_suite` is set to `:strong` to support only the
  # latest and more secure SSL ciphers. This means old browsers
  # and clients may not be supported. You can set it to
  # `:compatible` for wider support.
  #
  # `:keyfile` and `:certfile` expect an absolute path to the key
  # and cert in disk or a relative path inside priv, for example
  # "priv/ssl/server.key". For all supported SSL configuration
  # options, see https://hexdocs.pm/plug/Plug.SSL.html#configure/1
  #
  # We also recommend setting `force_ssl` in your config/prod.exs,
  # ensuring no data is ever sent via http, always redirecting to https:
  #
  #     config :omni_backend, OmniBackendWeb.Endpoint,
  #       force_ssl: [hsts: true]
  #
  # Check `Plug.SSL` for all available options in `force_ssl`.
end
