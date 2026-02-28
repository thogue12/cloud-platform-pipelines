import groovy.json.JsonSlurper
@Library('shared-library') _

properties([
    parameters([
        string(name: 'ENVIRONMENT', defaultValue: 'dev', description: 'Target environment'),
        string(name: 'project_name', defaultValue: 'global-admin', description: 'project name'),
        string(name: 'client_name', description: 'name of the client'),
        string(name: 'location', defaultValue: 'eastus'),
        string(name: 'vnet_address', description: 'Input the virtual networks CIDR'),
        string(name: 'subnet_address',  description: 'Input the subnet CIDR'),
        
        [
            $class: 'ChoiceParameter', 
            name: 'SELECTED_SUBSCRIPTION',
            choiceType: 'PT_SINGLE_SELECT',
            description: 'Select your client subscription',
            script: [
                $class: 'GroovyScript',
                fallbackScript: [sandbox: false, script: 'return ["UI Fallback triggered - Check Script Approval"]'],
                script: [
                    sandbox: false, 
                    script: '''
                        try {
                            def cmd = ['/bin/bash', '-c', '/usr/bin/az account list --query "[].{name:name, id:id}" --output json']
                            def process = cmd.execute()
                            def output = process.text
                            process.waitFor()
                            if (process.exitValue() == 0 && output) {
                                def data = new groovy.json.JsonSlurper().parseText(output)
                                return data.collect { sub -> "${sub.name} (${sub.id})" }
                            }
                        } catch (Exception e) { return ["Error: ${e.message}"] }
                    '''
                ]
            ]
        ],

        [
            $class: 'CascadeChoiceParameter', 
            name: 'storage_account',
            description: 'Select a storage account from the subscription',
            choiceType: 'PT_SINGLE_SELECT', 
            referencedParameters: 'SELECTED_SUBSCRIPTION',
            script: [
                $class: 'GroovyScript',
                fallbackScript: [
                    sandbox: false,
                    script: 'return ["Error: There are no accounts in this Subscription or Select Subscription first"]'
                ],
                script: [
                    sandbox: false,
                    script: '''
                        try {
                             if (SELECTED_SUBSCRIPTION == null || SELECTED_SUBSCRIPTION.trim().isEmpty()) {
                                 return ["Select a Subscription first..."]
                             }
                             def subId = SELECTED_SUBSCRIPTION.contains("(") ? 
                                         SELECTED_SUBSCRIPTION.substring(SELECTED_SUBSCRIPTION.lastIndexOf("(") + 1, SELECTED_SUBSCRIPTION.lastIndexOf(")")) : 
                                         SELECTED_SUBSCRIPTION
                             def command = "/usr/bin/az account set --subscription ${subId} && /usr/bin/az storage account list --query '[].name' --output json 2>&1"
                             def proc = ["/bin/bash", "-c", command].execute()
                             def output = proc.text.trim()
                             proc.waitFor()
                             if (proc.exitValue() != 0) {
                                 return ["AZ CLI Error: " + output.take(50)]
                             }
                             if (!output || output == "[]") {
                                 return ["ERROR: No storage accounts found in this sub"]
                             }
                             def data = new groovy.json.JsonSlurper().parseText(output)
                             return data
                         } catch (Exception e) {
                             return ["GROOVY ERROR: " + e.getMessage()]
                         }
                    '''
                ]
            ]
        ]
    ])
])

