defmodule OmniBackend.Billing.GooglePlay do
  @moduledoc "Verifies Android subscription purchases with Google Play Developer API."

  @scope "https://www.googleapis.com/auth/androidpublisher"
  @default_token_uri "https://oauth2.googleapis.com/token"

  def verify_subscription(%{
        "product_id" => product_id,
        "purchase_token" => purchase_token
      }) do
    package_name = Application.get_env(:omni_backend, :google_play_package_name)

    with :ok <- require_config(package_name, purchase_token),
         {:ok, access_token} <- access_token(),
         {:ok, response} <- fetch_purchase(access_token, package_name, product_id, purchase_token) do
      {:ok, build_result(package_name, product_id, purchase_token, response)}
    end
  end

  def verify_subscription(_), do: {:error, :missing_purchase_fields}

  def plan_for_product(product_id) do
    cond do
      product_id == Application.get_env(:omni_backend, :google_play_pro_product_id) ->
        "pro"

      product_id == Application.get_env(:omni_backend, :google_play_unlimited_product_id) ->
        "unlimited"

      true ->
        "free"
    end
  end

  defp require_config(package_name, purchase_token) do
    cond do
      is_nil(package_name) or package_name == "" -> {:error, :google_play_package_not_configured}
      is_nil(purchase_token) or purchase_token == "" -> {:error, :missing_purchase_token}
      true -> :ok
    end
  end

  defp access_token do
    with {:ok, service_account} <- service_account(),
         {:ok, assertion} <- service_account_jwt(service_account) do
      token_uri = service_account["token_uri"] || @default_token_uri

      case Req.post(token_uri,
             form: [
               grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
               assertion: assertion
             ]
           ) do
        {:ok, %Req.Response{status: 200, body: %{"access_token" => token}}} ->
          {:ok, token}

        {:ok, %Req.Response{status: status, body: body}} ->
          {:error, {:google_oauth_error, status, body}}

        {:error, reason} ->
          {:error, {:google_oauth_unreachable, reason}}
      end
    end
  end

  defp service_account do
    case Application.get_env(:omni_backend, :google_play_service_account_json) do
      nil -> {:error, :google_play_service_account_not_configured}
      "" -> {:error, :google_play_service_account_not_configured}
      json -> Jason.decode(json)
    end
  end

  defp service_account_jwt(
         %{"client_email" => email, "private_key" => private_key} = service_account
       ) do
    token_uri = service_account["token_uri"] || @default_token_uri
    now = System.system_time(:second)

    header = %{"alg" => "RS256", "typ" => "JWT"}

    claims = %{
      "iss" => email,
      "scope" => @scope,
      "aud" => token_uri,
      "iat" => now,
      "exp" => now + 3600
    }

    signing_input = "#{b64(header)}.#{b64(claims)}"

    with [pem_entry] <- :public_key.pem_decode(private_key),
         private_key <- :public_key.pem_entry_decode(pem_entry) do
      signature =
        :public_key.sign(signing_input, :sha256, private_key)
        |> Base.url_encode64(padding: false)

      {:ok, "#{signing_input}.#{signature}"}
    else
      _ -> {:error, :invalid_google_private_key}
    end
  end

  defp service_account_jwt(_), do: {:error, :invalid_google_service_account}

  defp fetch_purchase(access_token, package_name, product_id, purchase_token) do
    url =
      "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/" <>
        "#{URI.encode(package_name)}/purchases/subscriptions/#{URI.encode(product_id)}/tokens/#{URI.encode(purchase_token)}"

    case Req.get(url, headers: [{"authorization", "Bearer #{access_token}"}]) do
      {:ok, %Req.Response{status: 200, body: body}} ->
        {:ok, body}

      {:ok, %Req.Response{status: status, body: body}} ->
        {:error, {:google_play_error, status, body}}

      {:error, reason} ->
        {:error, {:google_play_unreachable, reason}}
    end
  end

  defp build_result(package_name, product_id, purchase_token, response) do
    expiry_ms = parse_int(response["expiryTimeMillis"])

    %{
      package_name: package_name,
      product_id: product_id,
      purchase_token: purchase_token,
      order_id: response["orderId"],
      plan: plan_for_product(product_id),
      status: purchase_status(response, expiry_ms),
      current_period_end: expiry_ms && DateTime.from_unix!(expiry_ms, :millisecond)
    }
  end

  defp purchase_status(response, expiry_ms) do
    now_ms = System.system_time(:millisecond)

    cond do
      response["cancelReason"] in [1, 2, 3] -> "canceled"
      is_integer(expiry_ms) and expiry_ms > now_ms -> "active"
      true -> "canceled"
    end
  end

  defp parse_int(nil), do: nil
  defp parse_int(value) when is_integer(value), do: value
  defp parse_int(value) when is_binary(value), do: String.to_integer(value)

  defp b64(payload) do
    payload
    |> Jason.encode!()
    |> Base.url_encode64(padding: false)
  end
end
