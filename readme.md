# Azure Cloud Platform Deployment Pipeline

This repository contains the infrastructure automation pipeline for deploying Azure cloud resources using Terraform, with integrated security scanning and dynamic Azure resource management.

## Overview

This project provides a Jenkins-based CI/CD pipeline that automates the deployment of Azure infrastructure with built-in security scanning. The pipeline dynamically interacts with Azure subscriptions, storage accounts, and Terraform state management while ensuring code security through multiple scanning tools.

## Repository Structure


## Security Scanner Docker Image

### Purpose

The security scanner Docker image (`Docker-Images/security-scanner/Dockerfile`) is a lightweight Alpine-based container that bundles multiple industry-standard security scanning tools specifically designed for Infrastructure as Code (IaC) validation. This image is built and executed during the pipeline to identify security vulnerabilities, misconfigurations, and compliance issues in Terraform code before deployment.

### Installed Security Tools

The Docker image includes three complementary security scanning tools:

1. **TFLint** - A Terraform linter that enforces best practices and catches errors before runtime
   - Validates Terraform syntax and configuration
   - Checks for deprecated syntax and potential errors
   - Enforces naming conventions and style guidelines

2. **Trivy** (v0.49.1) - A comprehensive vulnerability scanner by Aqua Security
   - Scans Terraform plan files for security vulnerabilities
   - Detects misconfigurations in cloud resources
   - Identifies exposed secrets and sensitive data
   - Provides CVE detection for dependencies

3. **Checkov** - A static code analysis tool for IaC
   - Scans Terraform configurations against 1000+ built-in policies
   - Checks for CIS benchmark compliance
   - Identifies security and compliance violations
   - Validates cloud resource configurations against best practices

### Technical Details

- **Base Image**: Alpine Linux 3.23.3 (minimal footprint for fast builds)
- **Python Environment**: Isolated virtual environment at `/opt/venv`
- **Working Directory**: `/apps` (mounted from Jenkins workspace during execution)
- **Dependencies**: curl, bash, jq, Python 3, pip

## Jenkins Deployment Pipeline

### Overview

The `deploy-az-platform.groovy` pipeline orchestrates the complete lifecycle of Azure infrastructure deployment, from dynamic resource discovery to Terraform execution with integrated security scanning.

### Key Features

#### 1. Shared Library Integration
The pipeline leverages a shared Jenkins library for reusable functions:
- `pushToGithub()` - Commits and pushes generated tfvars files to the infrastructure repository

#### 2. Dynamic Azure Resource Discovery

The pipeline uses **Active Choice Parameters** and **Reactive Active Choice Parameters** to provide dynamic, real-time Azure resource selection:

- **Active Choice Parameter**: `SELECTED_SUBSCRIPTION`
  - Dynamically queries Azure CLI to list all available subscriptions in the tenant
  - Displays subscription names with their IDs for easy identification
  - Updates in real-time when the pipeline is triggered

- **Reactive Active Choice Parameter**: `storage_account`
  - Cascades from the selected subscription
  - Automatically queries and displays storage accounts within the chosen subscription
  - Updates dynamically when a different subscription is selected
  - Provides immediate feedback if no storage accounts exist

#### 3. Dynamic Terraform State Management

The pipeline automatically manages Terraform remote state in Azure Storage:

- **Container Creation**: Dynamically creates a blob container in the selected storage account
  - Container name is derived from the client name (lowercase, no spaces)
  - Ensures the container exists before Terraform initialization
  
- **State File Naming**: Uses a consistent naming convention
  - Format: `{client_name}-{environment}.terraform.tfstate`
  - Enables multi-environment and multi-client state isolation

#### 4. Dynamic tfvars File Generation

The pipeline generates client-specific Terraform variable files on-the-fly:

- **File Location**: `Azure/Environments/{environment}/clients/{client_name}.tfvars`
- **Generated Content**: Includes all user-provided parameters:
  - Client name, environment, project name
  - Azure location (region)
  - Network configuration (VNet and subnet CIDR blocks)
  - Storage account reference

- **GitHub Integration**: Automatically commits and pushes the generated tfvars file to the infrastructure repository using the shared library function

#### 5. Integrated Security Scanning

The pipeline builds and runs the custom security scanner container to validate Terraform code:

- **Build Process**: Clones the pipeline repository and builds the security scanner image locally
- **Scan Execution**: 
  - Generates a Terraform plan in JSON format
  - Mounts the workspace into the security scanner container
  - Executes all three security tools (TFLint, Trivy, Checkov) sequentially
  - Outputs scan results to the Jenkins console for review

- **Scan Scope**: Validates the Terraform plan before apply to catch issues early

### Pipeline Stages

1. **Checkout Infrastructure Repo** - Clones the Terraform infrastructure code
2. **Get Info** - Extracts subscription ID and configures Azure CLI context
3. **Terraform Init** - Initializes Terraform with dynamic backend configuration
4. **Create Client tfvars File** - Generates and commits client-specific variables
5. **Terraform Format** - Formats Terraform code for consistency
6. **Terraform Plan & Security Scan** - Generates plan and runs security scans
7. **Terraform Apply** - Deploys the infrastructure

### Pipeline Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `ENVIRONMENT` | Choice | Target environment (dev, test, prod) |
| `project_name` | String | Project identifier (default: global-admin) |
| `client_name` | String | Client name (used for resource naming) |
| `location` | String | Azure region (default: eastus) |
| `vnet_address` | String | Virtual network CIDR block |
| `subnet_address` | String | Subnet CIDR block |
| `SELECTED_SUBSCRIPTION` | Active Choice | Dynamically populated Azure subscription list |
| `storage_account` | Reactive Choice | Dynamically populated storage accounts from selected subscription |

### Prerequisites

- Jenkins with the following plugins:
  - Active Choices Plugin
  - Azure Credentials Plugin
  - Pipeline Plugin
- Azure CLI installed on Jenkins agent (`/usr/bin/az`)
- Terraform installed on Jenkins agent (`/usr/bin/terraform`)
- Docker installed on Jenkins agent
- Azure Service Principal credentials configured in Jenkins (credential ID: `AZ_CREDS`)
- GitHub Personal Access Token configured in Jenkins (credential ID: `GIT-PAT`)
- Access to shared Jenkins library repository

### Security Considerations

- Azure credentials are managed through Jenkins credentials store
- Service Principal authentication for Azure operations
- Terraform state stored securely in Azure Blob Storage with authentication
- All security scans must pass before deployment proceeds
- Generated tfvars files are version-controlled in GitHub for audit trail

## Usage

1. Trigger the Jenkins pipeline
2. Select the target environment (dev/test/prod)
3. Choose the Azure subscription from the dynamically populated list
4. Select the storage account for Terraform state
5. Provide client-specific parameters (name, network configuration)
6. Review the security scan results in the console output
7. Pipeline automatically applies the Terraform configuration if scans pass

## Contributing

When modifying the pipeline or security scanner:
- Test changes in a non-production environment first
- Ensure all security tools remain up-to-date
- Document any new parameters or configuration options
- Validate that dynamic parameter population still functions correctly

## License

[Add your license information here]
