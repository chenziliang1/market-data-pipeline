#!/usr/bin/env bash
# Runs on the EC2 host as root, sent there by ssm-deploy.sh through AWS Systems Manager.
# Placeholders in double underscores are filled in by ssm-deploy.sh before sending.
#
# Blue-green: start the new image in the idle slot, wait until it is healthy, switch nginx to it,
# then stop the old slot. If the new slot never becomes healthy, the old one keeps serving.
set -euo pipefail

APP_DIR=/opt/tradedate
IMAGE='__IMAGE__'

mkdir -p "$APP_DIR/nginx"
cd "$APP_DIR"
if [ ! -f .env ]; then
  echo "$APP_DIR/.env is missing; create it with the database settings first (see deploy/README.md)." >&2
  exit 1
fi
if [ -s .env ] && [ -n "$(tail -c 1 .env)" ]; then echo >> .env; fi

# The API key for the load and admin endpoints is generated on the host and never leaves it.
if ! grep -q '^APP_API_KEY=.' .env; then
  sed -i.bak '/^APP_API_KEY=/d' .env && rm -f .env.bak
  echo "APP_API_KEY=$(openssl rand -hex 32)" >> .env
  echo "Generated APP_API_KEY in $APP_DIR/.env"
fi

set_env() {
  sed -i.bak "/^$1=/d" .env && rm -f .env.bak
  echo "$1=$2" >> .env
}

echo '__COMPOSE_B64__' | base64 -d | gunzip > docker-compose.yml
echo '__NGINX_B64__' | base64 -d | gunzip > nginx/default.conf

ACTIVE=$(cat active-slot 2>/dev/null || echo none)
if [ "$ACTIVE" = blue ]; then NEW=green; NEW_PORT=8082; else NEW=blue; NEW_PORT=8081; fi
NEW_VAR=IMAGE_$(echo "$NEW" | tr '[:lower:]' '[:upper:]')
echo "Active slot: $ACTIVE, deploying $IMAGE to $NEW"

# Only this deploy's commands see the new image. It is written to .env, which docker compose
# commands run by hand also read, once traffic has switched to it; a failed deploy changes nothing.
export "$NEW_VAR=$IMAGE"
if ! grep -q '^IMAGE=.' .env; then
  export IMAGE
fi

# The token is the deploy job's GITHUB_TOKEN with read access to packages; it expires when the job ends.
echo '__REGISTRY_TOKEN__' | docker login ghcr.io -u '__REGISTRY_USER__' --password-stdin
docker compose pull "app-$NEW"
docker logout ghcr.io

docker compose up -d kafka redis
docker compose up -d --no-deps --force-recreate "app-$NEW"

healthy() {
  curl -fsS --max-time 2 "$1" > /dev/null 2>&1
}

# Wait for the new slot itself: the app is up and can reach PostgreSQL and Redis.
for _ in $(seq 1 36); do
  if healthy "http://127.0.0.1:$NEW_PORT/actuator/health"; then
    break
  fi
  sleep 5
done
if ! healthy "http://127.0.0.1:$NEW_PORT/actuator/health"; then
  echo "The new slot ($NEW) did not become healthy within 3 minutes; $ACTIVE keeps serving." >&2
  docker compose logs --tail 80 "app-$NEW" >&2
  docker compose stop "app-$NEW" >&2
  exit 1
fi

# Switch traffic. A reload starts new nginx workers on the new upstream and lets the old workers
# finish the requests they are serving.
echo "upstream app { server app-$NEW:8080; }" > nginx/upstream.conf
if [ -n "$(docker compose ps -q --status running nginx)" ]; then
  docker compose exec -T nginx nginx -t
  docker compose exec -T nginx nginx -s reload
else
  # First blue-green deploy: this also removes the single "app" container of the old layout.
  docker compose up -d --remove-orphans nginx
fi

for _ in $(seq 1 15); do
  if healthy http://127.0.0.1:8080/actuator/health; then
    break
  fi
  sleep 1
done
curl -fsS --max-time 2 http://127.0.0.1:8080/actuator/health
echo
echo "$NEW" > active-slot
set_env "$NEW_VAR" "$IMAGE"
set_env IMAGE "$IMAGE"

# Give requests already sent to the old slot time to complete, then stop it gracefully.
if [ "$ACTIVE" != none ]; then
  sleep 5
  docker compose stop -t 30 "app-$ACTIVE"
fi

echo "Deployed $IMAGE to $NEW"
docker image prune -f > /dev/null
