# FINSECURE PAY CAPSTONE - STEP-BY-STEP GUIDE

Windows laptop + Docker Desktop + VS Code + AWS EKS + Docker Hub + Jenkins

```text
GENERAL NOTES
- Use PowerShell for all commands.
- Use curl.exe (plain "curl" is a PowerShell alias for something else).
- PowerShell line continuation is a backtick ( ` ), not a backslash.
- Screenshots: Win+Shift+S.  Screen recording for the demo: Win+Alt+R
  (Game Bar) or OBS.
- Ports used: app 8080, Jenkins 8081, Grafana 3000, Prometheus 9090,
  Alertmanager 9093.
- COST WARNING: EKS control plane is about USD 0.10/hour, plus worker nodes
  and load balancers. Create the cluster only when you are ready to capture
  evidence and delete it afterwards (see CLEANUP at the end).
- Never put passwords, tokens or keys in Git or in screenshots.
```

## STEP 0 - SET UP YOUR LAPTOP (about 30 minutes)

```text
0.1 Docker Desktop
    Settings > General > tick "Use the WSL 2 based engine".
    Run:  docker version     (both Client and Server must appear)

0.2 Install tools (if a winget ID is not found, run: winget search <name>)

    winget install -e --id Git.Git
    winget install -e --id EclipseAdoptium.Temurin.17.JDK
    winget install -e --id Amazon.AWSCLI
    winget install -e --id Kubernetes.kubectl
    winget install -e --id Helm.Helm

    eksctl: run "choco install eksctl" if you have Chocolatey. Otherwise
    download the Windows zip from the eksctl GitHub releases page, unzip it
    and add the folder to your PATH.

    Maven is NOT needed: the Spring project includes mvnw.cmd.

    Close and reopen PowerShell, then verify:
    git --version; java -version; aws --version; kubectl version --client; helm version; eksctl version

0.3 VS Code extensions
    Extension Pack for Java, Spring Boot Extension Pack, Docker, Kubernetes,
    YAML, GitHub Pull Requests.

0.4 Accounts
    - GitHub and Docker Hub (free is fine)
    - AWS: create an IAM USER (not root) with AdministratorAccess, generate
      an access key, then run "aws configure"
      (region ap-southeast-1, output json)
    - SonarCloud (sign in with GitHub)
    - Gmail APP PASSWORD (Google Account > Security > 2-Step Verification >
      App passwords) for email alerts

    Run:  aws sts get-caller-identity


```

## TASK 1 - APPLICATION AND GIT

```text
1.1 Create the project
    In VS Code press Ctrl+Shift+P > "Spring Initializr: Create a Maven
    Project":
      - Spring Boot 3.x (latest stable), Java 17, Packaging Jar
      - Group: com.finsecure   Artifact: finsecure-pay
      - Dependencies: Spring Web, Spring Boot Actuator, Validation,
        Prometheus (Micrometer registry)
    Open the generated folder in VS Code.

1.2 Add the code under src/main/java/com/finsecure/pay/

--- TransactionRequest.java ---
package com.finsecure.pay;
import java.math.BigDecimal;
public record TransactionRequest(String transactionId, String senderAccount,
        String receiverAccount, BigDecimal amount, String currency) {}

--- ValidationResult.java ---
package com.finsecure.pay;
import java.util.List;
public record ValidationResult(boolean valid, List<String> errors) {}

