defmodule OmniBackend.Accounts.GoogleAuth do
  @moduledoc "Verifies Google ID tokens for Android sign-in."

  @tokeninfo_url "https://oauth2.googleapis.com/tokeninfo"

  def verify_id_token(id_token) when is_binary(id_token) and byte_size(id_token) > 0 do
    client_id = Application.get_env(:omni_backend, :google_web_client_id)

    cond do
      is_nil(client_id) or client_id == "" ->
        {:error, :google_client_id_not_configured}

      true ->
        verify_with_google(id_token, client_id)
    end
  end

  def verify_id_token(_), do: {:error, :missing_id_token}

  defp verify_with_google(id_token, client_id) do
    case Req.get(@tokeninfo_url, params: [id_token: id_token]) do
      {:ok, %Req.Response{status: 200, body: body}} ->
        body = normalize_body(body)

        cond do
          body["aud"] != client_id ->
            {:error, :invalid_audience}

          body["email_verified"] not in [true, "true"] ->
            {:error, :email_not_verified}

          true ->
            {:ok,
             %{
               google_sub: body["sub"],
               email: body["email"],
               name: body["name"],
               avatar_url: body["picture"],
               auth_provider: "google"
             }}
        end

      {:ok, %Req.Response{status: status, body: body}} ->
        {:error, {:google_rejected_token, status, body}}

      {:error, reason} ->
        {:error, {:google_unreachable, reason}}
    end
  end

  defp normalize_body(body) when is_binary(body) do
    case Jason.decode(body) do
      {:ok, decoded} -> decoded
      _ -> %{}
    end
  end

  defp normalize_body(body), do: body || %{}
end
