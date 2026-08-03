def services = [
    'auth-service',
    'workflow-service',
    'notification-audit-service',
    'ai-assistant-service',
    'attachment-service',
    'api-gateway'
]

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
    - name: jnlp
      image: docker.m.daocloud.io/jenkins/inbound-agent:3383.vc8881d4b_0e76-1-jdk25
      resources:
        requests: {cpu: 100m, memory: 256Mi}
    - name: maven
      image: docker.m.daocloud.io/library/maven:3.9.9-eclipse-temurin-17
      command: ["sleep"]
      args: ["99d"]
      tty: true
      resources:
        requests: {cpu: 500m, memory: 1Gi}
        limits: {cpu: "2", memory: 3Gi}
    - name: kaniko
      image: registry.cn-hangzhou.aliyuncs.com/kube-image-repo/kaniko:v1.9.1-debug
      command: ["/busybox/cat"]
      tty: true
      resources:
        requests: {cpu: 500m, memory: 1Gi}
        limits: {cpu: "2", memory: 3Gi}
    - name: kubectl
      image: docker.m.daocloud.io/alpine/k8s:1.36.1
      command: ["sleep"]
      args: ["99d"]
      tty: true
      resources:
        requests: {cpu: 100m, memory: 128Mi}
        limits: {cpu: 500m, memory: 512Mi}
'''
        }
    }

    options {
        disableConcurrentBuilds(abortPrevious: true)
        timestamps()
        timeout(time: 45, unit: 'MINUTES')
        skipDefaultCheckout(true)
    }

    environment {
        K8S_NAMESPACE = 'flowforge'
        ACR_REGISTRY = 'crpi-vu83mjrcrc7gnl5w.cn-hangzhou.personal.cr.aliyuncs.com'
        IMAGE_REPOSITORY = 'nus_flowforge/flowforge-enterprise-be'
    }

    stages {
        stage('Checkout and resolve service') {
            steps {
                retry(3) {
                    checkout([
                        $class: 'GitSCM',
                        branches: [[name: '*/master']],
                        userRemoteConfigs: [[url: 'https://github.com/flowforge-enterprise/flowforge-enterprise-be.git']]
                    ])
                }
                sh 'git config --global --add safe.directory "$WORKSPACE"'
                script {
                    def matches = services.findAll { env.JOB_BASE_NAME == it || env.JOB_BASE_NAME.endsWith("-${it}") }
                    if (matches.size() != 1) {
                        error("Job 名必须是服务名或以服务名结尾。允许值: ${services.join(', ')}")
                    }
                    env.SERVICE_NAME = matches[0]
                    env.SHORT_COMMIT = sh(script: 'git rev-parse --short=12 HEAD', returnStdout: true).trim()
                    env.IMAGE_TAG = "${BUILD_NUMBER}-${env.SHORT_COMMIT}"
                }
            }
        }

        stage('Detect changes') {
            steps {
                script {
                    def base = env.GIT_PREVIOUS_SUCCESSFUL_COMMIT?.trim()
                    if (!base || sh(script: "git cat-file -e '${base}^{commit}' 2>/dev/null", returnStatus: true) != 0) {
                        base = sh(script: 'git rev-parse HEAD^ 2>/dev/null || git rev-parse HEAD', returnStdout: true).trim()
                    }
                    def changed = sh(script: "git diff --name-only '${base}' HEAD", returnStdout: true).trim().readLines()
                    def commonChanged = changed.any {
                        it in ['microservices/pom.xml', 'microservices/Dockerfile', 'Jenkinsfile']
                    }
                    def securityChanged = changed.any { it.startsWith('microservices/platform-security/') }
                    def usesSecurity = env.SERVICE_NAME != 'api-gateway'
                    def serviceChanged = changed.any { it.startsWith("microservices/${env.SERVICE_NAME}/") }
                    env.SHOULD_BUILD = (commonChanged || serviceChanged || (securityChanged && usesSecurity)).toString()
                    echo "Service: ${env.SERVICE_NAME}; changed files: ${changed.size()}; build: ${env.SHOULD_BUILD}"
                }
            }
        }

        stage('Test') {
            when { expression { env.SHOULD_BUILD == 'true' } }
            steps {
                sh 'mvn -B -ntp -f microservices/pom.xml -pl "$SERVICE_NAME" -am clean verify'
            }
        }

        stage('Build and push image') {
            when { expression { env.SHOULD_BUILD == 'true' } }
            steps {
                container('kaniko') {
                    withCredentials([usernamePassword(credentialsId: 'acr-credential', usernameVariable: 'ACR_USERNAME', passwordVariable: 'ACR_PASSWORD')]) {
                        sh '''
                            set -eu
                            mkdir -p /kaniko/.docker
                            AUTH="$(printf '%s:%s' "$ACR_USERNAME" "$ACR_PASSWORD" | base64 | tr -d '\n')"
                            printf '{"auths":{"%s":{"auth":"%s"}}}' "$ACR_REGISTRY" "$AUTH" > /kaniko/.docker/config.json
                            /kaniko/executor \
                              --context "$WORKSPACE/microservices" \
                              --dockerfile "$WORKSPACE/microservices/Dockerfile" \
                              --build-arg "MODULE=$SERVICE_NAME" \
                              --destination "$ACR_REGISTRY/$IMAGE_REPOSITORY:$SERVICE_NAME-$IMAGE_TAG" \
                              --snapshot-mode=redo \
                              --use-new-run
                        '''
                    }
                }
            }
        }

        stage('Deploy to ACK') {
            when { expression { env.SHOULD_BUILD == 'true' } }
            steps {
                container('kubectl') {
                    sh '''
                        kubectl set image "deployment/$SERVICE_NAME" \
                          "$SERVICE_NAME=$ACR_REGISTRY/$IMAGE_REPOSITORY:$SERVICE_NAME-$IMAGE_TAG" \
                          --namespace "$K8S_NAMESPACE"
                        kubectl rollout status "deployment/$SERVICE_NAME" \
                          --namespace "$K8S_NAMESPACE" --timeout=5m
                    '''
                }
            }
        }
    }

    post {
        success { echo "${env.SERVICE_NAME ?: 'service'} pipeline completed." }
        failure { echo 'Pipeline failed. Review the failed stage and Kubernetes events.' }
    }
}