--- ValidationService.java ---
package com.finsecure.pay;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.\*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ValidationService {
    private static final Pattern ACCOUNT = Pattern.compile("\\\\d{10}");
    private final BigDecimal maxAmount;
    private final Set<String> allowedCurrencies;

    public ValidationService(@Value("${MAX\_TXN\_AMOUNT:10000}") BigDecimal maxAmount,
                             @Value("${ALLOWED\_CURRENCIES:SGD,USD,EUR}") String currencies) {
        this.maxAmount = maxAmount;
        this.allowedCurrencies = Arrays.stream(currencies.split(","))
                .map(String::trim).collect(Collectors.toSet());
    }

    public ValidationResult validate(TransactionRequest r) {
        List<String> errors = new ArrayList<>();
        if (r == null) return new ValidationResult(false, List.of("Request body is required"));
        if (r.transactionId() == null || r.transactionId().isBlank())
            errors.add("transactionId is required");
        if (r.senderAccount() == null || !ACCOUNT.matcher(r.senderAccount()).matches())
            errors.add("senderAccount must be 10 digits");
        if (r.receiverAccount() == null || !ACCOUNT.matcher(r.receiverAccount()).matches())
            errors.add("receiverAccount must be 10 digits");
        if (r.senderAccount() != null \&\& r.senderAccount().equals(r.receiverAccount()))
            errors.add("sender and receiver must be different");
        if (r.amount() == null || r.amount().signum() <= 0)
            errors.add("amount must be greater than 0");
        else if (r.amount().compareTo(maxAmount) > 0)
            errors.add("amount exceeds limit of " + maxAmount);
        if (r.currency() == null || !allowedCurrencies.contains(r.currency()))
            errors.add("currency must be one of " + allowedCurrencies);
        return new ValidationResult(errors.isEmpty(), errors);
    }
}

--- TransactionController.java (includes a custom metric for Grafana) ---
package com.finsecure.pay;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.\*;
import java.util.Map;

@RestController
public class TransactionController {
    private final ValidationService service;
    private final MeterRegistry registry;

    public TransactionController(ValidationService service, MeterRegistry registry) {
        this.service = service;
        this.registry = registry;
    }

    @GetMapping("/health")
    public Map<String, String> health() { return Map.of("status", "UP"); }

    @PostMapping("/validate")
    public ResponseEntity<ValidationResult> validate(@RequestBody TransactionRequest request) {
        ValidationResult result = service.validate(request);
        registry.counter("finsecure\_validation\_total", "result", result.valid() ? "pass" : "fail").increment();
        return result.valid() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }
}

--- src/main/resources/application.properties ---
management.endpoints.web.exposure.include=health,info,prometheus
management.endpoint.health.probes.enabled=true
management.endpoint.health.show-details=never

1.3 Unit tests in src/test/java/com/finsecure/pay/

--- ValidationServiceTest.java ---
package com.finsecure.pay;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.\*;

class ValidationServiceTest {
    private final ValidationService svc = new ValidationService(new BigDecimal("10000"), "SGD,USD,EUR");

    private TransactionRequest req(String amt, String cur, String from, String to) {
        return new TransactionRequest("T1", from, to, new BigDecimal(amt), cur);
    }

    @Test void validTransactionPasses() {
        assertTrue(svc.validate(req("100.50", "SGD", "1234567890", "0987654321")).valid());
    }
    @Test void zeroAmountFails() {
        assertFalse(svc.validate(req("0", "SGD", "1234567890", "0987654321")).valid());
    }
    @Test void negativeAmountFails() {
        assertFalse(svc.validate(req("-5", "SGD", "1234567890", "0987654321")).valid());
    }
    @Test void overLimitFails() {
        assertFalse(svc.validate(req("10000.01", "SGD", "1234567890", "0987654321")).valid());
    }
    @Test void unsupportedCurrencyFails() {
        assertFalse(svc.validate(req("100", "XYZ", "1234567890", "0987654321")).valid());
    }
    @Test void sameAccountFails() {
        assertFalse(svc.validate(req("100", "SGD", "1234567890", "1234567890")).valid());
    }
    @Test void badAccountFormatFails() {
        assertFalse(svc.validate(req("100", "SGD", "123", "0987654321")).valid());
    }
}

--- TransactionControllerTest.java ---
package com.finsecure.pay;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.\*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.\*;

@SpringBootTest
@AutoConfigureMockMvc
class TransactionControllerTest {
    @Autowired MockMvc mvc;

    @Test void healthReturnsUp() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("UP"));
    }
    @Test void validTransactionReturns200() throws Exception {
        mvc.perform(post("/validate").contentType(MediaType.APPLICATION\_JSON).content("""
            {"transactionId":"T1","senderAccount":"1234567890","receiverAccount":"0987654321",
             "amount":100,"currency":"SGD"}"""))
           .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));
    }
    @Test void invalidTransactionReturns400() throws Exception {
        mvc.perform(post("/validate").contentType(MediaType.APPLICATION\_JSON).content("""
            {"transactionId":"T2","senderAccount":"1234567890","receiverAccount":"0987654321",
             "amount":-1,"currency":"SGD"}"""))
           .andExpect(status().isBadRequest()).andExpect(jsonPath("$.valid").value(false));
    }
}

