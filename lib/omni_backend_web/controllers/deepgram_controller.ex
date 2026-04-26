defmodule OmniBackendWeb.DeepgramController do
  use OmniBackendWeb, :controller

  @doc """
  Provisions a short-lived Deepgram session token for the Android client.

  Instead of embedding the Deepgram API key in the app, the client
  requests a temporary key from our backend, then connects directly
  to Deepgram's WebSocket.

  POST /api/deepgram/token
  → { "token": "...", "url": "wss://api.deepgram.com/v1/listen?..." }
  """
  def create_token(conn, params) do
    api_key = Application.get_env(:omni_backend, :deepgram_api_key)
    language = params["language"] || "multi"
    model = params["model"] || "nova-3"

    # Request a temporary key from Deepgram (valid 10 seconds — enough to open the socket)
    case Req.post("https://api.deepgram.com/v1/manage/keys",
           headers: [{"authorization", "Token #{api_key}"}],
           json: %{
             comment: "omni-session",
             scopes: ["usage:write"],
             time_to_live_in_seconds: 10
           }
         ) do
      {:ok, %Req.Response{status: 200, body: %{"key" => temp_key}}} ->
        ws_url = build_ws_url(temp_key, model, language)
        json(conn, %{token: temp_key, url: ws_url})

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

  defp build_ws_url(key, model, language) do
    params =
      URI.encode_query(%{
        "model" => model,
        "language" => language,
        "punctuate" => "true",
        "interim_results" => "true",
        "vad_events" => "true",
        "encoding" => "linear16",
        "sample_rate" => "16000",
        "token" => key
      })

    "wss://api.deepgram.com/v1/listen?#{params}"
  end
end
