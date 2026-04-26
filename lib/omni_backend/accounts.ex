defmodule OmniBackend.Accounts do
  import Ecto.Query
  alias OmniBackend.Repo
  alias OmniBackend.Accounts.{User, ApiToken}

  def register_user(attrs) do
    %User{}
    |> User.registration_changeset(attrs)
    |> Repo.insert()
  end

  def authenticate(email, password) do
    user = Repo.get_by(User, email: String.downcase(email))

    cond do
      user && Bcrypt.verify_pass(password, user.password_hash) -> {:ok, user}
      user -> {:error, :invalid_password}
      true -> Bcrypt.no_user_verify(); {:error, :not_found}
    end
  end

  def create_api_token(user, device_name \\ nil) do
    %ApiToken{}
    |> ApiToken.changeset(%{user_id: user.id, device_name: device_name})
    |> Repo.insert()
  end

  def verify_api_token(token_string) do
    now = DateTime.utc_now()

    query =
      from t in ApiToken,
        where: t.token == ^token_string,
        where: is_nil(t.revoked_at),
        where: t.expires_at > ^now,
        preload: [:user]

    case Repo.one(query) do
      nil ->
        {:error, :invalid_token}

      token ->
        token
        |> Ecto.Changeset.change(last_used_at: DateTime.truncate(now, :second))
        |> Repo.update()

        {:ok, token.user}
    end
  end

  def revoke_api_token(token_string, user) do
    query =
      from t in ApiToken,
        where: t.token == ^token_string,
        where: t.user_id == ^user.id,
        where: is_nil(t.revoked_at)

    case Repo.one(query) do
      nil -> {:error, :not_found}
      token ->
        token
        |> Ecto.Changeset.change(revoked_at: DateTime.truncate(DateTime.utc_now(), :second))
        |> Repo.update()
    end
  end

  def get_user!(id), do: Repo.get!(User, id)
end
