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
                fallbackScript: [sandbox: false, script: 'return ["Error"]'],
                script: [
                    sandbox: false, 
                    script: "return vars.getSubscriptions()"
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
                    script: "return vars.getStorageAccounts.call(SELECTED_SUBSCRIPTION)"
                ]
            ]
        ]
    ])
])

pipeline {
    agent any
    
    environment {
        CLIENT_LOWER = "${params.client_name.toLowerCase().replaceAll(' ', '')}"
    }

    stages {
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
                    sh '''
                        terraform init \
                            -backend-config="storage_account_name=${params.storage_account}" \
                            -backend-config="container_name=${CLIENT_LOWER}" \
                            -backend-config="key=${CLIENT_LOWER}-${params.ENVIRONMENT}.terraform.tfstate" \
                            -backend-config="subscription_id=${SUB_ID}" \
                            -backend-config="client_id=${AZURE_CLIENT_ID}" \
                            -backend-config="client_secret=${AZURE_CLIENT_SECRET}" \
                            -backend-config="tenant_id=${AZURE_TENANT_ID}" \
                            -reconfigure
                    '''
                    // pull security-scanner image from dockerhub
                    sh '''
                        docker run --rm -v ${WORKSPACE}:/apps \
                        thogue12/security-scanner:v2 \
                        bash -c "tfsec . && checkov -f tfplan.json && trivy conf tfplan.json"
                    '''
                }
            }
        }

        stage('terraform fmt & security') {
            steps { 
                // Pull tfsec docker image
                
                    sh 'terraform fmt'
                    
                  
            }
        }

        stage('terraform plan') {
            steps {
                withCredentials([azureServicePrincipal('AZ_CREDS')]) {
                    sh '''
                        export ARM_CLIENT_ID="${AZURE_CLIENT_ID}"
                        export ARM_CLIENT_SECRET="${AZURE_CLIENT_SECRET}"
                        export ARM_TENANT_ID="${AZURE_TENANT_ID}"
                        export ARM_SUBSCRIPTION_ID="${SUB_ID}"
                        terraform plan -out=tfplan
                        terraform show -json tfplan > tfplan.json
                    '''
                }
            }
        }

        stage('terraform apply') {
            steps {
                withCredentials([azureServicePrincipal('AZ_CREDS')]) {
                    sh '''
                        export ARM_CLIENT_ID="${AZURE_CLIENT_ID}"
                        export ARM_CLIENT_SECRET="${AZURE_CLIENT_SECRET}"
                        export ARM_TENANT_ID="${AZURE_TENANT_ID}"
                        export ARM_SUBSCRIPTION_ID="${SUB_ID}"
                        terraform apply -auto-approve tfplan
                    '''
                }
            }
        }

        stage('Create clients .tfvars file'){
            steps{
                script {
                    def targetDir = "Environments/${params.ENVIRONMENT}/clients"
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

                    writeFile file: "${targetDir}/${params.client_name}.tfvars", text: tfvarsContent
                    echo "Successfully created .tfvars file in ${targetDir}"
                }
            }
        }
    }
}