defmodule OmniBackendWeb.AuthController do
  use OmniBackendWeb, :controller

  alias OmniBackend.Accounts
  alias OmniBackend.Accounts.GoogleAuth
  alias OmniBackend.Billing

  def register(conn, %{"email" => _, "password" => _} = params) do
    with {:ok, user} <- Accounts.register_user(params),
         {:ok, token} <- Accounts.create_api_token(user, params["device_name"]),
         {:ok, _sub} <- Billing.get_or_create_subscription(user) do
      conn
      |> put_status(:created)
      |> json(%{
        token: token.token,
        user: %{id: user.id, email: user.email, name: user.name}
      })
    else
      {:error, %Ecto.Changeset{} = changeset} ->
        conn
        |> put_status(:unprocessable_entity)
        |> json(%{errors: format_errors(changeset)})
    end
  end

  def login(conn, %{"email" => email, "password" => password} = params) do
    case Accounts.authenticate(email, password) do
      {:ok, user} ->
        {:ok, token} = Accounts.create_api_token(user, params["device_name"])

        json(conn, %{
          token: token.token,
          user: %{id: user.id, email: user.email, name: user.name}
        })

      {:error, _} ->
        conn
        |> put_status(:unauthorized)
        |> json(%{error: "invalid_credentials"})
    end
  end

  def google(conn, %{"idToken" => id_token} = params) do
    with {:ok, attrs} <- GoogleAuth.verify_id_token(id_token),
         {:ok, user} <- Accounts.get_or_create_google_user(attrs),
         {:ok, token} <-
           Accounts.create_api_token(user, params["device_name"] || params["platform"]),
         {:ok, sub} <- Billing.get_or_create_subscription(user) do
      json(conn, session_payload(user, token, sub))
    else
      {:error, :google_client_id_not_configured} ->
        conn
        |> put_status(:service_unavailable)
        |> json(%{error: "google_auth_not_configured"})

      {:error, reason} ->
        conn
        |> put_status(:unauthorized)
        |> json(%{error: "invalid_google_token", detail: inspect(reason)})
    end
  end

  def community(conn, params) do
    if Application.get_env(:omni_backend, :community_mode, false) do
      identifier = Ecto.UUID.generate()

      attrs = %{
        "email" => "community-#{identifier}@local.omni",
        "name" => "Community user",
        "password" => Base.url_encode64(:crypto.strong_rand_bytes(24), padding: false)
      }

      with {:ok, user} <- Accounts.register_user(attrs),
           {:ok, token} <- Accounts.create_api_token(user, params["device_name"]),
           {:ok, sub} <- Billing.get_or_create_subscription(user) do
        conn
        |> put_status(:created)
        |> json(session_payload(user, token, sub))
      end
    else
      conn
      |> put_status(:not_found)
      |> json(%{error: "community_mode_disabled"})
    end
  end

  def me(conn, _params) do
    user = conn.assigns.current_user
    sub = Billing.get_subscription(user)

    json(conn, %{
      user: Accounts.user_payload(user),
      subscription: Billing.subscription_payload(sub)
    })
  end

  def logout(conn, _params) do
    ["Bearer " <> token] = get_req_header(conn, "authorization")
    Accounts.revoke_api_token(token, conn.assigns.current_user)
    json(conn, %{ok: true})
  end

  defp format_errors(changeset) do
    Ecto.Changeset.traverse_errors(changeset, fn {msg, opts} ->
      Regex.replace(~r"%{(\w+)}", msg, fn _, key ->
        opts |> Keyword.get(String.to_existing_atom(key), key) |> to_string()
      end)
    end)
  end

  defp session_payload(user, token, sub) do
    subscription = Billing.subscription_payload(sub)

    %{
      authToken: token.token,
      token: token.token,
      user: Accounts.user_payload(user),
      email: user.email,
      name: user.name,
      subscription: subscription,
      subscriptionStatus: if(subscription.active, do: "active", else: "inactive")
    }
  end
end
