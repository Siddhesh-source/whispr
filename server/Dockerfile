# syntax=docker/dockerfile:1.7

# ---- libsignal FFI (official Signal code; the only crypto the server links) ----
FROM rust:1.98.1-bookworm AS libsignal
ARG LIBSIGNAL_VERSION=v0.104.0
RUN apt-get update && apt-get install -y --no-install-recommends \
        clang libclang-dev cmake protobuf-compiler libprotobuf-dev git \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
RUN git clone --depth 1 --branch "${LIBSIGNAL_VERSION}" https://github.com/signalapp/libsignal.git .
RUN --mount=type=cache,target=/usr/local/cargo/registry \
    --mount=type=cache,target=/src/target,id=libsignal-target \
    cargo build --release --locked -p libsignal-ffi \
    && mkdir -p /out/lib /out/include \
    && cp target/release/libsignal_ffi.a /out/lib/ \
    && cp swift/Sources/SignalFfi/signal_ffi.h /out/include/

# ---- Go build ----
FROM golang:1.27-bookworm AS build
COPY --from=libsignal /out /opt/libsignal
ENV CGO_ENABLED=1 \
    CGO_CFLAGS="-I/opt/libsignal/include" \
    CGO_LDFLAGS="-L/opt/libsignal/lib"
WORKDIR /app
COPY go.mod go.sum ./
RUN --mount=type=cache,target=/go/pkg/mod go mod download
COPY . .

# `docker build --target test .` runs the suite including real-libsignal tests.
FROM build AS test
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    go test -tags libsignal ./...

FROM build AS compile
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    go build -tags libsignal -trimpath -ldflags="-s -w" -o /out/whisprd ./cmd/whisprd

# ---- Runtime ----
FROM gcr.io/distroless/cc-debian12:nonroot
COPY --from=compile /out/whisprd /whisprd
USER nonroot:nonroot
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD ["/whisprd", "healthcheck"]
ENTRYPOINT ["/whisprd"]