1.4 Build, test and run

    .\\mvnw.cmd clean verify
    .\\mvnw.cmd spring-boot:run

    In a second terminal:
    curl.exe http://localhost:8080/health
    Invoke-RestMethod -Method Post -Uri http://localhost:8080/validate -ContentType "application/json" `
      -Body '{"transactionId":"T1","senderAccount":"1234567890","receiverAccount":"0987654321","amount":100,"currency":"SGD"}'

    Also try an invalid body (amount -1). PowerShell will report a 400 error,
    which is the expected result.

1.5 Push to GitHub
    1. Create an empty PUBLIC repo "finsecure-pay" on github.com.
    2. Add a .gitignore with: target/  .vscode/  \*.env  alertmanager-values.yaml
    3. Run:
       git init
       git add .
       git commit -m "Initial FinSecure Pay application"
       git branch -M main
       git remote add origin https://github.com/<your-user>/finsecure-pay.git
       git push -u origin main
       (sign in through the browser prompt)

EVIDENCE: repo page, "mvnw verify" output showing BUILD SUCCESS and test
count, project tree in VS Code, curl outputs.
```

## TASK 2 - DOCKER

```text
2.1 Create "Dockerfile" in the project root

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S app \&\& adduser -S app -G app
USER app
WORKDIR /app
COPY --from=build /app/target/\*.jar app.jar
EXPOSE 8080
ENTRYPOINT \["java","-jar","app.jar"]

    Also create ".dockerignore" containing: target  .git  .vscode

2.2 Build, run and test
    docker build -t <dockerhub-user>/finsecure-pay:1.0.0 .
    docker run -d --name fsp -p 8080:8080 <dockerhub-user>/finsecure-pay:1.0.0
    docker ps
    curl.exe http://localhost:8080/health
    docker logs fsp

2.3 Push
    docker login
    docker push <dockerhub-user>/finsecure-pay:1.0.0
    docker stop fsp; docker rm fsp

    Keep the Docker Hub repository PUBLIC so EKS can pull it without
    credentials. (If private, create an imagePullSecret with
    "kubectl create secret docker-registry".)
    Always use explicit version tags (1.0.0, 1.0.1...), not only "latest";
    rollback depends on them.

EVIDENCE: Dockerfile, "docker images", "docker ps", curl output, Docker Hub
tags page.
```

## TASK 3 - EKS DEPLOYMENT

```text
3.1 Create the cluster. File k8s/cluster.yaml:

apiVersion: eksctl.io/v1alpha5
kind: ClusterConfig
metadata:
  name: finsecure-eks
  region: ap-southeast-1
managedNodeGroups:
  - name: ng-1
    instanceType: t3.medium
    desiredCapacity: 2
    minSize: 2
    maxSize: 3

    eksctl create cluster -f k8s/cluster.yaml
    kubectl get nodes

    Takes 15-20 minutes. Check the context with "kubectl config current-context".
    To reconnect later:
    aws eks update-kubeconfig --name finsecure-eks --region ap-southeast-1




3.2 Manifests in k8s/

--- configmap.yaml ---
apiVersion: v1
kind: ConfigMap
metadata:
  name: finsecure-config
data:
  MAX\_TXN\_AMOUNT: "10000"
  ALLOWED\_CURRENCIES: "SGD,USD,EUR"

--- deployment.yaml ---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: finsecure-pay
  labels: { app: finsecure-pay }
spec:
  replicas: 2
  selector:
    matchLabels: { app: finsecure-pay }
  template:
    metadata:
      labels: { app: finsecure-pay }
    spec:
      containers:
        - name: app
          image: <dockerhub-user>/finsecure-pay:1.0.0
          ports:
            - containerPort: 8080
          envFrom:
            - configMapRef: { name: finsecure-config }
          resources:
            requests: { cpu: 100m, memory: 256Mi }
            limits: { cpu: 500m, memory: 512Mi }
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: 8080 }
            initialDelaySeconds: 20
            periodSeconds: 10
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: 8080 }
            initialDelaySeconds: 40
            periodSeconds: 10

