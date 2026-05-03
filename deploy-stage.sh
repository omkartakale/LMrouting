#!/bin/bash
set -e

echo "=== LM Routing Stage Deployment ==="

# Pull latest code
echo "[1/5] Pulling latest code from stage branch..."
git pull origin stage

# Build jar
echo "[2/5] Building jar (skipping tests)..."
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
echo "App: http://$(curl -s http://169.254.169.254/latest/meta-data/public-ipv4 2>/dev/null || echo 'YOUR_IP'):8080"
