pipeline {
    agent any

    environment {
        COMPOSE_PROJECT_NAME = 'flowforge'
        BACKEND_CONTAINER = 'flowforge-enterprise-be'
        MYSQL_CONTAINER = 'flowforge-mysql'
        HOST_PORT = '8081'

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

        stage('Check Docker') {
            steps {
                sh '''
                echo "===== Check docker ====="
                docker version
                docker compose version
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

        stage('Deploy from ACR') {
            steps {
                sh '''
                echo "===== Stop old compose services ====="
                docker compose -p $COMPOSE_PROJECT_NAME down || true

                echo "===== Remove old containers that may occupy ports ====="
                docker rm -f flowforge-enterprise-be || true
                docker rm -f flowforge-backend-1 || true
                docker rm -f flowforge-enterprise-be-pipeline-backend-1 || true

                docker rm -f flowforge-mysql || true
                docker rm -f flowforge-mysql-1 || true
                docker rm -f flowforge-enterprise-be-pipeline-mysql-1 || true

                echo "===== Pull latest image from ACR ====="
                docker compose -p $COMPOSE_PROJECT_NAME pull backend

                echo "===== Start services ====="
                docker compose -p $COMPOSE_PROJECT_NAME up -d
                '''
            }
        }

        stage('Verify Containers') {
            steps {
                sh '''
                echo "===== Show running containers ====="
                docker ps --format "table {{.Names}}\\t{{.Status}}\\t{{.Ports}}"

                echo "===== Show backend logs ====="
                docker logs --tail=80 $BACKEND_CONTAINER || true
                '''
            }
        }

        stage('Verify Service') {
            steps {
                sh '''
                echo "===== Verify backend service ====="
                curl -i http://127.0.0.1:$HOST_PORT || true
                '''
            }
        }
    }
}