pipeline {
    agent any
    
    environment {
        TF_PATH = '/usr/bin/terraform'
        CLIENT_LOWER = "${params.client_name.toLowerCase().replaceAll(' ', '')}"
    }

    stages {
        stage('Checkout Infrastructure repo') {
            steps {
                git branch: 'main', 
                    url: 'https://github.com/thogue12/cloud-infrastructure.git'
            }
        }

        stage('Get info') {
            steps {
                script {
                    def subRaw = params.SELECTED_SUBSCRIPTION ?: ""
                    env.SUB_ID = subRaw.contains("(") ? 
                             subRaw.substring(subRaw.lastIndexOf("(") + 1, subRaw.lastIndexOf(")")) : 
                             subRaw
                }
                
                withCredentials([azureServicePrincipal('AZ_CREDS')]) {
                    sh '''
                        az account set --subscription ${SUB_ID}
                        echo "Configured for Subscription: ${SUB_ID}"
                    '''
                }
            }
        }

        stage('terraform init') {
            steps {
                withCredentials([azureServicePrincipal('AZ_CREDS')]) {
                    sh '''#!/bin/bash
                        # 1. Ensure the Blob Container exists for this client
                        echo "Checking if container ${CLIENT_LOWER} exists..."
                        az storage container create \
                            --name "${CLIENT_LOWER}" \
                            --account-name "${storage_account}" \
                            --auth-mode login \
                            --subscription "${SUB_ID}" || echo "Container might already exist."
                     '''
                    sh '''#!/bin/bash
                            ${TF_PATH} init -reconfigure \
                            -backend-config="storage_account_name=${storage_account}" \
                            -backend-config="container_name=${CLIENT_LOWER}" \
                            -backend-config="key=${CLIENT_LOWER}-${ENVIRONMENT}.terraform.tfstate" \
                            -backend-config="subscription_id=${SUB_ID}" \
                            -backend-config="client_id=${AZURE_CLIENT_ID}" \
                            -backend-config="client_secret=${AZURE_CLIENT_SECRET}" \
                            -backend-config="tenant_id=${AZURE_TENANT_ID}"
                    '''
                }
            }
        }

        stage('Create clients .tfvars file'){
            steps{
                script {
                    def targetDir = "Azure/Environments/${params.ENVIRONMENT}/clients"
                    def fileName  = "${params.client_name}.tfvars"
                    def fullPath  = "${targetDir}/${fileName}"

                    sh "mkdir -p ${targetDir}"
                
                    def tfvarsContent = """
                    client_name     = "${params.client_name}"
                    environment     = "${params.ENVIRONMENT}"
                    project_name    = "${params.project_name}"
                    location        = "${params.location}"
                    vnet_address    = "${params.vnet_address}"
                    subnet_address  = "${params.subnet_address}"
                    storage_account = "${params.storage_account}"
                    """.stripIndent()

                    writeFile file: fullPath, text: tfvarsContent

                    pushToGithub(
                        file:  fullPath,
                        creds: 'GIT-PAT',
                        repo:  'github.com/thogue12/cloud-infrastructure.git'
                    )
                }
            }
        }

        stage('terraform format') {
            steps { 
                sh 'terraform fmt'
            }
        }

        stage('terraform plan & security scan') {
            steps {
                dir('pipeline-repo') {
                    git branch: 'main', 
                        url: 'https://github.com/thogue12/cloud-platform-pipelines.git'
                }
        
                sh 'docker build -t security-scanner:local ./pipeline-repo/Docker-Images/security-scanner'
        
                withEnv([
                    "TF_VAR_client_name=${params.client_name}",
                    "TF_VAR_environment=${params.ENVIRONMENT}",
                    "TF_VAR_project_name=${params.project_name}",
                    "TF_VAR_vnet_address=[\"${params.vnet_address}\"]",
                    "TF_VAR_subnet_address=[\"${params.subnet_address}\"]",
                    "TF_VAR_location=${params.location}",
                    "TF_VAR_azure_subscription_id=${env.SUB_ID}"
                ]) { 
                    withCredentials([azureServicePrincipal('AZ_CREDS')]) {
                        sh """
                            export ARM_CLIENT_ID="\$AZURE_CLIENT_ID"
                            export ARM_CLIENT_SECRET="\$AZURE_CLIENT_SECRET"
                            export ARM_TENANT_ID="\$AZURE_TENANT_ID"
                            export ARM_SUBSCRIPTION_ID="\$AZURE_SUBSCRIPTION_ID"
        
                            ${TF_PATH} plan -out=tfplan
                            ${TF_PATH} show -json tfplan > tfplan.json
                        """
        
                        sh '''
                            echo "--- Creating Scan Script ---"
                            echo "#!/bin/sh" > scan.sh
                            echo "echo '--- Running tfsec ---'" >> scan.sh
                            echo "tfsec ." >> scan.sh
        
                            echo "echo '--- Running checkov ---'" >> scan.sh
                            echo "checkov -f tfplan.json" >> scan.sh
                            
                            echo "echo '--- Running trivy ---'" >> scan.sh
                            echo "trivy config tfplan.json" >> scan.sh
                            
                            chmod +x scan.sh
                        
                            echo "--- Starting Security Scan ---"
                            docker run --rm \
                                -v "$(pwd):/apps" \
                                --workdir /apps \
                                security-scanner:local \
                                ./scan.sh
                        '''
                    } 
                } 
            }
        }
        // stage('terraform apply') {
        //     steps {
        //         withEnv(["TF_VAR_client_name=${params.client_name}",
        //                 "TF_VAR_environment=${params.ENVIRONMENT}",
        //                 "TF_VAR_project_name=${params.project_name}",
        //                 "TF_VAR_vnet_address=${params.vnet_address}",
        //                 "TF_VAR_subnet_address=${params.subnet_address}",
        //                 "TF_VAR_location=${params.location}"
        //         ]) {
        //             withCredentials([azureServicePrincipal('AZ_CREDS')]) {
        //                 sh '''
        //                     export ARM_CLIENT_ID="${AZURE_CLIENT_ID}"
        //                     export ARM_CLIENT_SECRET="${AZURE_CLIENT_SECRET}"
        //                     export ARM_TENANT_ID="${AZURE_TENANT_ID}"
        //                     export ARM_SUBSCRIPTION_ID="${SUB_ID}"
        //                     ${TF_PATH} apply -auto-approve tfplan
        //                 '''
        //             }
        //         }
        //     }
        // }
    } 
}