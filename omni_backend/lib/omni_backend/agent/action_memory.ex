defmodule OmniBackend.Agent.ActionMemory do
  use Ecto.Schema
  import Ecto.Changeset

  @primary_key {:id, :binary_id, autogenerate: true}
  @foreign_key_type :binary_id

  schema "action_memories" do
    field :goal_text, :string
    field :action_sequence, {:array, :map}, default: []
    field :app_context, :string
    field :steps_taken, :integer
    field :success, :boolean, default: true
    field :used_count, :integer, default: 0

    belongs_to :user, OmniBackend.Accounts.User

    timestamps()
  end

  def changeset(memory, attrs) do
    memory
    |> cast(attrs, [:goal_text, :action_sequence, :app_context, :steps_taken, :success, :user_id])
    |> validate_required([:goal_text, :action_sequence, :user_id])
  end
end
