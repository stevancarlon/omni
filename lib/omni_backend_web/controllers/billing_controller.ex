defmodule OmniBackendWeb.BillingController do
  use OmniBackendWeb, :controller

  alias OmniBackend.Billing

  def status(conn, _params) do
    user = conn.assigns.current_user
    sub = Billing.get_subscription(user)

    json(conn, %{subscription: Billing.subscription_payload(sub)})
  end

  def verify_google(conn, params) do
    user = conn.assigns.current_user

    case Billing.verify_google_purchase(user, params) do
      {:ok, sub} ->
        json(conn, %{subscription: Billing.subscription_payload(sub)})

      {:error, reason} ->
        conn
        |> put_status(:bad_request)
        |> json(%{error: "purchase_verification_failed", detail: inspect(reason)})
    end
  end

  def restore_google(conn, _params) do
    user = conn.assigns.current_user
    sub = Billing.get_subscription(user)
    json(conn, %{subscription: Billing.subscription_payload(sub)})
  end

  @doc "Stripe webhook handler — receives events and updates local subscription state."
  def webhook(conn, _params) do
    with {:ok, raw_body} <- read_raw_body(conn),
         {:ok, event} <- verify_stripe_signature(conn, raw_body) do
      Billing.handle_stripe_event(event)
      json(conn, %{ok: true})
    else
      {:error, reason} ->
        conn
        |> put_status(:bad_request)
        |> json(%{error: inspect(reason)})
    end
  end

  defp read_raw_body(conn) do
    case Plug.Conn.read_body(conn) do
      {:ok, body, _conn} -> {:ok, body}
      _ -> {:error, :no_body}
    end
  end

  defp verify_stripe_signature(conn, raw_body) do
    webhook_secret = Application.get_env(:omni_backend, :stripe_webhook_secret)
    signature = List.first(get_req_header(conn, "stripe-signature")) || ""

    case Stripe.Webhook.construct_event(raw_body, signature, webhook_secret) do
      {:ok, event} -> {:ok, event}
      {:error, reason} -> {:error, reason}
    end
  end
end
