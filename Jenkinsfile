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
  nodeSelector:
    workload: nonprod
  containers:
    - name: jnlp
      image: docker.m.daocloud.io/jenkins/inbound-agent:3383.vc8881d4b_0e76-1-jdk25
      resources:
        requests: {cpu: 50m, memory: 128Mi}
    - name: maven
      image: docker.m.daocloud.io/library/maven:3.9.9-eclipse-temurin-17
      command: ["sleep"]
      args: ["99d"]
      tty: true
      resources:
        requests: {cpu: 250m, memory: 512Mi}
        limits: {cpu: "2", memory: 3Gi}
    - name: kaniko
      image: registry.cn-hangzhou.aliyuncs.com/kube-image-repo/kaniko:v1.9.1-debug
      command: ["/busybox/cat"]
      tty: true
      resources:
        requests: {cpu: 250m, memory: 512Mi}
        limits: {cpu: "2", memory: 3Gi}
    - name: kubectl
      image: docker.m.daocloud.io/alpine/k8s:1.36.1
      command: ["sleep"]
      args: ["99d"]
      tty: true
      resources:
        requests: {cpu: 50m, memory: 128Mi}
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
        ACR_REGISTRY = 'crpi-vu83mjrcrc7gnl5w.cn-hangzhou.personal.cr.aliyuncs.com'
        IMAGE_REPOSITORY = 'nus_flowforge/flowforge-enterprise-be'
        GITHUB_CREDENTIAL_ID = 'github-credential'
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
                    def jobFolder = env.JOB_NAME.tokenize('/').find { it in ['test', 'prod'] }
                    if (!jobFolder) {
                        error("Job 必须位于 test 或 prod 文件夹中，当前 Job: ${env.JOB_NAME}")
                    }
                    env.TARGET_ENV = jobFolder
                    env.K8S_NAMESPACE = "flowforge-${env.TARGET_ENV}"
                    env.SHORT_COMMIT = sh(script: 'git rev-parse --short=12 HEAD', returnStdout: true).trim()
                    env.IMAGE_TAG = "${env.TARGET_ENV}-${BUILD_NUMBER}-${env.SHORT_COMMIT}"
                    echo "Target environment: ${env.TARGET_ENV}; namespace: ${env.K8S_NAMESPACE}; image tag: ${env.SERVICE_NAME}-${env.IMAGE_TAG}"
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
                sh '''
                    mvn -B -ntp \
                      -s microservices/settings.xml \
                      -U \
                      -Dmaven.wagon.http.retryHandler.count=5 \
                      -f microservices/pom.xml \
                      -pl "$SERVICE_NAME" -am clean verify
                '''
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
                              --cache=true \
                              --cache-run-layers=true \
                              --cache-ttl=168h \
                              --snapshot-mode=redo \
                              --use-new-run
                        '''
                    }
                }
            }
        }

        stage('Record deployment manifest') {
            when { expression { env.SHOULD_BUILD == 'true' } }
            steps {
                container('kubectl') {
                    withCredentials([gitUsernamePassword(
                        credentialsId: env.GITHUB_CREDENTIAL_ID,
                        gitToolName: 'Default'
                    )]) {
                        retry(3) {
                            sh '''
                                set -eu

                                SOURCE_IMAGE="$ACR_REGISTRY/nus_flowforge/$SERVICE_NAME"
                                IMAGE_REFERENCE="$ACR_REGISTRY/$IMAGE_REPOSITORY:$SERVICE_NAME-$IMAGE_TAG"
                                OVERLAY_DIR="k8s/overlays/$TARGET_ENV"

                                git fetch origin master
                                git checkout -B deployment-manifest origin/master
                                (
                                  cd "$OVERLAY_DIR"
                                  kustomize edit set image "$SOURCE_IMAGE=$IMAGE_REFERENCE"
                                )

                                git config user.name "Jenkins"
                                git config user.email "jenkins@flowforge.local"
                                git add "$OVERLAY_DIR/kustomization.yaml"

                                if git diff --cached --quiet; then
                                  echo "Deployment manifest already references $IMAGE_REFERENCE"
                                  exit 0
                                fi

                                git commit -m "chore(deploy): update $SERVICE_NAME $TARGET_ENV image"
                                git pull --rebase origin master
                                git push origin HEAD:master
                            '''
                        }
                    }
                }
            }
        }

        stage('Deploy to ACK') {
            when { expression { env.SHOULD_BUILD == 'true' } }
            steps {
                container('kubectl') {
                    withCredentials([usernamePassword(credentialsId: 'acr-credential', usernameVariable: 'ACR_USERNAME', passwordVariable: 'ACR_PASSWORD')]) {
                        sh '''
                        set -eu

                        if [ "$TARGET_ENV" = "prod" ]; then
                          echo "Deploying to production namespace: $K8S_NAMESPACE"
                        else
                          echo "Deploying to test namespace: $K8S_NAMESPACE"
                        fi

                        kubectl apply -f "k8s/overlays/$TARGET_ENV/namespace.yaml"

                        kubectl create secret docker-registry acr-secret \
                          --docker-server="$ACR_REGISTRY" \
                          --docker-username="$ACR_USERNAME" \
                          --docker-password="$ACR_PASSWORD" \
                          --namespace "$K8S_NAMESPACE" \
                          --dry-run=client -o yaml | kubectl apply -f -

                        if ! kubectl get secret flowforge-shared-secret --namespace "$K8S_NAMESPACE" >/dev/null 2>&1; then
                          echo "ERROR: Missing required secret flowforge-shared-secret in namespace $K8S_NAMESPACE"
                          echo "Create it before deploying:"
                          echo "kubectl -n $K8S_NAMESPACE create secret generic flowforge-shared-secret --from-literal=APP_JWT_SECRET=... --from-literal=INTERNAL_API_KEY=... --from-literal=DEFAULT_PASSWORD=..."
                          exit 1
                        fi

                        kubectl kustomize --load-restrictor=LoadRestrictionsNone "k8s/overlays/$TARGET_ENV" | kubectl apply -f -
                        kubectl rollout status "deployment/$SERVICE_NAME" \
                          --namespace "$K8S_NAMESPACE" --timeout=5m
                    '''
                    }
                }
            }
        }
    }

    post {
        success { echo "${env.SERVICE_NAME ?: 'service'} pipeline completed." }
        failure { echo 'Pipeline failed. Review the failed stage and Kubernetes events.' }
    }
}
