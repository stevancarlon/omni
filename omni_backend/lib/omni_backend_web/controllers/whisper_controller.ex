defmodule OmniBackendWeb.WhisperController do
  use OmniBackendWeb, :controller

  @doc """
  Transcribes audio using OpenAI Whisper.

  POST /api/speech/transcribe
  Body: multipart/form-data with "audio" file (WAV/PCM) and optional "language" field
  → { "text": "transcribed text", "language": "pt" }
  """
  def transcribe(conn, params) do
    api_key = Application.get_env(:omni_backend, :openai_api_key)

    if is_nil(api_key) or api_key == "" do
      conn
      |> put_status(:service_unavailable)
      |> json(%{error: "openai_key_missing"})
    else
      audio = params["audio"]
      language = params["language"]

      if is_nil(audio) do
        conn |> put_status(:bad_request) |> json(%{error: "missing audio file"})
      else
        case transcribe_with_whisper(api_key, audio, language) do
          {:ok, text, detected_language} ->
            json(conn, %{text: text, language: detected_language})

          {:error, reason} ->
            conn
            |> put_status(:bad_gateway)
            |> json(%{error: "whisper_error", detail: reason})
        end
      end
    end
  end

  defp transcribe_with_whisper(api_key, audio, language) do
    file_content = File.read!(audio.path)
    filename = audio.filename || "audio.wav"
    content_type = audio.content_type || "audio/wav"

    form_parts = [
      {"file", {file_content, content_type: content_type, filename: filename}},
      {"model", "whisper-1"},
      {"response_format", "verbose_json"},
      {"temperature", "0.0"},
      {"prompt",
       "This is a voice command from a mobile assistant user. Common commands include opening apps, sending messages, making calls, playing music, and navigation."}
    ]

    # Add language hint if provided (improves accuracy)
    form_parts =
      if language && language != "" && language != "multi" do
        lang = language |> String.split(~r"[-_]") |> hd() |> String.downcase()
        form_parts ++ [{"language", lang}]
      else
        form_parts
      end

    case Req.post("https://api.openai.com/v1/audio/transcriptions",
           headers: [
             {"authorization", "Bearer #{api_key}"}
           ],
           form_multipart: form_parts,
           receive_timeout: 30_000
         ) do
      {:ok, %Req.Response{status: 200, body: body}} ->
        text = body["text"] || ""
        detected = body["language"] || ""
        {:ok, String.trim(text), detected}

      {:ok, %Req.Response{status: status, body: body}} ->
        {:error, "whisper returned #{status}: #{inspect(body)}"}

      {:error, reason} ->
        {:error, "request failed: #{inspect(reason)}"}
    end
  end
end
