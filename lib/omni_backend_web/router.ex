defmodule OmniBackendWeb.Router do
  use OmniBackendWeb, :router

  pipeline :api do
    plug :accepts, ["json"]
  end

  pipeline :authenticated do
    plug OmniBackendWeb.Plugs.Auth
  end

  pipeline :entitled do
    plug OmniBackendWeb.Plugs.Entitlement
  end

  # Public routes
  scope "/api", OmniBackendWeb do
    pipe_through :api

    post "/auth/register", AuthController, :register
    post "/auth/login", AuthController, :login
    post "/auth/google", AuthController, :google

    # Stripe webhook (no auth — verified via signature)
    post "/billing/webhook", BillingController, :webhook
  end

  # Protected routes
  scope "/api", OmniBackendWeb do
    pipe_through [:api, :authenticated]

    get "/auth/me", AuthController, :me
    delete "/auth/logout", AuthController, :logout

    # Billing
    get "/billing/status", BillingController, :status
    post "/billing/google/verify", BillingController, :verify_google
    post "/billing/google/restore", BillingController, :restore_google
  end

  # Paid entitlement routes
  scope "/api", OmniBackendWeb do
    pipe_through [:api, :authenticated, :entitled]

    # LLM
    post "/llm/stream", LLMController, :stream
    post "/llm/completions", LLMController, :completions

    # Deepgram session token
    post "/deepgram/token", DeepgramController, :create_token
  end

  # Enable LiveDashboard in development
  if Application.compile_env(:omni_backend, :dev_routes) do
    import Phoenix.LiveDashboard.Router

    scope "/dev" do
      pipe_through [:fetch_session, :protect_from_forgery]

      live_dashboard "/dashboard", metrics: OmniBackendWeb.Telemetry
    end
  end
end
