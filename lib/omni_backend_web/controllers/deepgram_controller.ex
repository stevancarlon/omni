defmodule OmniBackendWeb.DeepgramController do
  use OmniBackendWeb, :controller

  @doc """
  Provisions a short-lived Deepgram session token for the Android client.

  Instead of embedding the Deepgram API key in the app, the client
  requests a temporary token from our backend, then connects directly
  to Deepgram's WebSocket.

  POST /api/deepgram/token
  → { "access_token": "...", "expires_in": 30, "url": "wss://api.deepgram.com/v1/listen?..." }
  """
  def create_token(conn, params) do
    api_key = Application.get_env(:omni_backend, :deepgram_api_key)
    language = non_blank(params["language"], "multi")
    model = non_blank(params["model"], "nova-3")

    # Request a short-lived JWT from Deepgram. The token only needs to be valid
    # while the client opens the websocket; the connection can outlive the TTL.
    case Req.post("https://api.deepgram.com/v1/auth/grant",
           headers: [{"authorization", "Token #{api_key}"}],
           json: %{ttl_seconds: 60}
         ) do
      {:ok, %Req.Response{status: 200, body: %{"access_token" => access_token} = body}} ->
        json(conn, %{
          access_token: access_token,
          expires_in: body["expires_in"],
          url: build_ws_url(model, language)
        })

      {:ok, %Req.Response{status: 403, body: body}} ->
        conn
        |> put_status(:bad_gateway)
        |> json(%{
          error: "deepgram_key_forbidden",
          detail: body,
          message: "DEEPGRAM_API_KEY must have Member permissions to create auth grants"
        })

      {:ok, %Req.Response{status: status, body: body}} ->
        conn
        |> put_status(:bad_gateway)
        |> json(%{error: "deepgram_error", status: status, detail: body})

      {:error, reason} ->
        conn
        |> put_status(:bad_gateway)
        |> json(%{error: "deepgram_unreachable", detail: inspect(reason)})
    end
  end

  defp build_ws_url(model, language) do
    params =
      URI.encode_query(%{
        "model" => model,
        "language" => language,
        "punctuate" => "true",
        "interim_results" => "true",
        "vad_events" => "true",
        "encoding" => "linear16",
        "sample_rate" => "16000"
      })

    "wss://api.deepgram.com/v1/listen?#{params}"
  end

  defp non_blank(value, default) when is_binary(value) do
    case String.trim(value) do
      "" -> default
      trimmed -> trimmed
    end
  end

  defp non_blank(_, default), do: default
end
