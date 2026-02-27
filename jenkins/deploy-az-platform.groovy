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
                             // 1. Check if the parent parameter is empty or null
                             if (SELECTED_SUBSCRIPTION == null || SELECTED_SUBSCRIPTION.trim().isEmpty()) {
                                 return ["Select a Subscription first..."]
                             }
            
                             // 2. Extract Sub ID
                             def subId = SELECTED_SUBSCRIPTION.contains("(") ? 
                                         SELECTED_SUBSCRIPTION.substring(SELECTED_SUBSCRIPTION.lastIndexOf("(") + 1, SELECTED_SUBSCRIPTION.lastIndexOf(")")) : 
                                         SELECTED_SUBSCRIPTION
            
                             // 3. Execute Command (Capturing stderr with 2>&1)
                             def command = "/usr/bin/az account set --subscription ${subId} && /usr/bin/az storage account list --query '[].name' --output json 2>&1"
                             def proc = ["/bin/bash", "-c", command].execute()
                             def output = proc.text.trim()
                             proc.waitFor()
            
                             // 4. Handle Empty Output or Errors
                             if (proc.exitValue() != 0) {
                                 return ["AZ CLI Error: " + output.take(50)] // Show first 50 chars of error
                             }
            
                             if (!output || output == "[]") {
                                 return ["ERROR: No storage accounts found in this sub"]
                             }
            
                             // 5. Parse and Return
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
        CLIENT_LOWER = "${params.client_name.toLowerCase().replaceAll(' ', '')}"
    }

    stages {
        stage('Checkout Infrastructure repo'){
            steps {
                checkout([$class: 'GetSCM',
                branch: [['name': 'main']],
                userRemoteConfigs: [[
                    url:
                ]]
                ])
            }
        }
        stage('Get info') {
            steps {
                withEnv([
                    "TF_VAR_storage_account_name="
                ])
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

        stage('terraform format') {
            steps { 
                
                withCredentials([azureServicePrincipal('AZ_CREDS')]) {
                 sh
                     'terraform fmt'
                     
       
                    
                } 
            }
        }

        stage('terraform plan & security scan') {
            steps {
                //Map the terraform variables to send to the terraform file
                withEnv(["TF_VAR_client_name=${params.client_name}",
                        "TF_VAR_environment=${params.ENVIRONMENT}",
                        "TF_VAR_project_name=${params.project_name}",
                        "TF_VAR_vnet_address=${params.vnet_address}",
                        "TF_VAR_subnet_address=${params.subnet_address}",
                        "TF_VAR_location=${params.location}"
                ]) { 
                    withCredentials([azureServicePrincipal('AZ_CREDS')]) {
                        sh '''
                            export ARM_CLIENT_ID="${AZURE_CLIENT_ID}"
                            export ARM_CLIENT_SECRET="${AZURE_CLIENT_SECRET}"
                            export ARM_TENANT_ID="${AZURE_TENANT_ID}"
                            export ARM_SUBSCRIPTION_ID="${SUB_ID}"

                            terraform plan -out=tfplan
                            terraform show -json tfplan > tfplan.json
                        '''

                        dir('Docker-Images/security-scanner') {
                            sh 'docker build -t security-scanner:local .'
                        }

                        sh '''
                            echo "--- Starting Security Scan ---"
                            docker run --rm -v "$(pwd):/apps" \
                            security-scanner:local \
                            bash -c "tfsec . && checkov -f tfplan.json && trivy config tfplan.json"
                        '''
            }
        }
    }
}

        stage('terraform apply') {
            steps {
                 withEnv(["TF_VAR_client_name=${params.client_name}",
                        "TF_VAR_environment=${params.ENVIRONMENT}",
                        "TF_VAR_project_name=${params.project_name}",
                        "TF_VAR_vnet_address=${params.vnet_address}",
                        "TF_VAR_subnet_address=${params.subnet_address}",
                        "TF_VAR_location=${params.location}"
                ]) {
                
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
    }
}}

