defmodule OmniBackendWeb.Plugs.Entitlement do
  @moduledoc "Requires the authenticated user to have an active paid entitlement."

  import Plug.Conn
  alias OmniBackend.Billing

  def init(opts), do: opts

  def call(conn, _opts) do
    entitlement = Billing.get_entitlement(conn.assigns.current_user)

    if entitlement.active do
      assign(conn, :current_entitlement, entitlement)
    else
      conn
      |> put_status(:payment_required)
      |> Phoenix.Controller.json(%{error: "subscription_required", subscription: entitlement})
      |> halt()
    end
  end
end
