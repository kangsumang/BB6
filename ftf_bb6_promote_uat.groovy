def docker_image = env.DOCKER_IMAGE
def chart_name = env.CHART_NAME
def chart_path = env.CHART_PATH
def image_version = docker_image.split(':')[1]
def config_rc_count = 0
def new_conf_tag = ''
def new_chart_tag = ''

def conf_branch = env.CONF_BRANCH

def target_env = 'uat'
def uat_chart_branch = 'uat'
def target_namespace = 'eapi-uat'


def nexus_unsecured_host = "10.233.129.4:5010"
def nexusRegistryCredential = 'cde923bd-7c10-4da6-becc-1b708e0abaa3'

def nexus_secured_host = "10.134.17.21:5001"
def nexus_secured_host_raw = "10.134.17.21"

def charts_repo = "https://lphgeapjenb01a.control.bpi.com/Backbase/bb6.git"

def config_only = false
 
def ocp_sit_cluster = 'https://api.devbpiocp.dom001c.local:6443'

pipeline {
    agent {
        node {
            label 'eapi-deploy'
        }
    }
    options {
        disableConcurrentBuilds()
    }
    stages {
        stage('Generate Package') {
            steps {
                // Checkout repositories
                withCredentials([usernamePassword(credentialsId: "gitlab-user", passwordVariable: 'password', usernameVariable: 'username')]) {
                    script {
                        dir('charts-ftf-bb6') {
                            git credentialsId: 'gitlab-user',
                                url: charts_repo    
                            sh "git config remote.origin.url " + charts_repo.replaceAll("//","//${username}:${password}@")
                        }
                    }
                }

                script {
                    GIT_HASH = sh(returnStdout: true, script: "git rev-parse --short HEAD").trim()
                }
                
                dir('charts-ftf-bb6') {
                    script {
                        sh "git checkout ${uat_chart_branch}"
                        sh "git fetch --tags"
                        sh "git status"
                        sh "git merge --strategy-option theirs origin/master -m 'Merge updates from master'"

                        def latest_chart_tag = sh(
                                script: "git tag -l *${target_env}-${chart_name}-rc* --sort=-committerdate | head -n1",
                                returnStdout: true
                        ).trim()
                        def ch_counter = 1

                        if (latest_chart_tag) {
                            ch_counter = latest_chart_tag.split('-')[-1].substring(2).toInteger()

                            def commonlib_changes = sh(
                                    script: "git diff --name-only HEAD ${latest_chart_tag} -- commonlib | wc -l",
                                    returnStdout: true
                            ).trim().toInteger() > 0

                            def chart_changes = sh(
                                    script: "git diff --name-only HEAD ${latest_chart_tag} -- ${chart_name} | grep 'templates\\|values-${target_env}.yaml\\|values.yaml' | wc -l",
                                    returnStdout: true
                            ).trim().toInteger() > 0

                            if (commonlib_changes || chart_changes) {
                                ch_counter++
                            }
                        }

                     // new_chart_tag = "${helm_label}-ch${ch_counter}"
					//	new_chart_tag = "${app_version}"

                    //  println "[INFO] latest_chart_tag: ${latest_chart_tag}"
                    //  println "[INFO] new_chart_tag: ${new_chart_tag}"
						
                     // sh "helm dependency update ${chart_name}"

                        // Generate manifest file
						
                         sh """ 
                            helm template ${chart_name} ${chart_name}\\
							-f ${chart_name}/values.yaml \\
							--set global.app.image.tag="${image_version}" \\
                            -n ${target_namespace} > manifest.yaml
                           """

                        // GENERATE PRE-PROD CHART PACKAGE
                        sh "helm package ${chart_name} | sed 's/Successfully packaged chart and saved it to: //'"
						sh "tar cf ${chart_name}.tar ./${chart_name}/"

                        currentBuild.displayName = "#${BUILD_NUMBER} ${chart_name}"
						
                    }
                }								
			// input message: 'Proceed'
            }
			
        }
        stage('Validate Manifest') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'jenkins-oc-login', passwordVariable: 'password', usernameVariable: 'username')]) {
                    dir('charts-ftf-bb6'){
                        sh """#!/bin/bash -e
                        set +x
                        cat manifest.yaml
                        oc login -u=${username} -p=${password} -s=${ocp_sit_cluster} --insecure-skip-tls-verify >& /dev/null
                        oc apply -f manifest.yaml -n eapi-sit --dry-run=client
                        """.stripMargin()
                    }
                }
			//	input message: 'Proceed'
            }
        }
        stage('Pull and Re-tag Image') {
            when {
                expression { return !config_only }
            }
            steps {
                withCredentials([usernamePassword(credentialsId: nexusRegistryCredential, passwordVariable: 'password', usernameVariable: 'username')]) {
                    sh """#!/bin/bash -e
                    buildah login -u ${username} -p ${password} --tls-verify=false ${nexus_unsecured_host}/bpi/backbase/${chart_path}
                    buildah pull --tls-verify=false ${nexus_unsecured_host}/bpi/backbase/${chart_path}/${docker_image}
                    buildah tag ${nexus_unsecured_host}/bpi/backbase/${chart_path}/${docker_image} ${nexus_secured_host}/bpi/backbase/${chart_path}/${docker_image}
                    """
                }
               // input message: 'Proceed'
            }
        }