--- service.yaml (label and port name are needed later for monitoring) ---
apiVersion: v1
kind: Service
metadata:
  name: finsecure-pay
  labels: { app: finsecure-pay }
spec:
  type: LoadBalancer
  selector: { app: finsecure-pay }
  ports:
    - name: http
      port: 80
      targetPort: 8080

3.3 Deploy and verify
    kubectl apply -f k8s/configmap.yaml -f k8s/deployment.yaml -f k8s/service.yaml
    kubectl get pods -o wide
    kubectl get svc finsecure-pay -w      (wait for EXTERNAL-IP hostname, Ctrl+C)
    curl.exe http://<EXTERNAL-IP-hostname>/health

    The load balancer DNS can take 2-3 minutes to start resolving.

3.4 Self-healing demo
    kubectl get pods
    kubectl delete pod <one-pod-name>
    kubectl get pods -w

    Screenshot the old pod Terminating and a new pod going from
    ContainerCreating to Running.
    Then run "kubectl describe deployment finsecure-pay" to capture the
    probes and replicas.

EVIDENCE: the three YAML files, pods and service output, browser/curl hitting
the ELB, pod-recreation screenshot.
```

## TASK 4 - JENKINS CI/CD (Jenkins in Docker Desktop)

```text
4.1 Build a Jenkins image with all CLI tools. File jenkins/Dockerfile:

FROM jenkins/jenkins:lts-jdk17
USER root
RUN apt-get update \&\& apt-get install -y docker.io maven unzip curl \&\& rm -rf /var/lib/apt/lists/\*
RUN curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl" \\
 \&\& install -m 0755 kubectl /usr/local/bin/kubectl \&\& rm kubectl
RUN curl -s "https://awscli.amazonaws.com/awscli-exe-linux-x86\_64.zip" -o awscliv2.zip \\
 \&\& unzip -q awscliv2.zip \&\& ./aws/install \&\& rm -rf aws awscliv2.zip
