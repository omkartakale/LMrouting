#!/bin/bash
set -e

echo "=== LM Routing Stage Deployment ==="

# Git config
git config --global http.sslVerify false

# Set remote with credentials baked in
git remote set-url origin "https://omkar.takale%40xpressbees.com:glpat-wnS5w0y9NaPX43gT-U1zEm86MQp1OjM4CA.01.0y0k4r3ef@scm.xbees.in/global-api/lm-routing.git"

# Pull latest code
echo "[1/5] Pulling latest code from stage branch..."
git checkout stage 2>/dev/null || true
git pull origin stage

# Build jar
echo "[2/5] Building jar (skipping tests)..."
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
chmod +x mvnw
./mvnw clean package -DskipTests -q

# Stop and remove old container
echo "[3/5] Stopping old container..."
docker rm -f lm-routing 2>/dev/null || true

# Build Docker image
echo "[4/5] Building Docker image..."
docker build -t lm-routing:stage .

# Run new container
echo "[5/5] Starting new container..."
docker run -d \
  --name lm-routing \
  -p 8080:8082 \
  -e SPRING_PROFILES_ACTIVE=stage \
  --restart unless-stopped \
  lm-routing:stage

echo ""
echo "=== Deployment complete ==="
echo "Waiting for startup..."
sleep 10
docker logs --tail 5 lm-routing
echo ""
echo "App: http://$(curl -s http://169.254.169.254/latest/meta-data/public-ipv4 2>/dev/null || echo '13.127.28.147'):8080"
