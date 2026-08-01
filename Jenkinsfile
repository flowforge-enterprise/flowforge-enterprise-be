pipeline {
    agent any

    environment {
        KUBECONFIG = '/var/jenkins_home/.kube/config'

        K8S_NAMESPACE    = 'flowforge'
        ACR_REGISTRY     = 'crpi-vu83mjrcrc7gnl5w.cn-hangzhou.personal.cr.aliyuncs.com'
        ACR_NAMESPACE    = 'nus_flowforge'

        IMAGE_TAG = "${BUILD_NUMBER}"
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Check Tools') {
            steps {
                sh '''
                echo "===== Check docker ====="
                docker version

                echo "===== Check kubectl ====="
                kubectl version --client

                echo "===== Check ACK nodes ====="
                kubectl get nodes
                '''
            }
        }

        stage('Build & Push Images') {
            steps {
                withCredentials([usernamePassword(
                    credentialsId: 'acr-credential',
                    usernameVariable: 'ACR_USERNAME',
                    passwordVariable: 'ACR_PASSWORD'
                )]) {
                    sh '''
                    echo "===== Login to ACR ====="
                    echo "$ACR_PASSWORD" | docker login $ACR_REGISTRY -u "$ACR_USERNAME" --password-stdin
                    '''
                }

                script {
                    def services = [
                        'auth-service',
                        'workflow-service',
                        'notification-audit-service',
                        'ai-assistant-service',
                        'attachment-service',
                        'api-gateway'
                    ]

                    for (svc in services) {
                        def imageName   = "${ACR_REGISTRY}/${ACR_NAMESPACE}/${svc}:${IMAGE_TAG}"
                        def imageLatest = "${ACR_REGISTRY}/${ACR_NAMESPACE}/${svc}:latest"

                        sh """
                        echo "===== Build ${svc} ====="
                        docker build \
                          --build-arg MODULE=${svc} \
                          -t ${imageName} \
                          -f microservices/Dockerfile \
                          microservices/

                        docker tag ${imageName} ${imageLatest}

                        echo "===== Push ${svc} ====="
                        docker push ${imageName}
                        docker push ${imageLatest}
                        """
                    }
                }
            }
        }

        stage('Deploy to ACK') {
            steps {
                sh '''
                echo "===== Apply namespace ====="
                kubectl apply -f k8s/namespace.yaml

                echo "===== Apply infrastructure (MySQL, etc.) ====="
                kubectl apply -f k8s/mysql.yaml
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

                    for (svc in services) {
                        def imageName = "${ACR_REGISTRY}/${ACR_NAMESPACE}/${svc}:${IMAGE_TAG}"

                        sh """
                        echo "===== Deploy ${svc} ====="
                        kubectl apply -f k8s/microservices/${svc}.yaml -n ${K8S_NAMESPACE}

                        kubectl set image deployment/${svc} \
                          ${svc}=${imageName} \
                          -n ${K8S_NAMESPACE}

                        kubectl rollout status deployment/${svc} \
                          -n ${K8S_NAMESPACE} \
                          --timeout=180s
                        """
                    }
                }
            }
        }

        stage('Verify ACK Resources') {
            steps {
                sh '''
                echo "===== Pods ====="
                kubectl get pods -n $K8S_NAMESPACE

                echo "===== Services ====="
                kubectl get svc -n $K8S_NAMESPACE

                echo "===== PVC ====="
                kubectl get pvc -n $K8S_NAMESPACE || true

                echo "===== Service logs (last 50 lines each) ====="
                for svc in auth-service workflow-service notification-audit-service ai-assistant-service attachment-service api-gateway; do
                  echo "--- $svc ---"
                  kubectl logs -n $K8S_NAMESPACE deployment/$svc --tail=50 || true
                done
                '''
            }
        }
    }

    post {
        failure {
            echo 'Pipeline failed. Check the logs above for details.'
        }
        success {
            echo "All microservices deployed successfully at build #${BUILD_NUMBER}."
        }
    }
}
