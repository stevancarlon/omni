defmodule OmniBackend.Repo.Migrations.CreateUsers do
  use Ecto.Migration

  def change do
    create table(:users, primary_key: false) do
      add :id, :binary_id, primary_key: true
      add :email, :citext, null: false
      add :password_hash, :string, null: false
      add :name, :string

      timestamps(type: :utc_datetime)
    end

    execute "CREATE EXTENSION IF NOT EXISTS citext", ""

    create unique_index(:users, [:email])
  end
end
