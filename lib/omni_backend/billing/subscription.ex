defmodule OmniBackend.Billing.Subscription do
  use Ecto.Schema
  import Ecto.Changeset

  @primary_key {:id, :binary_id, autogenerate: true}
  @foreign_key_type :binary_id

  schema "subscriptions" do
    field :stripe_customer_id, :string
    field :stripe_subscription_id, :string
    field :google_package_name, :string
    field :google_product_id, :string
    field :google_purchase_token, :string
    field :google_order_id, :string
    field :aptoide_package_name, :string
    field :aptoide_product_id, :string
    field :aptoide_purchase_token, :string
    field :aptoide_order_id, :string
    field :plan, :string, default: "free"
    field :status, :string, default: "active"
    field :current_period_end, :utc_datetime

    belongs_to :user, OmniBackend.Accounts.User

    timestamps(type: :utc_datetime)
  end

  def changeset(subscription, attrs) do
    subscription
    |> cast(attrs, [
      :stripe_customer_id,
      :stripe_subscription_id,
      :google_package_name,
      :google_product_id,
      :google_purchase_token,
      :google_order_id,
      :aptoide_package_name,
      :aptoide_product_id,
      :aptoide_purchase_token,
      :aptoide_order_id,
      :plan,
      :status,
      :current_period_end,
      :user_id
    ])
    |> validate_required([:user_id, :plan, :status])
    |> validate_inclusion(:plan, ~w(free pro unlimited))
    |> validate_inclusion(:status, ~w(active past_due canceled))
    |> unique_constraint(:user_id)
    |> unique_constraint(:stripe_customer_id)
    |> unique_constraint(:stripe_subscription_id)
  end
end
