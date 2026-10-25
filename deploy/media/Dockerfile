# Emulated rekordbox USB medium for local emulation only.
# prolink-cli is GPL-3.0-only; this image is NOT published by CI.
FROM rust:1.99-slim-bookworm AS build
RUN apt-get update \
 && apt-get install -y --no-install-recommends pkg-config build-essential \
 && rm -rf /var/lib/apt/lists/*
RUN cargo install prolink-cli --version 0.5.0 --locked

FROM debian:bookworm-slim
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates \
 && rm -rf /var/lib/apt/lists/*
COPY --from=build /usr/local/cargo/bin/prolink /usr/local/bin/prolink
ENTRYPOINT ["prolink"]
