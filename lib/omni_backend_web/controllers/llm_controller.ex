defmodule OmniBackendWeb.LLMController do
  use OmniBackendWeb, :controller

  @moduledoc "Proxies LLM requests through backend-held provider keys."
  @doc """
  Non-streaming completion — returns the full LLM response in one JSON body.

  POST /api/llm/completions
  { "messages": [...], "system": "..." }
  → { "content": "..." }
  """
  def completions(conn, params) do
    _user = conn.assigns.current_user
    messages = params["messages"] || []
    system = params["system"]
    model = params["model"] || Application.get_env(:omni_backend, :default_llm_model)

    api_key = Application.get_env(:omni_backend, :groq_api_key)

    case Req.post("https://api.groq.com/openai/v1/chat/completions",
           headers: [
             {"authorization", "Bearer #{api_key}"},
             {"content-type", "application/json"}
           ],
           json: %{
             model: model,
             temperature: 0.2,
             max_completion_tokens: 4096,
             messages: chat_messages(messages, system),
             response_format: %{type: "json_object"}
           }
         ) do
      {:ok, %Req.Response{status: 200, body: resp_body}} ->
        content =
          resp_body
          |> Map.get("choices", [])
          |> Enum.find_value(fn
            %{"message" => %{"content" => text}} -> text
            _ -> nil
          end)

        json(conn, %{content: content || ""})

      {:ok, %Req.Response{status: status, body: resp_body}} ->
        conn
        |> put_status(:bad_gateway)
        |> json(%{error: "llm_error", status: status, detail: resp_body})

      {:error, reason} ->
        conn
        |> put_status(:bad_gateway)
        |> json(%{error: "llm_unreachable", detail: inspect(reason)})
    end
  end

  def stream(conn, params) do
    user = conn.assigns.current_user
    provider = params["provider"] || Application.get_env(:omni_backend, :default_llm_provider)
    model = params["model"] || Application.get_env(:omni_backend, :default_llm_model)
    messages = params["messages"] || []
    system = params["system"]

    conn =
      conn
      |> put_resp_content_type("text/event-stream")
      |> put_resp_header("cache-control", "no-cache")
      |> put_resp_header("x-accel-buffering", "no")
      |> send_chunked(200)

    case do_stream(conn, provider, model, messages, system, user) do
      {:ok, conn} -> conn
      {:error, conn} -> conn
    end
  end

  defp do_stream(conn, provider, model, messages, system, _user) do
    {url, headers, body} = build_request(provider, model, messages, system)

    case Req.post(url, headers: headers, json: body, into: :self) do
      {:ok, %Req.Response{status: 200}} ->
        stream_loop(conn, provider)

      {:ok, %Req.Response{status: status, body: body}} ->
        send_sse(conn, "error", %{message: "LLM returned #{status}", detail: body})

      {:error, reason} ->
        send_sse(conn, "error", %{message: "request_failed", detail: inspect(reason)})
    end
  end

  defp stream_loop(conn, provider) do
    receive do
      {_ref, {:data, data}} ->
        case parse_sse_chunk(data, provider) do
          {:token, text} ->
            {:ok, conn} = send_sse(conn, "token", %{text: text})
            stream_loop(conn, provider)

          :done ->
            send_sse(conn, "done", %{})

          {:error, msg} ->
            send_sse(conn, "error", %{message: msg})

          :skip ->
            stream_loop(conn, provider)
        end

      {_ref, :done} ->
        send_sse(conn, "done", %{})
    after
      30_000 ->
        send_sse(conn, "error", %{message: "timeout"})
    end
  end

  defp build_request("claude", model, messages, system) do
    api_key = Application.get_env(:omni_backend, :anthropic_api_key)

    {
      "https://api.anthropic.com/v1/messages",
      [
        {"x-api-key", api_key},
        {"anthropic-version", "2023-06-01"},
        {"content-type", "application/json"}
      ],
      %{
        model: model || "claude-sonnet-4-20250514",
        max_tokens: 4096,
        stream: true,
        system: system || "You are Omni, an AI assistant that controls Android devices.",
        messages: messages
      }
    }
  end

  defp build_request("openai", model, messages, system) do
    api_key = Application.get_env(:omni_backend, :openai_api_key)
    sys_msg = if system, do: [%{role: "system", content: system}], else: []

    {
      "https://api.openai.com/v1/chat/completions",
      [{"authorization", "Bearer #{api_key}"}, {"content-type", "application/json"}],
      %{model: model || "gpt-4o", stream: true, messages: sys_msg ++ messages}
    }
  end

  defp build_request("openrouter", model, messages, system) do
    api_key = Application.get_env(:omni_backend, :openrouter_api_key)
    sys_msg = if system, do: [%{role: "system", content: system}], else: []

    {
      "https://openrouter.ai/api/v1/chat/completions",
      [{"authorization", "Bearer #{api_key}"}, {"content-type", "application/json"}],
      %{
        model: model || "anthropic/claude-sonnet-4-20250514",
        stream: true,
        messages: sys_msg ++ messages
      }
    }
  end

  defp build_request("groq", model, messages, system) do
    api_key = Application.get_env(:omni_backend, :groq_api_key)

    {
      "https://api.groq.com/openai/v1/chat/completions",
      [{"authorization", "Bearer #{api_key}"}, {"content-type", "application/json"}],
      %{
        model: model || "llama-3.3-70b-versatile",
        temperature: 0.2,
        max_completion_tokens: 4096,
        stream: true,
        messages: chat_messages(messages, system)
      }
    }
  end

  defp parse_sse_chunk(data, "claude") do
    data
    |> String.split("\n", trim: true)
    |> Enum.reduce(:skip, fn line, acc ->
      case line do
        "data: " <> json_str ->
          case Jason.decode(json_str) do
            {:ok, %{"type" => "content_block_delta", "delta" => %{"text" => text}}} ->
              {:token, text}

            {:ok, %{"type" => "message_stop"}} ->
              :done

            {:ok, %{"type" => "error", "error" => %{"message" => msg}}} ->
              {:error, msg}

            _ ->
              acc
          end

        _ ->
          acc
      end
    end)
  end

  defp parse_sse_chunk(data, _openai_or_openrouter) do
    data
    |> String.split("\n", trim: true)
    |> Enum.reduce(:skip, fn line, acc ->
      case line do
        "data: [DONE]" ->
          :done

        "data: " <> json_str ->
          case Jason.decode(json_str) do
            {:ok, %{"choices" => [%{"delta" => %{"content" => text}} | _]}}
            when is_binary(text) ->
              {:token, text}

            _ ->
              acc
          end

        _ ->
          acc
      end
    end)
  end

  defp chat_messages(messages, system) do
    sys_msg =
      system ||
        "You are Omni, an AI assistant that controls Android devices. Respond only with valid JSON."

    [%{role: "system", content: sys_msg} | messages]
  end

  defp send_sse(conn, event, data) do
    chunk(conn, "event: #{event}\ndata: #{Jason.encode!(data)}\n\n")
  end
end
