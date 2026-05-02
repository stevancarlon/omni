defmodule OmniBackendWeb.HealthController do
  use OmniBackendWeb, :controller

  def check(conn, _params) do
    conn
    |> Plug.Conn.put_private(:phoenix_quiet, true)
    |> json(%{status: "ok"})
  end
end