RUN curl -sfL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh | sh -s -- -b /usr/local/bin

    docker build -t jenkins-finsecure ./jenkins
    docker run -d --name jenkins -u root -p 8081:8080 -p 50000:50000 `
      -v jenkins\_home:/var/jenkins\_home -v /var/run/docker.sock:/var/run/docker.sock jenkins-finsecure
    docker exec jenkins cat /var/jenkins\_home/secrets/initialAdminPassword

4.2 Open http://localhost:8081, paste the password, choose "Install
    suggested plugins", create an admin user. Then Manage Jenkins > Plugins >
    Available: make sure Email Extension, Pipeline, JUnit and Credentials
    Binding are installed (most are already included).

4.3 Add credentials (Manage Jenkins > Credentials > System > Global > Add)

    ID                Type                      Value
    ----------------  ------------------------  -------------------------------
    dockerhub-creds   Username with password    Docker Hub user + access token
                                                (Docker Hub > Account Settings >
                                                Security)
    aws-access-key    Secret text               AWS access key ID
    aws-secret-key    Secret text               AWS secret access key
    sonar-token       Secret text               SonarCloud token (Task 5)
    nvd-api-key       Secret text               NVD API key from nvd.nist.gov
                                                (Task 5)

    The cluster creator's IAM user already has access to the cluster. For a
    tighter setup, create a separate "jenkins-deployer" IAM user and add it
    with "eksctl create iamidentitymapping" or an EKS access entry.

4.4 Jenkinsfile in the repo root

pipeline {
  agent any
  environment {
    IMAGE   = "<dockerhub-user>/finsecure-pay"
    TAG     = "1.0.${env.BUILD\_NUMBER}"
    AWS\_DEFAULT\_REGION = "ap-southeast-1"
  }
  stages {
    stage('Checkout') { steps { checkout scm } }
    stage('Build')    { steps { sh 'mvn -B clean compile' } }
    stage('Test') {
      steps { sh 'mvn -B test' }
      post { always { junit 'target/surefire-reports/\*.xml' } }
    }
    stage('Package') { steps { sh 'mvn -B package -DskipTests' } }
    stage('Security Checks') {
      steps {
        withCredentials(\[string(credentialsId: 'sonar-token', variable: 'SONAR\_TOKEN'),
                         string(credentialsId: 'nvd-api-key', variable: 'NVD\_KEY')]) {
          sh '''mvn -B sonar:sonar -Dsonar.host.url=https://sonarcloud.io \\
                -Dsonar.organization=<sonar-org> -Dsonar.projectKey=<sonar-project-key> \\
                -Dsonar.token=$SONAR\_TOKEN -Dsonar.qualitygate.wait=true'''
          sh 'mvn -B org.owasp:dependency-check-maven:check -DfailBuildOnCVSS=7 -DnvdApiKey=$NVD\_KEY'
        }
      }
    }
    stage('Container Build') { steps { sh 'docker build -t $IMAGE:$TAG .' } }
    stage('Image Scan') {
      steps { sh 'trivy image --exit-code 1 --severity HIGH,CRITICAL --ignore-unfixed $IMAGE:$TAG' }
    }
    stage('Push') {
      steps {
        withCredentials(\[usernamePassword(credentialsId: 'dockerhub-creds',
                         usernameVariable: 'DH\_USER', passwordVariable: 'DH\_PASS')]) {
          sh 'echo $DH\_PASS | docker login -u $DH\_USER --password-stdin'
          sh 'docker push $IMAGE:$TAG'
        }
      }
    }
    stage('Deploy') {
      steps {
        withCredentials(\[string(credentialsId: 'aws-access-key', variable: 'AWS\_ACCESS\_KEY\_ID'),
                         string(credentialsId: 'aws-secret-key', variable: 'AWS\_SECRET\_ACCESS\_KEY')]) {
          sh 'aws eks update-kubeconfig --name finsecure-eks'
          sh 'kubectl set image deployment/finsecure-pay app=$IMAGE:$TAG'
          sh 'kubectl rollout status deployment/finsecure-pay --timeout=180s'
        }
      }
    }
  }
  post {
    success { emailext to: '<your-email>', subject: "SUCCESS: ${env.JOB\_NAME} #${env.BUILD\_NUMBER}", body: "Build OK: ${env.BUILD\_URL}" }
    failure { emailext to: '<your-email>', subject: "FAILED: ${env.JOB\_NAME} #${env.BUILD\_NUMBER}", body: "Build failed: ${env.BUILD\_URL}console" }
  }
}

    TIP: For the first run, comment out the Sonar and Dependency-Check
    commands to get a green pipeline, then add them back in Task 5.

4.5 Create the job
    New Item > Pipeline > "Pipeline script from SCM" > Git > your repo URL >
    branch \*/main > Script Path "Jenkinsfile".
    Build Triggers: Poll SCM with  H/2 \* \* \* \*
    (A GitHub webhook needs Jenkins reachable from the internet, e.g. via
    ngrok, so polling is simpler.)

4.6 Email in Jenkins
    Manage Jenkins > System > "Extended E-mail Notification":
    SMTP server smtp.gmail.com, port 587, enable TLS/STARTTLS, add a Gmail
    credential (your address + the app password). Send a test email.

4.7 Demonstrations
    1. SUCCESSFUL RUN: push a small change; screenshot Stage View and
       Console Output.
    2. FAILED TEST: edit one assertion in ValidationServiceTest (e.g. expect
       assertTrue for a zero amount) and push. The Test stage goes red and
       Deploy never runs. Screenshot the pipeline, then "kubectl get pods"
       unchanged. Then run "git revert HEAD" and push.
    3. ROLLOUT OF NEW VERSION: change something visible (e.g. a /version
       endpoint or the health text). After the run:
       kubectl rollout status deployment/finsecure-pay
       kubectl get deployment finsecure-pay -o jsonpath="{.spec.template.spec.containers\[0].image}"
    4. ROLLBACK:
       kubectl rollout history deployment/finsecure-pay
       kubectl rollout undo deployment/finsecure-pay     (or --to-revision=<n>)
       kubectl rollout status deployment/finsecure-pay
       Confirm the image tag went back to the earlier version.
```

## TASK 5 - DEVSECOPS

