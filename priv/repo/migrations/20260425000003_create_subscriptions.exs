defmodule OmniBackend.Repo.Migrations.CreateSubscriptions do
  use Ecto.Migration

  def change do
    create table(:subscriptions, primary_key: false) do
      add :id, :binary_id, primary_key: true
      add :stripe_customer_id, :string
      add :stripe_subscription_id, :string
      add :plan, :string, null: false, default: "free"
      add :status, :string, null: false, default: "active"
      add :current_period_end, :utc_datetime
      add :user_id, references(:users, type: :binary_id, on_delete: :delete_all), null: false

      timestamps(type: :utc_datetime)
    end

    create unique_index(:subscriptions, [:user_id])
    create unique_index(:subscriptions, [:stripe_customer_id])
    create unique_index(:subscriptions, [:stripe_subscription_id])
  end
end
