defmodule OmniBackend.Billing do
  alias OmniBackend.Repo
  alias OmniBackend.Billing.{GooglePlay, Subscription}

  def get_or_create_subscription(user) do
    case Repo.get_by(Subscription, user_id: user.id) do
      nil ->
        %Subscription{}
        |> Subscription.changeset(%{user_id: user.id, plan: "free", status: "active"})
        |> Repo.insert()

      sub ->
        {:ok, sub}
    end
  end

  def get_subscription(user) do
    Repo.get_by(Subscription, user_id: user.id)
  end

  def subscription_payload(nil) do
    %{
      plan: "free",
      status: "inactive",
      active: false,
      current_period_end: nil
    }
  end

  def subscription_payload(%Subscription{} = subscription) do
    %{
      plan: subscription.plan,
      status: subscription.status,
      active: entitled?(subscription),
      current_period_end: subscription.current_period_end
    }
  end

  def entitled?(%Subscription{plan: plan, status: "active"}) when plan in ~w(pro unlimited),
    do: true

  def entitled?(_), do: false

  def get_entitlement(user) do
    user
    |> get_subscription()
    |> subscription_payload()
  end

  def update_subscription(subscription, attrs) do
    subscription
    |> Subscription.changeset(attrs)
    |> Repo.update()
  end

  def verify_google_purchase(user, attrs) do
    with {:ok, result} <- GooglePlay.verify_subscription(attrs),
         {:ok, sub} <- get_or_create_subscription(user) do
      update_subscription(sub, %{
        google_package_name: result.package_name,
        google_product_id: result.product_id,
        google_purchase_token: result.purchase_token,
        google_order_id: result.order_id,
        plan: result.plan,
        status: result.status,
        current_period_end: result.current_period_end
      })
    end
  end

  def handle_stripe_event(%{"type" => "customer.subscription.created"} = event) do
    sync_subscription(event["data"]["object"])
  end

  def handle_stripe_event(%{"type" => "customer.subscription.updated"} = event) do
    sync_subscription(event["data"]["object"])
  end

  def handle_stripe_event(%{"type" => "customer.subscription.deleted"} = event) do
    sub_data = event["data"]["object"]

    case Repo.get_by(Subscription, stripe_subscription_id: sub_data["id"]) do
      nil -> :ok
      sub -> update_subscription(sub, %{status: "canceled"})
    end
  end

  def handle_stripe_event(_event), do: :ok

  defp sync_subscription(sub_data) do
    case Repo.get_by(Subscription, stripe_customer_id: sub_data["customer"]) do
      nil ->
        {:error, :subscription_not_found}

      sub ->
        update_subscription(sub, %{
          stripe_subscription_id: sub_data["id"],
          status: map_stripe_status(sub_data["status"]),
          current_period_end: DateTime.from_unix!(sub_data["current_period_end"])
        })
    end
  end

  defp map_stripe_status("active"), do: "active"
  defp map_stripe_status("past_due"), do: "past_due"
  defp map_stripe_status(_), do: "canceled"
end