```text
5.1 SonarCloud (static analysis)
    1. Log in to sonarcloud.io with GitHub, import your repo. Note the
       organization key and project key.
    2. Choose "Analyze with Maven", generate a token, store it as the
       "sonar-token" Jenkins credential.
    3. In the Sonar project, turn OFF Automatic Analysis (Administration >
       Analysis Method) so the Jenkins scan is used.
    4. "-Dsonar.qualitygate.wait=true" in the Jenkinsfile makes a failing
       Quality Gate fail the build.
    Evidence: Sonar dashboard and Quality Gate status.

5.2 OWASP Dependency-Check
    Request a free NVD API key at nvd.nist.gov/developers/request-an-api-key
    and add it as "nvd-api-key". The first run downloads the vulnerability
    database and can take a long time. The report is at
    target/dependency-check-report.html. To keep it, add
    archiveArtifacts 'target/dependency-check-report.html' to the pipeline.
    If a clean build is blocked by an unrelated false positive, upgrade to
    the latest Spring Boot patch version first; only then use a suppression
    file.

5.3 Trivy
    The Image Scan stage is already in the Jenkinsfile. "--ignore-unfixed" is
    deliberate: without it, unfixable OS findings could block your "good"
    build forever. Manual run from PowerShell:
    docker run --rm -v /var/run/docker.sock:/var/run/docker.sock aquasec/trivy image <dockerhub-user>/finsecure-pay:1.0.0

5.4 Controlled finding and remediation (do this on a branch "security-demo")
    1. In pom.xml add a vulnerable dependency:

       <dependency>
         <groupId>org.apache.logging.log4j</groupId>
         <artifactId>log4j-core</artifactId>
         <version>2.14.1</version>
       </dependency>

    2. Push. Dependency-Check fails with a CVSS 10 finding (Log4Shell) and
       the run stops before the container build. Screenshot the blocked
       stage and the report.
    3. REMEDIATE: remove the dependency or upgrade it to a current patched
       version, push, and screenshot the green build.
    4. For the Trivy gate specifically, you can instead change the FROM line
       to an old, pinned base-image tag from Docker Hub's tags page.
    For the branch to run through Jenkins, create a Multibranch Pipeline job
    or temporarily set the job's branch to \*/security-demo.

5.5 Secrets
    - Show the Jenkins Credentials page (values masked) and that the
      Jenkinsfile only references credential IDs.
    - Run: git grep -i -E "password|secret|token|apikey"  and show nothing
      sensitive is found.
    - Show a Kubernetes Secret used by the pod:
      kubectl create secret generic finsecure-secret --from-literal=API\_KEY=demo-not-a-real-key
      Add under the container in deployment.yaml, then re-apply:
          env:
            - name: API\_KEY
              valueFrom:
                secretKeyRef: { name: finsecure-secret, key: API\_KEY }
    - In your document, state that Vault or AWS Secrets Manager would be the
      production choice.
```

## TASK 6 - MONITORING, ALERTING, LOGGING

