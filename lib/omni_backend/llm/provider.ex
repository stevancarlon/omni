defmodule OmniBackend.LLM.Provider do
  @moduledoc "Behaviour for LLM provider adapters."

  @callback completions(model :: String.t(), system :: String.t(), messages :: list(map())) ::
              {:ok, String.t()} | {:error, String.t()}
end
