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
    stage('Container Build') {
      steps { sh 'docker build -t $IMAGE:$TAG .' }
    }
    stage('Push') {
      steps {
        withCredentials([usernamePassword(credentialsId: 'dockerhub-creds', usernameVariable: 'DH_USER', passwordVariable: 'DH_PASS')]) {
          sh 'echo "$DH_PASS" | docker login -u "$DH_USER" --password-stdin'
          sh 'docker push $IMAGE:$TAG'
        }
      }
    }
  }
  post {
    always { sh 'docker logout || true' }
  }
}