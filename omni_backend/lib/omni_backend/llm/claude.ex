defmodule OmniBackend.LLM.Claude do
  @moduledoc "Anthropic Claude provider with extended thinking."
  @behaviour OmniBackend.LLM.Provider

  @default_model "claude-opus-4-6"
  # Thinking budget — Claude reasons internally before responding.
  # Higher = deeper analysis on complex screens, but slower.
  @thinking_budget 10_000

  @impl true
  def default_model, do: @default_model

  @impl true
  def completions(model, system, messages) do
    api_key = Application.get_env(:omni_backend, :anthropic_api_key)
    model = if model in [nil, ""], do: @default_model, else: model

    # Claude uses a separate system param — strip system messages from the list
    claude_messages = Enum.reject(messages, fn msg -> msg["role"] == "system" end)

    case Req.post("https://api.anthropic.com/v1/messages",
           headers: [
             {"x-api-key", api_key},
             {"anthropic-version", "2025-04-14"},
             {"content-type", "application/json"}
           ],
           json: %{
             model: model,
             max_tokens: 16_000,
             system: system,
             messages: claude_messages,
             thinking: %{
               type: "enabled",
               budget_tokens: @thinking_budget
             }
           },
           receive_timeout: 120_000
         ) do
      {:ok, %Req.Response{status: 200, body: body}} ->
        content =
          body
          |> Map.get("content", [])
          |> Enum.find_value(fn
            %{"type" => "text", "text" => text} -> text
            _ -> nil
          end)

        {:ok, content || ""}

      {:ok, %Req.Response{status: status, body: body}} ->
        {:error, "claude returned #{status}: #{inspect(body)}"}

      {:error, reason} ->
        {:error, "claude unreachable: #{inspect(reason)}"}
    end
  end
end
