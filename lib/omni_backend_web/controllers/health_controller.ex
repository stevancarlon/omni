defmodule OmniBackendWeb.HealthController do
  use OmniBackendWeb, :controller

  def check(conn, _params) do
    json(conn, %{status: "ok"})
  end
end
