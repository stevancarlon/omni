defmodule OmniBackend.Repo.Migrations.AddGoogleIdentityToUsers do
  use Ecto.Migration

  def change do
    alter table(:users) do
      modify :password_hash, :string, null: true
      add :google_sub, :string
      add :avatar_url, :string
      add :auth_provider, :string, null: false, default: "password"
    end

    create unique_index(:users, [:google_sub])
  end
end
