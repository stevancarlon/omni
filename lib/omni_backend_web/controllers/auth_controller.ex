defmodule OmniBackendWeb.AuthController do
  use OmniBackendWeb, :controller

  alias OmniBackend.Accounts
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

  def me(conn, _params) do
    user = conn.assigns.current_user
    sub = Billing.get_subscription(user)

    json(conn, %{
      user: %{id: user.id, email: user.email, name: user.name},
      plan: (sub && sub.plan) || "free"
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
end
