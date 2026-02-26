# Use a specific version of Alpine for stability
FROM alpine:latest

# 1. Install System Dependencies 
RUN apk add --no-cache \
    ca-certificates \
    curl \
    bash \
    python3 \
    py3-pip \
    jq

# Create a virtual env
RUN python3 -m venv /opt/venv

# Ensure the venv is used for all subsequent commands
ENV PATH="/opt/venv/bin:$PATH"
# 2. Install tfsec
RUN curl -sSLo /usr/local/bin/tfsec https://github.com/aquasecurity/tfsec/releases/latest/download/tfsec-linux-amd64 && \
    chmod +x /usr/local/bin/tfsec

# 3. Install Checkov
RUN pip3 install --no-cache-dir --upgrade pip && \
    pip3 install --no-cache-dir checkov

# 4. Install Trivy
RUN curl -sfL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh | sh -s -- -b /usr/local/bin v0.49.1

# 5. Set up a Workspace
WORKDIR /apps

# 6. Verify Installations
RUN tfsec --version && checkov --version && trivy --version

ENTRYPOINT ["/bin/bash"]