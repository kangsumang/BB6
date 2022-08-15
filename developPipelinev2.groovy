def call(body) {
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    log.info "maven_command: ${config.maven_command}"

    def repo_name = scm.getUserRemoteConfigs()[0].getUrl().tokenize('/').last().split("\\.")[0].toLowerCase()
    def group = env.JOB_NAME.split('/')[1].toLowerCase()

    def hash = ''
    def app_version = ''
    def app = repo_name

    def maven_settings = 'maven-settings-v2'
    def sonar_project_key = ''
    def sonar_project_name = ''
    def sonar_project_version = ''
    def sonar_coverage_xml_paths = 'target/jacoco-it/jacoco.xml,target/jacoco-ut/jacoco.xml'
    def sonar_junit_report_paths = 'target/failsafe-reports'
    def image_scan_log_pattern = '*-scan.log'

    def agent_label = 'maven-jdk8u181'

    def ocp_sit_cluster = 'https://api.devbpiocp.dom001c.local:6443'
    
    // List of microservices without sonar stage
    def no_sonar_ms = [
        'datastub',
        'mockingjay',
        'pqloanscampaign',
        'sfmceventdelivery'
    ]
    
    pipeline {
        agent none
        environment {
            registryHost = "default-route-openshift-image-registry.apps.devbpiocp.dom001c.local/eapi-dev"
            registry = "${registryHost}/${group}"
            registryUrl = "https://${registryHost}"
            registryCredential = 'jenkins-dtr'
            dockerImage = ''
        }
        options {
            gitLabConnection('gitLab')
        }

        stages {
            stage('Checkout') {
                agent {
                    label 'utils'
                }
                steps {
                    script {
                        def base_image = sh(
                            script: '''cat Containerfile | grep -v '#' | grep -vi ' AS ' | grep FROM | awk '{print $2}' ''',
                            returnStdout: true).trim()

                        // // ms that needs large agent
                        /*def large_ms = [
                            'account',
                            'billspayment',
                            'cards',
                            'customer',
                            'deposits',
                            'depositsorigination'
                        ]*/

                        // map base image to build agent label
                        /*def base_image_map = [
                            'registry.access.redhat.com/ubi8:8.5-226.1645809065': [
                                'default': 'registry.access.redhat.com/ubi8:8.5-226.1645809065'
                            ]
                        ]

                        // Update agent_label
                        for (entry in base_image_map) {
                            if (base_image == entry.key){
                                if(large_ms.contains(app)) {
                                    agent_label = entry.value["large"]
                                } else {
                                    agent_label = entry.value["default"]
                                }
                                agent_label = entry.value["default"]
                                break
                            }
                        }*/
                        agent_label = 'maven'
                        println "Base Image: ${base_image}"
                        println "Build Agent Label: ${agent_label}"
                    }
                    script {
                        hash = "${GIT_COMMIT[0..6]}"
                        app_version = "alpha-${hash}"
                        def sonar_project_group_id = sh(
                            script: '''xpath -q -e '/project/groupId/text()' pom.xml''',
                            returnStdout: true).trim()

                        def sonar_project_artifact_id = sh(
                            script: '''xpath -q -e '/project/artifactId/text()' pom.xml''',
                            returnStdout: true).trim()

                        sonar_project_key = "${sonar_project_group_id}:${sonar_project_artifact_id}:${env.BRANCH_NAME}"
                        sonar_project_name = "${sonar_project_artifact_id}-${env.BRANCH_NAME}"
                        sonar_project_version = "${app_version}"
                        
                        println "Sonar Project Key: ${sonar_project_key}"
                        println "Sonar Project Name: ${sonar_project_name}"
                        println "Sonar Project Version: ${sonar_project_version}"
                    }
                    stash name: "checkout", includes: "**", excludes: ".git/**", allowEmpty: false
                }
            }
            stage('Maven') {
                agent {
                    node {
                        label "$agent_label"
                    }
                }
                options {
                    skipDefaultCheckout true
                }
                stages {
                    stage('Maven Build') {
                        steps {
                            // updateGitlabCommitStatus name: 'Maven Build', state: 'pending'
                            unstash "checkout"
                            script {
                                maven {
                                    maven_command = config.maven_command
                                    maven_settings_id = maven_settings
                                }
                            }
                            // updateGitlabCommitStatus name: 'Maven Build', state: 'success'
                        }
                    }
                    stage('Sonarqube Scan') {
                        when {
                            expression { return !no_sonar_ms.contains(app) }
                        }
                        steps {
                            // updateGitlabCommitStatus name: 'Sonarqube', state: 'pending'
                            script {
                                // use default surefire report path if sonar_junit_report_paths does not exist
                                if (!fileExists(sonar_junit_report_paths)) {
                                    sonar_junit_report_paths = 'target/surefire-reports'
                                }
                            }

                            script {
                                def maven_args = [
                                    "org.sonarsource.scanner.maven:sonar-maven-plugin:3.5.0.1254:sonar",
                                    "-Dsonar.projectKey=${sonar_project_key}",
                                    "-Dsonar.projectName=${sonar_project_name}",
                                    "-Dsonar.projectVersion=${sonar_project_version}",
                                    "-Dsonar.coverage.jacoco.xmlReportPaths='${sonar_coverage_xml_paths}'",
                                    "-Dsonar.junit.reportPaths='${sonar_junit_report_paths}'"
                                ]
                                withSonarQubeEnv('Dev Sonarqube') {
                                    maven {
                                        maven_command = maven_args.join(' ')
                                        maven_settings_id = maven_settings
                                    }
                                }
                                // Temporarily commented since this causes error:
                                /*timeout(time: 60, unit: 'SECONDS') {
                                    waitForQualityGate abortPipeline: true
                                }*/
                            }
                            script {
                                withCredentials([usernameColonPassword(
                                    credentialsId: 'cde923bd-7c10-4da6-becc-1b708e0abaa3',
                                    variable: 'nexus_credentials')]) {
                                    status_code = sh(
                                        script: """curl --silent --output sonar-cnes-report-3.3.1.jar -w \"%{http_code}\" -u ${nexus_credentials} \\
                                            https://10.134.17.21:8443/repository/files/pipelinev2/jar/sonar-cnes-report-3.3.1.jar
                                            """,
                                        returnStdout: true).trim()
                                        
                                    if (status_code != "200") {
                                        log.err "Download Failed"
                                    }
                                }
                            }
                            // updateGitlabCommitStatus name: 'Sonarqube', state: 'success'
                        }
                    }
                }
                post {
                    always {
                        script {
                            stash name: "target", includes: "target/**", allowEmpty: false
                        }
                    }
                }
            }
            stage('Build Podman Image') {
                agent {
                    node {
                        label "podman"
                    }
                }
                options {
                    skipDefaultCheckout true
                }
                steps {
                    // updateGitlabCommitStatus name: 'Docker Image Build', state: 'pending'
                    unstash "checkout"
                    unstash "target"
                    script {
                        withCredentials([usernamePassword(credentialsId: 'jenkins-oc-login', passwordVariable: 'password', usernameVariable: 'username')]) {
                            //dockerImage = docker.build "${registry}/${app}:${app_version}"
                            sh """#!/bin/bash -e
                            set +e

                            oc login -u=${username} -p=${password} -s=${ocp_sit_cluster} --insecure-skip-tls-verify >& /dev/null
                            export REGISTRY_AUTH_FILE=~/auth.json

                            podman login -u ${username} -p \$(oc whoami -t) --tls-verify=false ${registryHost}

                            podman build --authfile \${REGISTRY_AUTH_FILE} --tls-verify=false --creds=${username}:\$(oc whoami -t) -t ${registryHost}/${app}:${app_version} .

                            podman image ls
                            podman save -o app-image-tar.tar ${registryHost}/${app}:${app_version}
                            tar -xvf app-image-tar.tar
                            ls -lrt
                            """
                            // podman save -o app-image-tar.tar --format oci-archive ${registryHost}/${app}:${app_version}
                        }
                    }
                    stash name: "app-image-tar", includes: "app-image-tar.tar", allowEmpty: false
                    // Clean dockerfile stash
                    stash name: "checkout", excludes: "**", allowEmpty: true
                    // Clean target stash
                    stash name: "target", excludes: "**", allowEmpty: true
                    // updateGitlabCommitStatus name: 'Docker Image Build', state: 'success'
                }
            }
            stage('Scan Image') {
                agent {
                    label 'trivy'
                }
                options {
                    skipDefaultCheckout()
                }
                environment {
                    http_proxy="http://192.168.199.34:8080"
                    https_proxy="http://192.168.199.34:8080"
                    no_proxy="localhost,10.233.132.114,10.134.17.21,lphgeapjenb01a.control.bpi.com,10.233.132.115,lphbeapdwn01a.headoffice.corp.bpi.com.ph,docker-daemon"
                }
                steps {
                    unstash "app-image-tar"
                    sh """
                        ls -lrt
                        #podman load -i app-image-tar.tar

                        #trivy -d image --ignore-unfixed ${registry}/${app}:${app_version} | tee ${app}-${app_version}-scan.log

                        trivy image --skip-update --input app-image-tar.tar | tee ${app}-${app_version}-scan.log
                       """
                    stash includes: "${app}-${app_version}-scan.log", name: 'image-scan-log'
                }
            }
            stage('Push Podman Image') {
                agent {
                    node {
                        label "podman"
                    }
                }
                options {
                    skipDefaultCheckout true
                }
                steps {
                    unstash "app-image-tar"
                    // updateGitlabCommitStatus name: 'Docker Image Push', state: 'pending'
                    withCredentials([usernamePassword(credentialsId: 'jenkins-oc-login', passwordVariable: 'password', usernameVariable: 'username')]) {
                        // sh """
                        //     docker login -u ${username} -p ${password} ${registryUrl}
                        // """
                        sh """#!/bin/bash -e
                            set +e

                            oc login -u=${username} -p=${password} -s=${ocp_sit_cluster} --insecure-skip-tls-verify >& /dev/null

                            export REGISTRY_AUTH_FILE=~/auth.json

                            podman login -u ${username} -p \$(oc whoami -t) --tls-verify=false ${registryHost}
                            podman load -i app-image-tar.tar
                            podman push --authfile \${REGISTRY_AUTH_FILE} --tls-verify=false --creds=${username}:\$(oc whoami -t) ${registryHost}/${app}:${app_version}
                        """
                    }
                    script {
                        // dockerImage.push()
                        currentBuild.displayName = "#${BUILD_NUMBER} ${app_version}"
                    }
                    // updateGitlabCommitStatus name: 'Docker Image Push', state: 'success'
                }
            }
        }

        /*post {
            success {
                node("!master") {
                    script {
                        unstash 'image-scan-log'

                        // Send to requestor -- user that triggered the build
                        email.sendBuildResult(
                            "",
                            image_scan_log_pattern
                        )

                        // Upload image scan logs via email
                        email.sendToBox(
                            "Image_S.nimpibaoebfbuvt7@u.box.com",
                            image_scan_log_pattern
                        )
                    }
                }
            }
            failure {
                node("!master") {
                    script {
                        email.sendBuildResult("eapi-devops@wwpdl.vnet.ibm.com")
                    }
                }
            }
        }*/
    }
}