// Scan image using trivy (abort or proceed/ create report that clean-up all vuln. in image - Java)

        stage('Push Image to Nexus') {
            when {
                expression { return !config_only }
            }
            steps {
                withCredentials([usernamePassword(credentialsId: nexusRegistryCredential, passwordVariable: 'password', usernameVariable: 'username')]) {
                    sh """#!/bin/bash -e
                    buildah login -u ${username} -p ${password} --tls-verify=false ${nexus_secured_host}
                    buildah push ${nexus_secured_host}/bpi/backbase/${chart_path}/${docker_image}
					buildah inspect --type image ${docker_image} | grep 'FromImageDigest' | tr -d \\\", | tr -s " " | cut -d " " -f3 > img.sha
					cat img.sha
                    """
                }
               // input message: 'Proceed'
            }
		}	
        stage('Push Chart to Nexus') {
            steps {
                dir('charts-ftf-bb6') {
                    script {
                        //  if ( !config_only ) {
                        //    sh "mv ../img.sha ./"
						//	sh "tar cf ${app_version}.tar ${app_version}.tgz manifest-${target_env}.yaml img.sha"
                        // } else {
                        //   sh "tar cf ${app_version}.tar ${app_version}.tgz manifest-${target_env}.yaml"
                        // }

                        withCredentials([usernameColonPassword(
                            credentialsId: "${nexusRegistryCredential}",
                            variable: 'nexus_credentials')]) {

                            sh """#!/bin/bash -e
                                |set -v
                                |curl -f -u \${nexus_credentials} --upload-file ${chart_name}.tar \\
                                |    https://${nexus_secured_host_raw}:8443/repository/bb6_promoted_charts_${target_env}/${chart_name}/${chart_name}-${commit_hash}.tar
                                """.stripMargin()
                         }

                         currentBuild.description = """
                            <table>
                                <tr>
                                    <td>Image Tag:</td><td>${docker_image}</td>
                                </tr>
                                <tr>
                                    <td>Config Tag:</td><td>none</td>
                                </tr>
                                <tr>
                                    <td>Chart Tag:</td><td>${target_env}-${chart_name}-${commit_hash}</td>
                                </tr>
                                <tr>
                                    <td>Nexus Package: </td>
                                    <td>
                                        <a name="nexus_package_url" href="https://${nexus_secured_host_raw}:8443/repository/bb6_ocp_promoted_charts_${target_env}/${chart_name}/${chart_name}.tar">
                                            https://${nexus_secured_host_raw}:8443/repository/bb6_ocp_promoted_charts_${target_env}/${chart_name}/${chart_name}-${commit_hash}.tar
                                        </a>
                                    </td>
                                </tr>
                            </table>
                            """.stripMargin()
                    }
                }
            }
        }
        
	}
    
    post {
        always {
            cleanWs()
        }
    }
}