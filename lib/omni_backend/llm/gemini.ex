defmodule OmniBackend.LLM.Gemini do
  @moduledoc "Google Gemini provider (2.5 Pro, Flash, etc.)."
  @behaviour OmniBackend.LLM.Provider

  @default_model "gemini-2.5-pro"
  @fallback_model "gemini-2.5-flash"
  @cooldown_seconds 120

  require Logger

  @impl true
  def completions(model, system, messages) do
    model = if model in [nil, ""], do: effective_model(), else: model

    case do_request(model, system, messages) do
      {:ok, _} = success ->
        if model == @default_model, do: clear_cooldown()
        success

      {:error, reason} ->
        if model == @default_model do
          Logger.warning("Gemini #{@default_model} failed (#{reason}), falling back to #{@fallback_model} for #{@cooldown_seconds}s")
          set_cooldown()
          do_request(@fallback_model, system, messages)
        else
          {:error, reason}
        end
    end
  end

  defp effective_model do
    case :persistent_term.get(:gemini_cooldown_until, nil) do
      nil -> @default_model
      until ->
        if System.monotonic_time(:second) < until do
          @fallback_model
        else
          clear_cooldown()
          @default_model
        end
    end
  end

  defp set_cooldown do
    :persistent_term.put(:gemini_cooldown_until, System.monotonic_time(:second) + @cooldown_seconds)
  end

  defp clear_cooldown do
    :persistent_term.erase(:gemini_cooldown_until)
  rescue
    ArgumentError -> :ok
  end

  defp do_request(model, system, messages) do
    api_key = Application.get_env(:omni_backend, :gemini_api_key)
    gemini_contents = convert_messages(messages)

    url = "https://generativelanguage.googleapis.com/v1beta/models/#{model}:generateContent?key=#{api_key}"

    body = %{
      system_instruction: %{
        parts: [%{text: system}]
      },
      contents: gemini_contents,
      generationConfig: %{
        temperature: 0.2,
        maxOutputTokens: 4096,
        responseMimeType: "application/json"
      }
    }

    case Req.post(url,
           headers: [{"content-type", "application/json"}],
           json: body,
           receive_timeout: 120_000
         ) do
      {:ok, %Req.Response{status: 200, body: resp_body}} ->
        content =
          resp_body
          |> Map.get("candidates", [])
          |> List.first(%{})
          |> get_in(["content", "parts"])
          |> case do
            parts when is_list(parts) ->
              Enum.find_value(parts, "", fn
                %{"text" => text} -> text
                _ -> nil
              end)
            _ -> ""
          end

        {:ok, content}

      {:ok, %Req.Response{status: status, body: resp_body}} ->
        {:error, "gemini returned #{status}: #{inspect(resp_body)}"}

      {:error, reason} ->
        {:error, "gemini unreachable: #{inspect(reason)}"}
    end
  end

  # Convert Claude/OpenAI message format to Gemini format
  defp convert_messages(messages) do
    messages
    |> Enum.reject(fn msg -> msg["role"] == "system" end)
    |> Enum.map(fn msg ->
      role = if msg["role"] == "assistant", do: "model", else: "user"

      parts = case msg["content"] do
        content when is_list(content) ->
          Enum.map(content, fn
            %{"type" => "text", "text" => text} ->
              %{text: text}
            %{"type" => "image", "source" => %{"type" => "base64", "media_type" => mt, "data" => data}} ->
              %{inline_data: %{mime_type: mt, data: data}}
            other ->
              %{text: inspect(other)}
          end)
        content when is_binary(content) ->
          [%{text: content}]
        _ ->
          [%{text: ""}]
      end

      %{role: role, parts: parts}
    end)
  end

end