```text
6.1 Install Prometheus and Grafana (kube-prometheus-stack)
    helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
    helm repo update
    helm install monitoring prometheus-community/kube-prometheus-stack -n monitoring --create-namespace
    kubectl get pods -n monitoring

6.2 Scrape your app. File k8s/servicemonitor.yaml:

apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: finsecure-pay
  namespace: monitoring
  labels: { release: monitoring }
spec:
  namespaceSelector: { matchNames: \[default] }
  selector:
    matchLabels: { app: finsecure-pay }
  endpoints:
    - port: http
      path: /actuator/prometheus
      interval: 15s

    Apply it, then open Prometheus:
    kubectl -n monitoring port-forward svc/monitoring-kube-prometheus-prometheus 9090:9090
    Check Status > Targets: "finsecure-pay" must be UP.
    (If the service name differs, run: kubectl get svc -n monitoring)

6.3 Grafana dashboard
    Get the password:
    kubectl -n monitoring get secret monitoring-grafana -o jsonpath="{.data.admin-password}" | %{ \[Text.Encoding]::UTF8.GetString(\[Convert]::FromBase64String($\_)) }
    kubectl -n monitoring port-forward svc/monitoring-grafana 3000:80
    Log in at http://localhost:3000 (user: admin). Create a dashboard with:
      - HTTP requests/sec:
        sum(rate(http\_server\_requests\_seconds\_count{job="finsecure-pay"}\[1m]))
      - Validation results:
        sum by (result) (rate(finsecure\_validation\_total\[1m]))
      - JVM heap:
        sum(jvm\_memory\_used\_bytes{area="heap"})
      - Available replicas:
        kube\_deployment\_status\_replicas\_available{deployment="finsecure-pay"}
    Generate traffic with a PowerShell loop of Invoke-RestMethod calls
    (include some invalid ones), then screenshot the live dashboard.

6.4 Alert rule. File k8s/alerts.yaml:

apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: finsecure-alerts
  namespace: monitoring
  labels: { release: monitoring }
spec:
  groups:
    - name: finsecure
      rules:
        - alert: FinSecureReplicasLow
          expr: kube\_deployment\_status\_replicas\_available{deployment="finsecure-pay"} < 2
          for: 1m
          labels: { severity: critical }
          annotations:
            summary: "FinSecure Pay has fewer than 2 healthy replicas"

6.5 Email delivery. Create alertmanager-values.yaml OUTSIDE the Git repo (or
    .gitignore it) because it contains the app password:

alertmanager:
  config:
    global:
      smtp\_smarthost: 'smtp.gmail.com:587'
      smtp\_from: '<your-gmail>'
      smtp\_auth\_username: '<your-gmail>'
      smtp\_auth\_password: '<gmail-app-password>'
      smtp\_require\_tls: true
    route:
      receiver: email
      group\_by: \['alertname']
      routes:
        - receiver: 'null'
          matchers: \['alertname = "Watchdog"']
    receivers:
      - name: 'null'
      - name: email
        email\_configs:
          - to: '<your-email>'
            send\_resolved: true

    helm upgrade monitoring prometheus-community/kube-prometheus-stack -n monitoring -f alertmanager-values.yaml
    kubectl apply -f k8s/servicemonitor.yaml -f k8s/alerts.yaml

6.6 Trigger the alert
    kubectl scale deployment finsecure-pay --replicas=1
    Wait about 2 minutes. Screenshot:
      - the alert as Pending then Firing (Prometheus > Alerts, or
        kubectl -n monitoring port-forward svc/monitoring-kube-prometheus-alertmanager 9093:9093)
      - the dashboard dip
      - the EMAIL in your inbox
    Then restore:
    kubectl scale deployment finsecure-pay --replicas=2
    and capture the "resolved" email.
    Also screenshot the Jenkins build email from Task 4 as a second
    notification.

6.7 Logging for troubleshooting (document this workflow, capture each command)
    kubectl logs -l app=finsecure-pay --tail=50
    kubectl logs <pod-name> --previous        (logs from a crashed container)
    kubectl describe pod <pod-name>           (events, probe failures)
    kubectl logs -f deployment/finsecure-pay

    A good troubleshooting example: break the readiness probe path, show the
    pod as not Ready, and use "describe" to find the cause.
    Optional cloud-native extra: install the CloudWatch Observability add-on
    (aws eks create-addon --cluster-name finsecure-eks --addon-name amazon-cloudwatch-observability).
    It needs the CloudWatch policy on the node role; then show the log group
    in the AWS console.

Troubleshooting flow to document: alert > dashboard > logs > kubectl describe.
```

## TASK 7 - AI USE CASE

```text

AI USE CASE (suggested): AI-assisted incident triage and root-cause
summarisation.
  - Inputs: Prometheus alert payload, recent pod logs, kubectl describe
    events, last Jenkins deployment and commit.
  - Output: an LLM produces a plain-language summary, likely causes and
    suggested next steps, sent to the same email/Slack channel as the alert.
  - Governance: human stays in the loop; redact secrets and account data
    before sending to a model; suggestions are advisory (no auto-remediation);
    keep an audit log.

```

## CLEANUP (IMPORTANT FOR AWS COSTS)

```text
    kubectl delete svc finsecure-pay
    helm uninstall monitoring -n monitoring
    eksctl delete cluster -f k8s/cluster.yaml

Check the AWS console afterwards to confirm no load balancers or EC2 nodes
remain. 

```

## SUGGESTED ORDER AND TIME

```text
 1. Tasks 1-2 on your laptop (no AWS cost): one evening.
 2. Create the EKS cluster and do Task 3: 1-2 hours.
 3. Task 4, then Task 5: half a day.
 4. Task 6 and then cleanup.

```

