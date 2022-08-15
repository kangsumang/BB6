// @Library('libs@pipelinev2')
def kubernetes_version = '1.8'
def chart_path = env.CHART_PATH
def chart_name = env.CHART_NAME
def docker_image = env.DOCKER_IMAGE
def values_file = env.VALUES_FILE
def project = env.PROJECT
def image_version = docker_image.split(':')[1]

def group = ''

def nexus_host = "lphgeapjenb01a.control.bpi.com"
def nexus_secured_host = "$nexus_host:5001"
def nexusRegistryCredential = 'c788af61-bb93-4117-ab06-885026e54699'
def candidate_image = ''
def promoted_image = ''

//def registry = "lphseapdtr.uat.bpi.local"
def target_registry = 'default-route-openshift-image-registry.apps.uatbpiocp.uat.bpi.local'
def targetRegistryCredential = '30fc37b4-a03a-4f7b-8b75-46f644b834bc'
def target_env = 'uat'
def uat_chart_branch = 'uat'
def target_namespace = 'ng-green'
def charts_repo = 'https://lphgeapjenb01a.control.bpi.com/DevOps/charts-eapi-openshift.git'

//def ocp_uat_cluster = 'https://api.devbpiocp.dom001c.local:6443'
def ocp_uat_cluster = 'https://api.uatbpiocp.uat.bpi.local:6443'

pipeline {
    agent {
        node {
            label 'master'
            customWorkspace "${JENKINS_HOME}/jobs/${JOB_NAME}/workspace/${BUILD_ID}"
        }
    }
    
    options {
        disableConcurrentBuilds()
    }
    
    environment {
        kubeadmin = credentials('kubeadmin')
        KUBECONFIG = '/devops/jenkins/.kube/openshift_config'
    }

    stages {
        stage('Download Helm Chart') {
            steps {
                script {
                    currentBuild.displayName = "#${BUILD_NUMBER} ${chart_name}"

                    // Download helm chart from Nexus
                    withCredentials([usernameColonPassword(
                            credentialsId: 'c788af61-bb93-4117-ab06-885026e54699',
                            variable: 'nexus_credentials')]) {
                        def status_code = sh(
                                script: """curl -O --silent --output /dev/null -w %{http_code} -u \$nexus_credentials \\
                                           https://lphgeapjenb01a.control.bpi.com:8443/repository/bb6_promoted_charts_${target_env}/${chart_path}/${chart_name}.tar""",
                                returnStdout: true).trim().toInteger()


                        println status_code
                        if (status_code != 200) {
                            error "[ERROR]: $status_code: Chart download failed"
                        }
                    }

                    // Extract the helm chart package
                    sh "tar xvf ${chart_name}.tar"
                    sh "ls"
                }
            }
        }
        stage('Pull and Re-tag Docker Image') {
            steps {
                withCredentials([usernamePassword(credentialsId: nexusRegistryCredential,
                        passwordVariable: 'NEXPASS', usernameVariable: 'NEXUSER')]) {
                    sh """
                    set +e
                    podman images
                    oc login -u=kubeadmin -p=\${kubeadmin_PSW} ${ocp_uat_cluster} --insecure-skip-tls-verify >& /dev/null
                    podman login -u \$NEXUSER -p \$NEXPASS https://$nexus_secured_host
                    podman pull ${nexus_secured_host}/bpi/backbase/${chart_path}/${docker_image}
                    podman login -u kubeadmin -p \$(oc whoami -t) ${target_registry}/${project} --tls-verify=false
				//	podman push --tls-verify=false ${target_registry}/${project}/${docker_image}
                    """
                }
                // input message: 'Proceed or Abort?'
            }
        }
        stage('Create manifest and Deploy') {
            steps {
                sh """
                helm upgrade -i ${chart_name} ${chart_name} --values ${chart_name}/${values_file} --set global.app.image.tag="${image_version}" -n ${project}
                """
            }
        }
    }
    
    post {
        always {
            cleanWs()
        }
    }
} 