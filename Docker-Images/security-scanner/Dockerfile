# Use a specific version of Alpine for stability
FROM alpine:3.23.3

# Install System Dependencies 
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
#Install tflint
RUN curl -s https://raw.githubusercontent.com/terraform-linters/tflint/master/install_linux.sh | bash

#Install Trivy
RUN curl -sfL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh | sh -s -- -b /usr/local/bin v0.49.1

#Install Checkov
RUN pip3 install --no-cache-dir --upgrade pip && \
    pip3 install --no-cache-dir checkov

#Set up a Workspace
WORKDIR /apps

#Verify Installations
RUN tflint --version && trivy --version && checkov --version

ENTRYPOINT ["/bin/bash"]

