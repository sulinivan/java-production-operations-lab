#!/bin/bash
# Exit immediately on error
set -e

# Interactive warning prompt unless forced
if [[ "$1" != "-f" && "$1" != "--force" ]]; then
    echo "====================================================================="
    echo "WARNING: This will permanently destroy all staging data and volumes!"
    echo "====================================================================="
    read -p "Are you absolutely sure you want to proceed? [y/N]: " response
    if [[ ! "$response" =~ ^[yY]$ ]]; then
        echo "Staging environment reset cancelled."
        exit 0
    fi
fi

if [ ! -f "tests/.env.staging" ]; then
    echo "ERROR: tests/.env.staging not found. Copy tests/.env.staging.example to"
    echo "tests/.env.staging and fill in real staging secrets first."
    exit 1
fi

echo "Stopping staging containers and wiping volumes..."
docker compose -f docker-compose.yml -f docker-compose.staging.yml --env-file tests/.env.staging down -v

echo "Rebuilding and starting staging containers with production-like resource limits..."
docker compose -f docker-compose.yml -f docker-compose.staging.yml --env-file tests/.env.staging up -d --build

echo "Staging environment successfully reset and started."
