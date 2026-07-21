defmodule OmniBackendWeb.AuthControllerTest do
  use OmniBackendWeb.ConnCase, async: false

  alias OmniBackend.Accounts.ApiToken
  alias OmniBackend.Repo

  setup do
    previous = Application.get_env(:omni_backend, :community_mode, false)
    on_exit(fn -> Application.put_env(:omni_backend, :community_mode, previous) end)
    :ok
  end

  test "community sign-in is unavailable by default", %{conn: conn} do
    Application.put_env(:omni_backend, :community_mode, false)

    conn = post(conn, ~p"/api/auth/community", %{})

    assert %{"error" => "community_mode_disabled"} = json_response(conn, 404)
  end

  test "community mode creates an entitled local session", %{conn: conn} do
    Application.put_env(:omni_backend, :community_mode, true)

    conn = post(conn, ~p"/api/auth/community", %{"device_name" => "test-device"})

    response = json_response(conn, 201)
    assert is_binary(response["authToken"])
    assert response["subscription"]["active"]
    assert response["subscription"]["plan"] == "community"

    stored_token = Repo.one!(ApiToken)
    refute stored_token.token == response["authToken"]
  end
end
