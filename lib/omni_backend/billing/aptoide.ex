defmodule OmniBackend.Billing.Aptoide do
  @moduledoc "Verifies Android in-app product purchases with Aptoide Connect."
  require Logger

  @api_base "https://api.catappult.io/productv2/8.20250505/google/inapp/v3/applications"
  @access_days 31

  def verify_purchase(%{
        "product_id" => product_id,
        "purchase_token" => purchase_token
      }) do
    package_name = Application.get_env(:omni_backend, :aptoide_package_name)

    Logger.info(
      "aptoide_verify_start #{inspect(%{package_name: package_name, product_id: product_id})}"
    )

    with :ok <- require_config(package_name, purchase_token),
         {:ok, response} <- fetch_purchase(package_name, product_id, purchase_token) do
      Logger.info(
        "aptoide_verify_success #{inspect(%{package_name: package_name, product_id: product_id})}"
      )

      {:ok, build_result(package_name, product_id, purchase_token, response)}
    else
      {:error, reason} ->
        Logger.error(
          "aptoide_verify_failed #{inspect(%{package_name: package_name, product_id: product_id, reason: sanitize_reason(reason)})}"
        )

        {:error, reason}
    end
  end

  def verify_purchase(_), do: {:error, :missing_purchase_fields}

  def plan_for_product(product_id) do
    cond do
      product_id == Application.get_env(:omni_backend, :aptoide_pro_product_id) ->
        "pro"

      product_id == Application.get_env(:omni_backend, :aptoide_unlimited_product_id) ->
        "unlimited"

      true ->
        "free"
    end
  end

  defp require_config(package_name, purchase_token) do
    cond do
      is_nil(package_name) or package_name == "" -> {:error, :aptoide_package_not_configured}
      is_nil(purchase_token) or purchase_token == "" -> {:error, :missing_purchase_token}
      true -> :ok
    end
  end

  defp fetch_purchase(package_name, product_id, purchase_token) do
    url = purchase_url(package_name, product_id, purchase_token)

    case Req.get(url) do
      {:ok, %Req.Response{status: 200, body: body}} ->
        {:ok, body}

      {:ok, %Req.Response{status: status, body: body}} ->
        {:error, {:aptoide_error, status, body}}

      {:error, reason} ->
        {:error, {:aptoide_unreachable, reason}}
    end
  end

  defp purchase_url(package_name, product_id, purchase_token) do
    @api_base <>
      "/#{URI.encode(package_name)}/purchases/products/#{URI.encode(product_id)}/tokens/#{URI.encode(purchase_token)}"
  end

  defp sanitize_reason({:aptoide_error, status, body}), do: {:aptoide_error, status, body}

  defp sanitize_reason({:aptoide_unreachable, reason}) do
    {:aptoide_unreachable, Exception.message(reason)}
  rescue
    _ -> {:aptoide_unreachable, inspect(reason)}
  end

  defp sanitize_reason(reason), do: reason

  defp build_result(package_name, product_id, purchase_token, response) do
    purchase_time_ms = parse_int(response["purchaseTimeMillis"] || response["purchaseTime"])

    %{
      package_name: package_name,
      product_id: product_id,
      purchase_token: purchase_token,
      order_id: response["orderId"],
      plan: plan_for_product(product_id),
      status: purchase_status(response),
      purchased_at: purchase_time_ms && DateTime.from_unix!(purchase_time_ms, :millisecond),
      current_period_end:
        DateTime.utc_now()
        |> DateTime.add(@access_days, :day)
        |> DateTime.truncate(:second)
    }
  end

  defp purchase_status(response) do
    cond do
      response["paymentState"] == 0 -> "past_due"
      response["purchaseState"] in [1, "1"] -> "canceled"
      true -> "active"
    end
  end

  defp parse_int(nil), do: nil
  defp parse_int(value) when is_integer(value), do: value
  defp parse_int(value) when is_binary(value), do: String.to_integer(value)
end
