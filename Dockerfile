# Latch gateway container. Build: docker build -t latch-gateway .
# Run:   docker run -p 8787:8787 -v latch-data:/data \
#          -e LATCH_ADMIN_TOKEN=... -e LATCH_MCP_TOKEN=... -e LATCH_PUBLIC_URL=https://... latch-gateway
FROM rust:1.97-slim-bookworm AS build
WORKDIR /src
COPY Cargo.toml Cargo.lock ./
COPY crates crates
COPY servers servers
COPY packages packages
RUN cargo build --release --locked -p latch-gateway \
 && mkdir -p /out/data \
 && cp target/release/latch-gateway /out/

FROM gcr.io/distroless/cc-debian12:nonroot
LABEL org.opencontainers.image.source="https://github.com/aspershupadhyay/latch" \
      org.opencontainers.image.description="Latch gateway: lets any MCP app use your Android phone, on your terms" \
      org.opencontainers.image.licenses="AGPL-3.0-only"
COPY --from=build /out/latch-gateway /usr/local/bin/latch-gateway
COPY --from=build --chown=65532:65532 /out/data /data
# PORT, not LATCH_BIND: hosts that assign a port (Cloud Run, Render, Railway)
# overwrite PORT, and the gateway listens on 0.0.0.0:$PORT.
ENV PORT=8787 \
    LATCH_DATA_DIR=/data \
    RUST_LOG=info
EXPOSE 8787
VOLUME ["/data"]
USER nonroot
ENTRYPOINT ["/usr/local/bin/latch-gateway"]
CMD ["serve"]
