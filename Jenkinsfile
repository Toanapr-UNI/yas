pipeline {
    agent any

    options {
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 1, unit: 'HOURS')
        disableConcurrentBuilds()
    }

    environment {
        JAVA_HOME = '/opt/jdk25'
        PATH = "${JAVA_HOME}/bin:${env.PATH}"
        // Populated from the root pom.xml during initialization to avoid drift
        ALL_SERVICES = ""
        // Required for Testcontainers in a Docker-in-Docker setup to route to the host gateway
        TESTCONTAINERS_HOST_OVERRIDE = ""
    }

    stages {

        // ============ CHECKOUT ============
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        // ============ INITIALIZE ============
        stage('Initialize') {
            steps {
                script {
                    env.GIT_COMMIT_MSG = sh(
                        script: "git log -1 --pretty=%B",
                        returnStdout: true
                    ).trim()

                    env.BUILD_TIMESTAMP = sh(
                        script: "date '+%Y%m%d_%H%M%S'",
                        returnStdout: true
                    ).trim()

                    // Use shell instead of XmlSlurper to avoid script security issues
                    def pomContent = readFile('pom.xml')
                    env.ALL_SERVICES = sh(
                        script: "grep -oP '(?<=<module>)[^<]+' <<'EOF'\n${pomContent}\nEOF | tr '\\n' ' '",
                        returnStdout: true
                    ).trim()

                    // Dynamically retrieve the Docker host gateway IP for Testcontainers routing
                    env.TESTCONTAINERS_HOST_OVERRIDE = sh(
                        script: 'ip route | awk \'/default/ { print $3; exit }\'',
                        returnStdout: true
                    ).trim()

                    echo """
╔════════════════════════════════════════╗
║  YAS Project - CI/CD Pipeline Started  ║
╚════════════════════════════════════════╝

Branch: ${env.BRANCH_NAME}
Commit: ${env.GIT_COMMIT}
Message: ${env.GIT_COMMIT_MSG}
Build: #${env.BUILD_NUMBER}
Time: ${env.BUILD_TIMESTAMP}
"""
                }
            }
        }

        // ============ DETECT CHANGES ============
        stage('Detect Changed Services') {
            steps {
                script {
                    sh '''
                        echo "Detecting changed services..."

                        SERVICES="cart customer delivery inventory location media order payment payment-paypal product promotion rating recommendation search sampledata tax backoffice-bff storefront-bff common-library"

                        if [ "$BRANCH_NAME" = "main" ]; then
                            echo "CHANGED_SERVICES=all" > build.properties
                        else
                            git fetch origin main || true

                            # Use diff-filter to exclude deleted files and avoid errors
                            CHANGED_FILES="$(git diff --name-only origin/main...HEAD --diff-filter=ACMRT || true)"
                            CHANGED_SERVICES=""
                            NON_SERVICE_CHANGE="false"

                            for SERVICE in $SERVICES; do
                                # Check if any changed file starts with the service path
                                if echo "$CHANGED_FILES" | grep -q "^$SERVICE/"; then
                                    CHANGED_SERVICES="$CHANGED_SERVICES,$SERVICE"
                                fi
                            done
                            
                            # Clean up leading comma
                            CHANGED_SERVICES=$(echo "$CHANGED_SERVICES" | sed 's/^,//')

                            if [ -n "$CHANGED_FILES" ]; then
                                while IFS= read -r FILE; do
                                    [ -z "$FILE" ] && continue
                                    
                                    # Ignore global configuration files from triggering 'all' build
                                    if [ "$FILE" = "Jenkinsfile" ] || [ "$FILE" = "pom.xml" ] || echo "$FILE" | grep -q "^\\.github/"; then
                                        continue
                                    fi

                                    IS_SERVICE_FILE="false"
                                    for SERVICE in $SERVICES; do
                                        if echo "$FILE" | grep -q "^$SERVICE/"; then
                                            IS_SERVICE_FILE="true"
                                            break
                                        fi
                                    done

                                    if [ "$IS_SERVICE_FILE" = "false" ]; then
                                        NON_SERVICE_CHANGE="true"
                                        break
                                    fi
                                done <<EOF
$CHANGED_FILES
EOF
                            fi

                            if [ "$NON_SERVICE_CHANGE" = "true" ]; then
                                echo "CHANGED_SERVICES=all" > build.properties
                            elif [ -z "$CHANGED_SERVICES" ]; then
                                echo "CHANGED_SERVICES=none" > build.properties
                            else
                                echo "CHANGED_SERVICES=$CHANGED_SERVICES" > build.properties
                            fi
                        fi

                        cat build.properties
                    '''

                    env.CHANGED_SERVICES = sh(
                        script: "grep '^CHANGED_SERVICES=' build.properties | cut -d'=' -f2-",
                        returnStdout: true
                    ).trim()

                    echo "Changed services: ${env.CHANGED_SERVICES}"
                }
            }
        }

        // ============ DOCKER PREFLIGHT ============
        stage('Verify Docker for Testcontainers') {
            when {
                expression { (env.CHANGED_SERVICES ?: 'none') != 'none' }
            }
            steps {
                sh '''
                    echo "Verifying Docker access for Testcontainers..."

                    if ! command -v docker >/dev/null 2>&1; then
                        echo "ERROR: docker CLI is not available on this Jenkins agent."
                        echo "Install Docker on the agent or run this pipeline on an agent image that includes Docker."
                        exit 1
                    fi

                    if ! docker info >/dev/null 2>&1; then
                        echo "ERROR: Jenkins cannot access the Docker daemon."
                        echo "Testcontainers requires Docker to start PostgreSQL and Keycloak containers."
                        echo ""
                        echo "Fix the Jenkins agent configuration:"
                        echo "  - If Jenkins runs on a VM/bare-metal agent: add the Jenkins user to the docker group and restart the agent."
                        echo "  - If Jenkins runs inside a container: mount /var/run/docker.sock and the docker CLI into the agent container."
                        echo "  - If using Docker-in-Docker: expose a valid DOCKER_HOST and make sure the daemon is reachable."
                        exit 1
                    fi

                    echo "Docker is reachable."

                    if [ -n "${TESTCONTAINERS_HOST_OVERRIDE:-}" ]; then
                        echo "TESTCONTAINERS_HOST_OVERRIDE=$TESTCONTAINERS_HOST_OVERRIDE"
                    else
                        echo "TESTCONTAINERS_HOST_OVERRIDE is empty; Testcontainers will auto-detect the host."
                    fi

                    echo "Checking required Testcontainers images..."
                    docker image inspect postgres:16 >/dev/null 2>&1 || docker pull postgres:16
                    docker image inspect quay.io/keycloak/keycloak:26.0 >/dev/null 2>&1 || docker pull quay.io/keycloak/keycloak:26.0
                '''
            }
        }

        // ============ BUILD ============
        stage('Build Services') {
            when {
                expression { (env.CHANGED_SERVICES ?: 'none') != 'none' }
            }
            steps {
                script {
                    sh '''
                        echo "Building services..."

                        if [ "$CHANGED_SERVICES" = "all" ]; then
                            # Use the core module wrapper since root doesn't have one
                            if [ -f "cart/mvnw" ]; then
                                chmod +x cart/mvnw
                                cd cart && ./mvnw -f ../pom.xml clean install -DskipTests
                            else
                                echo "ERROR: Maven wrapper not found in cart/ folder"
                                exit 1
                            fi
                        else
                            if [ -f "cart/mvnw" ]; then
                                echo "Building $CHANGED_SERVICES along with dependencies..."
                                chmod +x cart/mvnw
                                cd cart && ./mvnw -f ../pom.xml clean install -pl "$CHANGED_SERVICES" -am -DskipTests
                            else
                                echo "Building $CHANGED_SERVICES with root mvn (fallback)"
                                mvn clean install -pl "$CHANGED_SERVICES" -am -DskipTests || echo "mvn not in path"
                            fi
                        fi
                    '''
                }
            }
        }

        // ============ TEST ============
        stage('Run Tests') {
            when {
                expression { (env.CHANGED_SERVICES ?: 'none') != 'none' }
            }
            steps {
                script {
                    sh '''
                        echo "Running tests..."

                        TEST_FAILURES=0

                        if [ "$CHANGED_SERVICES" = "all" ]; then
                            if [ -f "cart/mvnw" ]; then
                                chmod +x cart/mvnw
                                set +e
                                (cd cart && ./mvnw -f ../pom.xml verify -fae -DfailIfNoTests=false)
                                RC=$?
                                set -e
                                if [ $RC -ne 0 ]; then
                                    TEST_FAILURES=1
                                fi
                            else
                                echo "ERROR: Maven wrapper not found in cart/ folder"
                                exit 1
                            fi
                        else
                            if [ -f "cart/mvnw" ]; then
                                echo "Testing $CHANGED_SERVICES..."
                                chmod +x cart/mvnw
                                set +e
                                (cd cart && ./mvnw -f ../pom.xml verify -pl "$CHANGED_SERVICES" -am -fae -DfailIfNoTests=false)
                                RC=$?
                                set -e
                                if [ $RC -ne 0 ]; then
                                    TEST_FAILURES=1
                                    echo "Tests failed for one or more services, continuing..."
                                fi
                            else
                                echo "Testing $CHANGED_SERVICES with root mvn (fallback)"
                                set +e
                                mvn verify -pl "$CHANGED_SERVICES" -am -fae -DfailIfNoTests=false
                                RC=$?
                                set -e
                                if [ $RC -ne 0 ]; then
                                    TEST_FAILURES=1
                                    echo "mvn verify failed, continuing..."
                                fi
                            fi
                        fi

                        echo "TEST_FAILURES=$TEST_FAILURES" > test-status.properties
                    '''

                    env.TEST_FAILURES = sh(
                        script: "grep '^TEST_FAILURES=' test-status.properties | cut -d'=' -f2-",
                        returnStdout: true
                    ).trim()

                    if (env.TEST_FAILURES == '1') {
                        currentBuild.result = 'UNSTABLE'
                        echo 'One or more test executions failed. Continuing to collect reports and run coverage checks.'
                    }
                }
            }
            post {
                always {
                    junit testResults: '**/target/surefire-reports/*.xml, **/target/failsafe-reports/*.xml', allowEmptyResults: true
                }
            }
        }

        // ============ COVERAGE CHECK ============
        stage('Check Coverage') {
            when {
                expression { (env.CHANGED_SERVICES ?: 'none') != 'none' }
            }
            steps {
                script {
                    echo "Checking aggregate coverage (threshold: 70%)..."
                    
                    // This logic parses target/site/jacoco/jacoco.xml after Maven verify
                    // In a monorepo, we'll aggregate or check service-by-service
                    sh '''
                        # Simple script to aggregate coverage from all changed services
                        TOTAL_COVERED=0
                        TOTAL_MISSED=0
                        
                        SERVICES_TO_CHECK="$CHANGED_SERVICES"
                        if [ "$CHANGED_SERVICES" = "all" ]; then
                            SERVICES_TO_CHECK="$ALL_SERVICES"
                            if [ -z "$SERVICES_TO_CHECK" ]; then
                                # Fallback in case ALL_SERVICES couldn't be initialized
                                SERVICES_TO_CHECK="cart customer delivery inventory location media order payment payment-paypal product promotion rating recommendation search sampledata tax backoffice-bff storefront-bff common-library"
                            fi
                        fi

                        for SERVICE in $SERVICES_TO_CHECK; do
                            REPORT="$SERVICE/target/site/jacoco/jacoco.xml"
                            if [ -f "$REPORT" ]; then
                                COVERAGE_VALUES=$(python3 - "$REPORT" <<'PY'
import sys
import xml.etree.ElementTree as ET

report = sys.argv[1]
covered = 0
missed = 0

tree = ET.parse(report)
for counter in tree.iter('counter'):
    if counter.get('type') == 'LINE':
        covered += int(counter.get('covered', '0'))
        missed += int(counter.get('missed', '0'))

print(f"{covered} {missed}")
PY
)
                                COVERED=$(printf '%s\n' "$COVERAGE_VALUES" | awk '{print $1}')
                                MISSED=$(printf '%s\n' "$COVERAGE_VALUES" | awk '{print $2}')

                                TOTAL_COVERED=$((TOTAL_COVERED + COVERED))
                                TOTAL_MISSED=$((TOTAL_MISSED + MISSED))
                                echo "Service $SERVICE: $COVERED covered, $MISSED missed"
                            fi
                        done

                        if [ $((TOTAL_COVERED + TOTAL_MISSED)) -eq 0 ]; then
                            echo "FAILED: No coverage data found."
                            exit 1
                        fi

                        # Calculate percentage
                        TOTAL=$((TOTAL_COVERED + TOTAL_MISSED))
                        PERCENTAGE=$((TOTAL_COVERED * 100 / TOTAL))
                        
                        echo "════════════════════════════════════"
                        echo "Final Aggregate Coverage: $PERCENTAGE%"
                        echo "════════════════════════════════════"

                        if [ $PERCENTAGE -lt 70 ]; then
                            echo "FAILED: Coverage below 70% threshold!"
                            exit 1
                        fi
                        echo "SUCCESS: Coverage above threshold."
                    '''
                }
            }
        }

        // ============ SONAR (SIMPLIFIED) ============
        stage('SonarQube Scan') {
            steps {
                echo "SonarQube scan placeholder (configure later if needed)"
            }
        }

        // ============ REPORT ============
        stage('Publish Reports') {
            when {
                expression { (env.CHANGED_SERVICES ?: 'none') != 'none' }
            }
            steps {
                script {
                    def services = []
                    def changedServices = (env.CHANGED_SERVICES ?: '').trim()
                    def allServices = (env.ALL_SERVICES ?: '').trim()

                    if (changedServices == 'all') {
                        services = allServices ? allServices.split(' ') : []
                    } else {
                        services = changedServices ? changedServices.split(',') : []
                    }
                    
                    services.collect { it.trim() }.findAll { it }.each { service ->
                        def reportDir = "${service}/target/site/jacoco"
                        if (fileExists("${reportDir}/index.html")) {
                            publishHTML([
                                reportDir: reportDir,
                                reportFiles: 'index.html',
                                reportName: "Coverage Report - ${service}",
                                allowMissing: true,
                                alwaysLinkToLastBuild: true,
                                keepAll: true
                            ])
                        }
                    }
                }

                archiveArtifacts artifacts: '**/target/*.jar', allowEmptyArchive: true
            }
        }

        // ============ FINAL TEST GATE ============
        stage('Enforce Test Gate') {
            when {
                expression { (env.CHANGED_SERVICES ?: 'none') != 'none' }
            }
            steps {
                script {
                    if (env.TEST_FAILURES == '1') {
                        error('Test gate failed: one or more services have failing tests.')
                    }
                }
            }
        }
    }

    post {
        always {
            cleanWs()
        }

        success {
            echo "Pipeline SUCCESS"
        }

        unstable {
            echo "Pipeline UNSTABLE"
        }

        failure {
            echo "Pipeline FAILED"
        }
    }
}
