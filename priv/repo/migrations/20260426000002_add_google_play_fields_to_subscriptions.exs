defmodule OmniBackend.Repo.Migrations.AddGooglePlayFieldsToSubscriptions do
  use Ecto.Migration

  def change do
    alter table(:subscriptions) do
      add :google_package_name, :string
      add :google_product_id, :string
      add :google_purchase_token, :text
      add :google_order_id, :string
    end

    create index(:subscriptions, [:google_product_id])
  end
end
