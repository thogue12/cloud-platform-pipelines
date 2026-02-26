import groovy.json.JsonSlurper
@Library('shared-library') _

// Define the logic as Closures at the top of the file
def getSubscriptions(){'''
import groovy.json.JsonSlurper
    try {
        def process = ['/usr/bin/az', 'account', 'list', '--query', '[].{name:name, id:id}', '--output', 'json'].execute()
        def out = new StringBuilder(), err = new StringBuilder()
        process.waitForProcessOutput(out, err)
        if (process.exitValue() == 0) {
            def data = new JsonSlurper().parseText(out.toString())
            return data.collect { "${it.name} (${it.id})" }
        }
        return ["Error: CLI Failed"]
    } catch (e) { return ["Error: ${e.message}"] }
    '''
}



// Define this at the very top of your Jenkinsfile, before the 'properties' block
def getStorageAccounts(selectedSub){ '''
import groovy.json.JsonSlurper
    try {
        // 1. Check if the parent parameter is empty or null
        if (selectedSub == null || selectedSub.trim().isEmpty() || selectedSub.contains("Error")) {
            return ["Select a Subscription first..."]
        }

        // 2. Extract Sub ID from "Name (ID)" format
        def subId = selectedSub.contains("(") ? 
                    selectedSub.substring(selectedSub.lastIndexOf("(") + 1, selectedSub.lastIndexOf(")")) : 
                    selectedSub

        // 3. Execute Command
        // We use absolute paths to /usr/bin/az to ensure the Jenkins Controller finds it
        def command = "/usr/bin/az account set --subscription ${subId} && /usr/bin/az storage account list --query '[].name' --output json 2>&1"
        def proc = ["/bin/bash", "-c", command].execute()
        
        def output = proc.text.trim()
        proc.waitFor()

        // 4. Handle CLI Errors (Exit code != 0)
        if (proc.exitValue() != 0) {
            return ["AZ CLI Error: " + output.take(50)] 
        }

        // 5. Handle Empty Results
        if (!output || output == "[]") {
            return ["No storage accounts found in this sub"]
        }

        // 6. Parse JSON and Return List
        return new JsonSlurper().parseText(output)
                         
    } catch (Exception e) {
        return ["GROOVY ERROR: " + e.getMessage().take(50)]
    }'''
}

// Then in your properties block, you call it like this:
// script: "return getStorageAccounts(SELECTED_SUBSCRIPTION)"


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
                    script: getSubscriptions()
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
                    script: getStorageAccounts(selectedSub)
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