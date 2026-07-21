env_path = Path.expand("../.env", __DIR__)

if File.exists?(env_path) do
  env_path
  |> File.stream!()
  |> Stream.map(&String.trim/1)
  |> Enum.each(fn
    "" ->
      :ok

    "#" <> _comment ->
      :ok

    line ->
      line = String.replace_prefix(line, "export ", "")

      case String.split(line, "=", parts: 2) do
        [key, value] ->
          value =
            value
            |> String.trim()
            |> String.trim("\"")
            |> String.trim("'")

          if System.get_env(key) == nil do
            System.put_env(key, value)
          end

        _ ->
          :ok
      end
  end)
end
