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

  def append(system) when is_binary(system) do
    if String.contains?(system, "═══ NEXT ACTION PRIORITY ═══") do
      system
    else
      system <> @policy
    end
  end

  def append(_system), do: append(default_system())

  def default_system do
    "You are Omni, an AI assistant that controls Android devices. Respond only with valid JSON."
  end
end
