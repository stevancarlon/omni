# --- Build stage ---
FROM hexpm/elixir:1.18.3-erlang-27.0-debian-bookworm-20260421 AS build

RUN apt-get update -y && apt-get install -y build-essential git && apt-get clean

WORKDIR /app

ENV MIX_ENV=prod

RUN mix local.hex --force && mix local.rebar --force

COPY mix.exs mix.lock ./
RUN mix deps.get --only prod
RUN mix deps.compile

COPY config config
COPY lib lib
COPY priv priv

RUN mix compile
RUN mix release

# --- Runtime stage ---
FROM debian:bookworm-slim

RUN apt-get update -y && \
    apt-get install -y libstdc++6 openssl libncurses5 locales ca-certificates && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

RUN sed -i '/en_US.UTF-8/s/^# //g' /etc/locale.gen && locale-gen
ENV LANG=en_US.UTF-8

WORKDIR /app

COPY --from=build /app/_build/prod/rel/omni_backend ./

ENV PHX_SERVER=true

CMD bin/omni_backend eval "OmniBackend.Release.migrate()" && bin/omni_backend start
