defmodule OmniBackend.LLM.Groq do
  @moduledoc "Groq provider (Llama, Mixtral, etc.)."
  @behaviour OmniBackend.LLM.Provider

  @default_model "llama-3.3-70b-versatile"

  def default_model, do: @default_model

  @impl true
  def completions(model, system, messages) do
    api_key = Application.get_env(:omni_backend, :groq_api_key)
    model = if model in [nil, ""], do: @default_model, else: model

    # Groq doesn't support images — strip image blocks from messages
    stripped =
      Enum.map(messages, fn msg ->
        case msg["content"] do
          content when is_list(content) ->
            text_only = Enum.filter(content, fn block -> block["type"] != "image" end)
            # If only one text block remains, flatten to string
            case text_only do
              [%{"type" => "text", "text" => text}] -> Map.put(msg, "content", text)
              _ -> Map.put(msg, "content", text_only)
            end

          _ ->
            msg
        end
      end)

    all_messages = [%{role: "system", content: system} | stripped]

    case Req.post("https://api.groq.com/openai/v1/chat/completions",
           headers: [
             {"authorization", "Bearer #{api_key}"},
             {"content-type", "application/json"}
           ],
           json: %{
             model: model,
             temperature: 0.2,
             max_completion_tokens: 4096,
             messages: all_messages,
             response_format: %{type: "json_object"}
           },
           receive_timeout: 30_000
         ) do
      {:ok, %Req.Response{status: 200, body: body}} ->
        content =
          body
          |> Map.get("choices", [])
          |> Enum.find_value(fn
            %{"message" => %{"content" => text}} -> text
            _ -> nil
          end)

        {:ok, content || ""}

      {:ok, %Req.Response{status: status, body: body}} ->
        {:error, "groq returned #{status}: #{inspect(body)}"}

      {:error, reason} ->
        {:error, "groq unreachable: #{inspect(reason)}"}
    end
  end
end
