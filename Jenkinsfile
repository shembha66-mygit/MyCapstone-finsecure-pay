pipeline {
  agent any
  options { disableConcurrentBuilds() }
  environment {
    IMAGE = 'shembha66/finsecure-pay'
    TAG   = "1.0.${env.BUILD_NUMBER}"
  }
  stages {
    stage('Checkout') {
      steps { checkout scm }
    }
    stage('Build') {
      steps { sh 'mvn -B clean compile' }
    }
    stage('Test') {
      steps { sh 'mvn -B test' }
      post { always { junit 'target/surefire-reports/*.xml' } }
    }
    stage('Package') {
      steps {
        sh 'mvn -B package -DskipTests'
        archiveArtifacts artifacts: 'target/*.jar', fingerprint: true
      }
    }
    stage('Code Quality') {
      steps {
        withCredentials([string(credentialsId: 'sonar-token', variable: 'SONAR_TOKEN')]) {
          sh 'mvn -B org.sonarsource.scanner.maven:sonar-maven-plugin:sonar -Dsonar.host.url=https://sonarcloud.io -Dsonar.organization=shembha66-mygit -Dsonar.projectKey=shembha66-mygit_MyCapstone-finsecure-pay -Dsonar.token=$SONAR_TOKEN'
        }
      }
    }
    stage('Dependency Scan') {
      steps {
        withCredentials([string(credentialsId: 'nvd-api-key', variable: 'NVD_KEY')]) {
          sh 'mvn -B org.owasp:dependency-check-maven:check -DfailBuildOnCVSS=7 -DnvdApiKey=$NVD_KEY -DdataDirectory=/var/jenkins_home/dependency-check-data'
        }
      }
    }
    stage('Container Build') {
      steps { sh 'docker build -t $IMAGE:$TAG .' }
    }
    stage('Image Scan') {
      steps {
        sh 'trivy image --no-progress --ignore-unfixed --severity HIGH,CRITICAL --format table --output trivy-report.txt --exit-code 0 $IMAGE:$TAG'
        sh 'trivy image --no-progress --ignore-unfixed --severity HIGH,CRITICAL --exit-code 1 $IMAGE:$TAG'
      }
    }
    stage('Push') {
      steps {
        withCredentials([usernamePassword(credentialsId: 'dockerhub-creds', usernameVariable: 'DH_USER', passwordVariable: 'DH_PASS')]) {
          sh 'echo "$DH_PASS" | docker login -u "$DH_USER" --password-stdin'
          sh 'docker push $IMAGE:$TAG'
        }
      }
    }
    stage('Deploy') {
      steps {
        withCredentials([string(credentialsId: 'aws-access-key', variable: 'AWS_ACCESS_KEY_ID'),
                         string(credentialsId: 'aws-secret-key', variable: 'AWS_SECRET_ACCESS_KEY')]) {
          sh 'aws eks update-kubeconfig --name finsecure-eks --region ap-southeast-1'
          sh 'kubectl set image deployment/finsecure-pay "*=$IMAGE:$TAG"'
          sh 'kubectl rollout status deployment/finsecure-pay --timeout=180s'
          sh 'kubectl get pods -o wide'
        }
      }
    }
  }
  post {
    always {
      archiveArtifacts artifacts: 'target/dependency-check-report.*, trivy-report.txt', allowEmptyArchive: true
      sh 'docker logout || true'
    }
    success {
      emailext to: '$DEFAULT_RECIPIENTS',
               subject: "SUCCESS: ${env.JOB_NAME} #${env.BUILD_NUMBER}",
               body: "Build ${env.BUILD_NUMBER} passed all stages and was deployed as ${env.IMAGE}:${env.TAG}.\nDetails: ${env.BUILD_URL}"
    }
    failure {
      emailext to: '$DEFAULT_RECIPIENTS',
               subject: "FAILED: ${env.JOB_NAME} #${env.BUILD_NUMBER}",
               body: "Build ${env.BUILD_NUMBER} failed. Check the console output: ${env.BUILD_URL}console"
    }
  }
}