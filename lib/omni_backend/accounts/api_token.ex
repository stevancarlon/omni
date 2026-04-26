defmodule OmniBackend.Accounts.ApiToken do
  use Ecto.Schema
  import Ecto.Changeset

  @primary_key {:id, :binary_id, autogenerate: true}
  @foreign_key_type :binary_id

  schema "api_tokens" do
    field :token, :string
    field :device_name, :string
    field :last_used_at, :utc_datetime
    field :expires_at, :utc_datetime
    field :revoked_at, :utc_datetime

    belongs_to :user, OmniBackend.Accounts.User

    timestamps(type: :utc_datetime)
  end

  def changeset(api_token, attrs) do
    api_token
    |> cast(attrs, [:device_name, :user_id])
    |> validate_required([:user_id])
    |> put_token()
    |> put_expiry()
  end

  defp put_token(changeset) do
    put_change(changeset, :token, :crypto.strong_rand_bytes(32) |> Base.url_encode64(padding: false))
  end

  defp put_expiry(changeset) do
    put_change(changeset, :expires_at, DateTime.add(DateTime.utc_now(), 90, :day) |> DateTime.truncate(:second))
  end
end
