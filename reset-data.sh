#!/bin/bash
echo "Stopping Raft cluster..."
docker-compose down

echo "Removing volumes..."
docker volume rm ${docker volume ls -q | grep raft-data-node} || echo "No volumes to remove"

echo "Cluster data reset. Restart with ./start-cluster.sh