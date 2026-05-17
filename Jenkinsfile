pipeline {
    agent any

    environment {
        KUBECONFIG = '/var/jenkins_home/.kube/config'

        K8S_NAMESPACE = 'flowforge'
        BACKEND_DEPLOYMENT = 'flowforge-backend'
        BACKEND_CONTAINER = 'backend'

        ACR_REGISTRY = 'crpi-vu83mjrcrc7gnl5w.cn-hangzhou.personal.cr.aliyuncs.com'
        ACR_NAMESPACE = 'nus_flowforge'
        IMAGE_REPO = 'flowforge-enterprise-be'

        IMAGE_TAG = "${BUILD_NUMBER}"
        IMAGE_NAME = "${ACR_REGISTRY}/${ACR_NAMESPACE}/${IMAGE_REPO}:${IMAGE_TAG}"
        IMAGE_LATEST = "${ACR_REGISTRY}/${ACR_NAMESPACE}/${IMAGE_REPO}:latest"
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

        stage('Build Image') {
            steps {
                sh '''
                echo "===== Build Docker image ====="
                docker build -t $IMAGE_NAME .

                echo "===== Tag image as latest ====="
                docker tag $IMAGE_NAME $IMAGE_LATEST
                '''
            }
        }

        stage('Login to ACR') {
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
            }
        }

        stage('Push Image to ACR') {
            steps {
                sh '''
                echo "===== Push version image ====="
                docker push $IMAGE_NAME

                echo "===== Push latest image ====="
                docker push $IMAGE_LATEST
                '''
            }
        }

        stage('Deploy to ACK') {
            steps {
                sh '''
                echo "===== Apply namespace ====="
                kubectl apply -f k8s/namespace.yaml

                echo "===== Apply MySQL resources ====="
                kubectl apply -f k8s/mysql.yaml

                echo "===== Apply backend resources ====="
                kubectl apply -f k8s/backend.yaml

                echo "===== Update backend image to current build ====="
                kubectl set image deployment/$BACKEND_DEPLOYMENT \
                  $BACKEND_CONTAINER=$IMAGE_NAME \
                  -n $K8S_NAMESPACE

                echo "===== Wait for backend rollout ====="
                kubectl rollout status deployment/$BACKEND_DEPLOYMENT \
                  -n $K8S_NAMESPACE \
                  --timeout=180s
                '''
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

                echo "===== Backend logs ====="
                kubectl logs -n $K8S_NAMESPACE deployment/$BACKEND_DEPLOYMENT --tail=100 || true
                '''
            }
        }
    }
}