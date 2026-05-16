pipeline {
    agent any

    environment {
        APP_NAME = 'flowforge-enterprise-be'
        HOST_PORT = '8081'
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

        stage('Build and Start Services') {
            steps {
                sh '''
                echo "===== Stop old services ====="
                docker compose -p flowforge down || true

                echo "===== Remove old standalone containers if exists ====="
                docker rm -f flowforge-enterprise-be || true
                docker rm -f flowforge-mysql || true
                docker rm -f flowforge-enterprise-be-pipeline-mysql-1 || true

                echo "===== Build and start services ====="
                docker compose -p flowforge up -d --build
                '''
            }
        }

        stage('Verify Containers') {
            steps {
                sh '''
                echo "===== Show running containers ====="
                docker ps

                echo "===== Show backend logs ====="
                docker logs --tail=80 flowforge-enterprise-be || true
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