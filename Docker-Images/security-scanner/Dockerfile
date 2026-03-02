# Use a specific version of Alpine for stability
FROM alpine:3.23.3
FROM aquasec/trivy:0.49.1 AS trivy_source
FROM ghcr.io/terraform-linters/tflint:v0.50.1 AS tflint_source

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

# Create a virtual env
RUN python3 -m venv /opt/venv

# Ensure the venv is used for all subsequent commands
ENV PATH="/opt/venv/bin:$PATH"
#Install tflint
RUN curl -s https://raw.githubusercontent.com/terraform-linters/tflint/master/install_linux.sh | bash

#Install Trivy
COPY --from=trivy_source /usr/local/bin/trivy /usr/local/bin/trivy
COPY --from=tflint_source /usr/local/bin/tflint /usr/local/bin/tflint

#Install Checkov
RUN pip3 install --no-cache-dir --upgrade pip && \
    pip3 install --no-cache-dir checkov

#Set up a Workspace
WORKDIR /apps

#Verify Installations
RUN tflint --version && trivy --version && checkov --version

ENTRYPOINT ["/bin/bash"]