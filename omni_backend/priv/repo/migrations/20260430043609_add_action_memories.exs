defmodule OmniBackend.Repo.Migrations.AddActionMemories do
  use Ecto.Migration

  def change do
    execute "CREATE EXTENSION IF NOT EXISTS pg_trgm"

    create table(:action_memories, primary_key: false) do
      add :id, :binary_id, primary_key: true, default: fragment("gen_random_uuid()")
      add :user_id, references(:users, type: :binary_id, on_delete: :delete_all), null: false
      add :goal_text, :text, null: false
      add :action_sequence, :jsonb, null: false
      add :app_context, :string
      add :steps_taken, :integer
      add :success, :boolean, default: true
      add :used_count, :integer, default: 0

      timestamps()
    end

    create index(:action_memories, [:user_id])

    execute "CREATE INDEX idx_action_memories_goal_trgm ON action_memories USING GIN (goal_text gin_trgm_ops)"
  end
end
