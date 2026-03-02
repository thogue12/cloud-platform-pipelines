# 1. Pull the tools (The "Suppliers")
FROM aquasec/trivy:0.49.1 AS trivy_source
FROM ghcr.io/terraform-linters/tflint:v0.50.1 AS tflint_source

# 2. Start your actual environment (The "Workbench")
FROM alpine:3.23.3

# Install System Dependencies 
RUN apk add --no-cache \
    ca-certificates \
    curl \
    bash \
    python3 \
    py3-pip \
    jq \
    tar \
    gzip \
    git

# Create a virtual env for Checkov
RUN python3 -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

# 3. Copy the binaries from the "Suppliers"
COPY --from=trivy_source /usr/local/bin/trivy /usr/local/bin/trivy
COPY --from=tflint_source /usr/local/bin/tflint /usr/local/bin/tflint

# 4. Install Checkov
RUN pip3 install --no-cache-dir --upgrade pip && \
    pip3 install --no-cache-dir checkov

WORKDIR /apps

# Verify Installations
RUN tflint --version && trivy --version && checkov --version

ENTRYPOINT ["/bin/bash"]