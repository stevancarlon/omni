defmodule OmniBackend.LLM.OpenAI do
  @moduledoc "OpenAI provider (GPT-4o, etc.)."
  @behaviour OmniBackend.LLM.Provider

  @default_model "gpt-5.5"

  def default_model, do: @default_model

  @impl true
  def completions(model, system, messages) do
    api_key = Application.get_env(:omni_backend, :openai_api_key)
    model = if model in [nil, ""], do: @default_model, else: model

    # Convert Claude-format image blocks to OpenAI format
    converted = Enum.map(messages, &convert_message/1)
    all_messages = [%{role: "system", content: system} | converted]

    case Req.post("https://api.openai.com/v1/chat/completions",
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
           receive_timeout: 120_000
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
        {:error, "openai returned #{status}: #{inspect(body)}"}

      {:error, reason} ->
        {:error, "openai unreachable: #{inspect(reason)}"}
    end
  end

  @doc "Convert Claude image format to OpenAI format in message content blocks."
  def convert_message(%{"content" => content} = msg) when is_list(content) do
    converted =
      Enum.map(content, fn
        %{
          "type" => "image",
          "source" => %{"type" => "base64", "media_type" => mt, "data" => data}
        } ->
          %{
            "type" => "image_url",
            "image_url" => %{"url" => "data:#{mt};base64,#{data}", "detail" => "low"}
          }

        block ->
          block
      end)

    Map.put(msg, "content", converted)
  end

  def convert_message(msg), do: msg
end
