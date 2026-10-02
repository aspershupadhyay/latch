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
COPY --from=build /out/latch-gateway /usr/local/bin/latch-gateway
COPY --from=build --chown=65532:65532 /out/data /data
ENV LATCH_BIND=0.0.0.0:8787 \
    LATCH_DATA_DIR=/data \
    RUST_LOG=info
EXPOSE 8787
VOLUME ["/data"]
USER nonroot
ENTRYPOINT ["/usr/local/bin/latch-gateway"]
CMD ["serve"]
