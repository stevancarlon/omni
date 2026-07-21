defmodule OmniBackendWeb.MemoryController do
  use OmniBackendWeb, :controller

  alias OmniBackend.Agent

  @doc """
  POST /api/agent/memories
  Stores a successful action sequence.
  Body: { "goal_text": "...", "action_sequence": [...], "app_context": "...", "steps_taken": N }
  """
  def create(conn, params) do
    user = conn.assigns.current_user

    attrs = %{
      user_id: user.id,
      goal_text: params["goal_text"],
      action_sequence: params["action_sequence"],
      app_context: params["app_context"],
      steps_taken: params["steps_taken"],
      success: params["success"] != false
    }

    case Agent.save_memory(attrs) do
      {:ok, memory} ->
        json(conn, %{id: memory.id, status: "saved"})

      {:error, changeset} ->
        conn
        |> put_status(:unprocessable_entity)
        |> json(%{error: "save_failed", detail: inspect(changeset.errors)})
    end
  end

  @doc """
  GET /api/agent/memories/search?goal=...
  Finds similar past action sequences.
  Returns up to 3 matches with similarity scores.
  """
  def search(conn, params) do
    user = conn.assigns.current_user
    goal = params["goal"] || ""

    if String.length(goal) < 3 do
      json(conn, %{memories: []})
    else
      memories = Agent.find_similar(goal, user.id)
      json(conn, %{memories: memories})
    end
  end
end
