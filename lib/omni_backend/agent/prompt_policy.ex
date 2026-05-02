defmodule OmniBackend.Agent.PromptPolicy do
  @moduledoc """
  Backend-owned policy appended to Omni's agent system prompt.

  Keep app-agnostic decision rules here so agent behavior can improve without
  requiring an Android release. The mobile client should still own perception
  details such as annotated marks and accessibility-node ranking.
  """

  @policy """

  ═══ NEXT ACTION PRIORITY ═══
  Act like a human finishing the current flow, not like a menu explorer.
  - If the screen shows a primary action that advances or commits the user's goal, tap it now.
  - Primary progress/commit labels include Start, Go, Continue, Next, Done, Send, Confirm, Save, Order, Book, Play, Call, Navigate, Open, Join, Pay, and localized equivalents.
  - Prefer direct primary actions over secondary controls like More, Options, Filters, Sort, Details, Share, Settings, overflow menus, category chips, suggestions, or informational cards.
  - Do not open modals, option sheets, filters, or alternate choices unless the current screen is missing required information for the user's goal.
  - If the requested target is already selected or visible and there is a clear action to begin/submit/continue, press that action instead of exploring nearby choices.
  - If a modal or sheet is blocking progress and it is not required, dismiss it with Back or a close control and return to the main flow.
  - When the goal is visibly accomplished, return done(success=true) immediately.
  """

  def append(system, opts \\ []) when is_binary(system) do
    system
    |> append_once("═══ NEXT ACTION PRIORITY ═══", @policy)
    |> append_app_profile(opts[:app_profile])
  end

  def append(_system, opts), do: append(default_system(), opts)

  def default_system do
    "You are Omni, an AI assistant that controls Android devices. Respond only with valid JSON."
  end

  def app_profile(:google_maps) do
    """

    ═══ APP PROFILE :google_maps — GOOGLE MAPS ═══
    The user usually wants to complete navigation, not explore place details.
    - If the goal is to start a route/navigation to a city/place, the priority is: find/select destination → Directions if needed → Start.
    - If the screen shows a destination bottom sheet with Start visible, tap Start immediately. Do not tap Share, Save, bookmark, photos, place cards, category chips, or overflow controls.
    - If both Directions and Start are visible, Start wins. It means the route is ready.
    - If Start is not visible but Directions is visible, tap Directions.
    - Avoid opening place-detail modals unless the destination is not selected yet or required route controls are missing.
    - The row with Restaurants, Attractions, Hotels, Share, Save, and similar chips is not progress for route-start tasks.
    - Once navigation has started or the route is active, return done(success=true).
    """
  end

  def app_profile(:youtube_music) do
    """

    ═══ APP PROFILE :youtube_music — YOUTUBE MUSIC ═══
    The user usually wants playback to begin.
    - Prefer Play, Shuffle, the top song/result, or the most direct media result over filters, overflow menus, radio settings, or account controls.
    - If a search result matching the requested song/artist/playlist is visible, tap the result or its primary play action.
    - Once playback is visibly active, return done(success=true).
    """
  end

  def app_profile(:youtube) do
    """

    ═══ APP PROFILE :youtube — YOUTUBE ═══
    The user usually wants a video opened or played.
    - Prefer Search, the best matching video result, and visible Play controls.
    - Do not open channel pages, filters, captions, Share, Save, or overflow menus unless required by the user.
    - If the requested video/content is playing or opened, return done(success=true).
    """
  end

  def app_profile(:ifood) do
    """

    ═══ APP PROFILE :ifood — IFOOD ═══
    The user usually wants to find/order food with minimum exploration.
    - Prefer search, restaurant/product results, Add, Continue, Checkout, Confirm, and Pay actions.
    - Do not open filters, sort sheets, coupons, profile, loyalty, or informational modals unless required by the goal.
    - If a modal blocks the ordering flow and is not required, dismiss it.
    - Stop when the order is placed or when required user-sensitive payment/address confirmation needs manual input.
    """
  end

  def app_profile(_), do: ""

  def infer_app_profile(goal, foreground_package) do
    haystack = "#{goal || ""} #{foreground_package || ""}" |> String.downcase()

    cond do
      contains_any?(haystack, [
        "google maps",
        "maps",
        "route",
        "directions",
        "navigate",
        "navigation",
        "drive to",
        "go to"
      ]) ||
          foreground_package == "com.google.android.apps.maps" ->
        :google_maps

      contains_any?(haystack, ["youtube music", "yt music", "play music"]) ||
          foreground_package == "com.google.android.apps.youtube.music" ->
        :youtube_music

      contains_any?(haystack, ["youtube", "you tube", "video"]) ||
          foreground_package == "com.google.android.youtube" ->
        :youtube

      contains_any?(haystack, ["ifood", "i food", "order food", "delivery", "restaurant"]) ||
          foreground_package == "br.com.brainweb.ifood" ->
        :ifood

      true ->
        nil
    end
  end

  defp append_app_profile(system, nil), do: system

  defp append_app_profile(system, profile) when is_binary(profile) do
    profile
    |> String.trim_leading(":")
    |> normalize_app_profile()
    |> then(&append_app_profile(system, &1))
  end

  defp append_app_profile(system, profile) when is_atom(profile) do
    append_once(system, "═══ APP PROFILE #{profile_atom(profile)}", app_profile(profile))
  end

  defp append_once(system, marker, text) do
    if String.contains?(system, marker) or text == "" do
      system
    else
      system <> text
    end
  end

  defp profile_atom(profile), do: inspect(profile)

  defp normalize_app_profile("google_maps"), do: :google_maps
  defp normalize_app_profile("youtube_music"), do: :youtube_music
  defp normalize_app_profile("youtube"), do: :youtube
  defp normalize_app_profile("ifood"), do: :ifood
  defp normalize_app_profile(_), do: nil

  defp contains_any?(text, needles) do
    Enum.any?(needles, &String.contains?(text, &1))
  end
end
