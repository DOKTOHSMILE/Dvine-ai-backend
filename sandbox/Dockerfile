# The image each code run starts from (dvine.workspace.image, default dvine-sandbox:1).
# Build: docker build -t dvine-sandbox:1 sandbox/
FROM debian:bookworm-slim
RUN apt-get update \
 && apt-get install -y --no-install-recommends bash python3 python3-pip nodejs git curl ca-certificates coreutils \
 && rm -rf /var/lib/apt/lists/* \
 && useradd -u 1000 -m -d /work runner
USER 1000:1000
WORKDIR /work
