#!/usr/bin/env bash
# Runs on the EC2 host as root, sent there by ssm-deploy.sh through AWS Systems Manager.
# Placeholders in double underscores are filled in by ssm-deploy.sh before sending.
set -euo pipefail

APP_DIR=/opt/tradedate
IMAGE='__IMAGE__'

mkdir -p "$APP_DIR"
cd "$APP_DIR"
if [ ! -f .env ]; then
  echo "$APP_DIR/.env is missing; create it with the database settings first (see deploy/README.md)." >&2
  exit 1
fi

# Record the deployed image in .env, so docker compose commands run by hand on the host use it too.
sed -i.bak '/^IMAGE=/d' .env && rm -f .env.bak
if [ -s .env ] && [ -n "$(tail -c 1 .env)" ]; then echo >> .env; fi
echo "IMAGE=$IMAGE" >> .env

echo '__COMPOSE_B64__' | base64 -d | gunzip > docker-compose.yml

# The token is the deploy job's GITHUB_TOKEN with read access to packages; it expires when the job ends.
echo '__REGISTRY_TOKEN__' | docker login ghcr.io -u '__REGISTRY_USER__' --password-stdin
docker compose pull app
docker logout ghcr.io

docker compose up -d --remove-orphans

# Wait for the new container to report healthy: the app is up and can reach PostgreSQL and Redis.
for _ in $(seq 1 36); do
  if curl -fsS http://127.0.0.1:8080/actuator/health; then
    echo
    echo "Deployed $IMAGE"
    docker image prune -f > /dev/null
    exit 0
  fi
  sleep 5
done

echo "The app did not become healthy within 3 minutes." >&2
docker compose ps >&2
docker compose logs --tail 80 app >&2
exit 1
