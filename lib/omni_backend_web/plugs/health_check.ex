defmodule OmniBackendWeb.Plugs.HealthCheck do
  import Plug.Conn

  def init(opts), do: opts

  def call(%Plug.Conn{request_path: "/api/health"} = conn, _opts) do
    conn
    |> put_resp_content_type("application/json")
    |> send_resp(200, ~s({"status":"ok"}))
    |> halt()
  end

  def call(conn, _opts), do: conn
end
