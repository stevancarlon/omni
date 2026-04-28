defmodule OmniBackendWeb.AppInventoryController do
  use OmniBackendWeb, :controller

  @doc """
  Generates a detailed app inventory report using Claude.

  POST /api/apps/inventory
  { "apps": [{"package": "com.whatsapp", "label": "WhatsApp"}, ...], "language": "pt-BR", "region": "BR" }
  → { "report": "..." }
  """
  def generate(conn, params) do
    api_key = Application.get_env(:omni_backend, :openai_api_key)

    if is_nil(api_key) or api_key == "" do
      conn
      |> put_status(:service_unavailable)
      |> json(%{error: "openai_key_missing"})
      |> halt()
    else
      apps = params["apps"] || []
      language = params["language"] || "en-US"
      region = params["region"] || "US"

      app_list =
        apps
        |> Enum.filter(&is_map/1)
        |> Enum.map(fn app ->
          "#{app["package"]} — #{app["label"]}"
        end)
        |> Enum.join("\n")

      prompt = build_prompt(app_list, language, region)

      case call_llm(api_key, prompt) do
        {:ok, report} ->
          json(conn, %{report: report})

        {:error, reason} ->
          conn
          |> put_status(:bad_gateway)
          |> json(%{error: "llm_error", detail: reason})
      end
    end
  end

  defp build_prompt(app_list, language, region) do
    """
    You are an expert Android power user. The user has these apps installed on their device.
    Their language is #{language} and region is #{region}.

    INSTALLED APPS:
    #{app_list}

    Generate a detailed app inventory report. For each app, provide:

    1. **Category** — group apps into: Messaging, Social, Media, Maps & Transport, Food & Delivery, Finance, Productivity, Shopping, Browser, Email, Health, Games, System, Other
    2. **Priority** — rate 1-5 based on how likely a #{region} user speaking #{language} would use this app daily (5 = essential, 1 = rarely used)
    3. **Scenarios** — detailed step-by-step navigation instructions for the 2-3 most common tasks in each app. Be VERY specific about UI element locations (bottom bar, top-right, floating button, etc.). Include the exact names of buttons/tabs as they appear in the app when set to #{language}.

    IMPORTANT:
    - Prioritize apps that are popular in #{region}. For example, if region is BR, WhatsApp and iFood are higher priority than Messenger or DoorDash.
    - Write scenario instructions in English (they are for an AI agent, not the end user).
    - Be specific about UI positions: "bottom navigation bar", "top-right corner", "floating action button (bottom-right)", "hamburger menu (top-left)".
    - Mention when an app has different navigation after updates (e.g., "Instagram: Reels tab may be center or right of bottom bar depending on version").
    - For messaging apps: the conversation the user wants may ALREADY be visible in the recent chats list — the agent should look at the current screen first before searching for contacts. Write scenarios that account for this (e.g., "Open app → check if the contact's chat is already visible in the Chats list → if yes, tap it directly; if not, tap the search icon or new chat button to find the contact").
    - For apps you don't recognize or are too niche, just list them with category and priority 1, no scenarios needed.
    - Skip system/bloatware apps that users rarely open intentionally (Samsung Members, Digital Wellbeing, etc.) — still list them but priority 1 and no scenarios.

    FORMAT your response as a structured report using markdown. Group by category, sort by priority (highest first) within each category. Example:

    ## Messaging
    ### WhatsApp (com.whatsapp) — Priority: 5
    - **Send message**: Open app → check if contact's chat is already visible in Chats list and tap it directly; otherwise tap search icon (top) to find the contact → tap text field at bottom → type message → tap green send arrow (right of text field)
    - **Voice call**: Open chat with contact → tap phone icon (top-right) → select "Voice call"
    - **Send photo**: Open chat → tap attachment icon (paperclip, right of text field) → tap "Gallery" → select photo → tap green send arrow

    ### Telegram (org.telegram.messenger) — Priority: 4
    ...

    Generate the full report now.
    """
  end

  defp call_llm(api_key, prompt) do
    case Req.post("https://api.openai.com/v1/chat/completions",
           headers: [
             {"authorization", "Bearer #{api_key}"},
             {"content-type", "application/json"}
           ],
           json: %{
             model: "gpt-4.1",
             max_tokens: 8192,
             messages: [
               %{role: "user", content: prompt}
             ]
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

        if content, do: {:ok, content}, else: {:error, "no text in response"}

      {:ok, %Req.Response{status: status, body: body}} ->
        {:error, "openai returned #{status}: #{inspect(body)}"}

      {:error, reason} ->
        {:error, "request failed: #{inspect(reason)}"}
    end
  end
end
