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
    language = non_blank(params["language"], "multi") |> normalize_language()
    model = non_blank(params["model"], "nova-3")
    keyterms = sanitize_keyterms(params["keyterms"])

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
          url: build_ws_url(model, language, keyterms)
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

  defp build_ws_url(model, language, keyterms) do
    base = [
      {"model", model},
      {"language", language},
      {"punctuate", "true"},
      {"interim_results", "true"},
      {"vad_events", "true"},
      {"encoding", "linear16"},
      {"sample_rate", "16000"},
      {"channels", "1"},
      {"endpointing", "600"},
      {"utterance_end_ms", "1000"},
      {"smart_format", "true"},
      {"filler_words", "false"},
      {"numerals", "true"}
    ]

    keyterm_pairs = Enum.map(keyterms, &{"keyterm", &1})

    query =
      (base ++ keyterm_pairs)
      |> Enum.map(fn {k, v} -> "#{URI.encode_www_form(k)}=#{URI.encode_www_form(v)}" end)
      |> Enum.join("&")

    "wss://api.deepgram.com/v1/listen?#{query}"
  end

  defp sanitize_keyterms(list) when is_list(list) do
    list
    |> Enum.filter(&is_binary/1)
    |> Enum.map(&String.trim/1)
    |> Enum.reject(&(&1 == ""))
    |> Enum.uniq()
    |> Enum.take(100)
  end

  defp sanitize_keyterms(_), do: []

  # Nova-3 uses base language codes ("pt", "es", "fr") not locale codes
  # ("pt-BR", "es-ES"). Strip the region suffix so transcription actually
  # uses the selected language instead of silently falling back to English.
  defp normalize_language("multi"), do: "multi"
  defp normalize_language(lang) do
    lang |> String.split(~r"[-_]") |> hd() |> String.downcase()
  end

  defp non_blank(value, default) when is_binary(value) do
    case String.trim(value) do
      "" -> default
      trimmed -> trimmed
    end
  end

  defp non_blank(_, default), do: default
end
