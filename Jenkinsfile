pipeline {
    agent {
        kubernetes {
            defaultContainer 'maven'
            yaml '''
apiVersion: v1
kind: Pod
spec:
  serviceAccountName: jenkins
  containers:
    - name: maven
      image: docker.m.daocloud.io/library/maven:3.9.9-eclipse-temurin-17
      command: ["sleep"]
      args: ["99d"]
      tty: true
      resources:
        requests:
          cpu: 500m
          memory: 1Gi
        limits:
          cpu: "2"
          memory: 3Gi
    - name: kaniko
      image: docker.m.daocloud.io/gcr.io/kaniko-project/executor:v1.23.2-debug
      command: ["/busybox/cat"]
      tty: true
      resources:
        requests:
          cpu: 500m
          memory: 1Gi
        limits:
          cpu: "2"
          memory: 3Gi
    - name: kubectl
      image: docker.m.daocloud.io/alpine/k8s:1.36.1
      command: ["sleep"]
      args: ["99d"]
      tty: true
      resources:
        requests:
          cpu: 100m
          memory: 128Mi
        limits:
          cpu: 500m
          memory: 512Mi
'''
        }
    }

    options {
        disableConcurrentBuilds()
        timestamps()
        timeout(time: 45, unit: 'MINUTES')
    }

    environment {
        K8S_NAMESPACE = 'flowforge'
        ACR_REGISTRY = 'crpi-vu83mjrcrc7gnl5w.cn-hangzhou.personal.cr.aliyuncs.com'
        IMAGE_REPOSITORY = 'nus_flowforge/flowforge-enterprise-be'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
                script {
                    env.SHORT_COMMIT = sh(
                        script: 'git rev-parse --short=12 HEAD',
                        returnStdout: true
                    ).trim()
                    env.IMAGE_TAG = "${BUILD_NUMBER}-${env.SHORT_COMMIT}"
                }
            }
        }

        stage('Test') {
            steps {
                sh 'mvn -B -ntp clean verify'
                sh 'mvn -B -ntp -f microservices/pom.xml clean verify'
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: '**/target/surefire-reports/*.xml'
                }
            }
        }

        stage('Build and Push Images') {
            steps {
                container('kaniko') {
                    withCredentials([usernamePassword(
                        credentialsId: 'acr-credential',
                        usernameVariable: 'ACR_USERNAME',
                        passwordVariable: 'ACR_PASSWORD'
                    )]) {
                        sh '''
                            set -eu
                            mkdir -p /kaniko/.docker
                            AUTH="$(printf '%s:%s' "$ACR_USERNAME" "$ACR_PASSWORD" | base64 | tr -d '\\n')"
                            printf '{"auths":{"%s":{"auth":"%s"}}}' "$ACR_REGISTRY" "$AUTH" > /kaniko/.docker/config.json
                        '''

                        script {
                            def services = [
                                'auth-service',
                                'workflow-service',
                                'notification-audit-service',
                                'ai-assistant-service',
                                'attachment-service',
                                'api-gateway'
                            ]

                            for (serviceName in services) {
                                sh """
                                    /kaniko/executor \\
                                      --context \"${WORKSPACE}/microservices\" \\
                                      --dockerfile \"${WORKSPACE}/microservices/Dockerfile\" \\
                                      --build-arg MODULE=${serviceName} \\
                                      --destination \"${ACR_REGISTRY}/${IMAGE_REPOSITORY}:${serviceName}-${IMAGE_TAG}\" \\
                                      --snapshot-mode=redo \\
                                      --use-new-run
                                """
                            }
                        }
                    }
                }
            }
        }

        stage('Deploy to ACK') {
            steps {
                container('kubectl') {
                    script {
                        def services = [
                            'auth-service',
                            'workflow-service',
                            'notification-audit-service',
                            'ai-assistant-service',
                            'attachment-service',
                            'api-gateway'
                        ]

                        for (serviceName in services) {
                            sh """
                                kubectl set image deployment/${serviceName} \\
                                  ${serviceName}=${ACR_REGISTRY}/${IMAGE_REPOSITORY}:${serviceName}-${IMAGE_TAG} \\
                                  --namespace ${K8S_NAMESPACE}

                                kubectl rollout status deployment/${serviceName} \\
                                  --namespace ${K8S_NAMESPACE} \\
                                  --timeout=5m
                            """
                        }
                    }
                }
            }
        }

        stage('Verify') {
            steps {
                container('kubectl') {
                    sh '''
                        kubectl get deployments,pods,services --namespace "$K8S_NAMESPACE" -o wide
                    '''
                }
            }
        }
    }

    post {
        success {
            echo "Deployed build ${BUILD_NUMBER}, commit ${SHORT_COMMIT}."
        }
        failure {
            echo 'CI/CD failed. Review the failed stage and Kubernetes events.'
        }
        cleanup {
            deleteDir()
        }
    }
}
