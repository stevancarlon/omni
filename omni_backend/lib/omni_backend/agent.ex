defmodule OmniBackend.Agent do
  @moduledoc "Context for agent action memories (MobileRAG pattern)."

  import Ecto.Query
  alias OmniBackend.Repo
  alias OmniBackend.Agent.ActionMemory

  @doc """
  Stores a successful action sequence for future retrieval.
  """
  def save_memory(attrs) do
    %ActionMemory{}
    |> ActionMemory.changeset(attrs)
    |> Repo.insert()
  end

  @doc """
  Finds similar past action sequences using trigram similarity.
  Returns up to 3 matches above the threshold.
  """
  def find_similar(goal_text, user_id, threshold \\ 0.4) do
    query =
      from m in ActionMemory,
        where: m.user_id == ^user_id and m.success == true,
        where: fragment("similarity(?, ?) > ?", m.goal_text, ^goal_text, ^threshold),
        order_by: [desc: fragment("similarity(?, ?)", m.goal_text, ^goal_text)],
        limit: 3,
        select: %{
          id: m.id,
          goal_text: m.goal_text,
          action_sequence: m.action_sequence,
          app_context: m.app_context,
          steps_taken: m.steps_taken,
          similarity: fragment("similarity(?, ?)", m.goal_text, ^goal_text)
        }

    Repo.all(query)
  end

  @doc """
  Increments the used_count for a memory that was retrieved and used.
  """
  def mark_used(memory_id) do
    from(m in ActionMemory, where: m.id == ^memory_id)
    |> Repo.update_all(inc: [used_count: 1])
  end
end
