defmodule OmniBackend.Repo.Migrations.AddAptoideFieldsToSubscriptions do
  use Ecto.Migration

  def change do
    alter table(:subscriptions) do
      add :aptoide_package_name, :string
      add :aptoide_product_id, :string
      add :aptoide_purchase_token, :text
      add :aptoide_order_id, :string
    end

    create index(:subscriptions, [:aptoide_product_id])
    create index(:subscriptions, [:aptoide_order_id])
  end
end
