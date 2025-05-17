#!/bin/bash
echo "Building and starting Raft cluster..."
echo "Stopping Raft cluster..."
docker compose down

echo "cluster stopped"
rm -rf ./data/raft/*
rm -rf ./logs/*

echo "Building images..."
docker compose build

# Start node1 first
echo "Starting node1..."
docker compose up -d node1
sleep 3

# Start node2
echo "Starting node2..."
docker compose up -d node2
sleep 3

# Start node3
echo "Starting node3..."
docker compose up -d node3

echo "Waiting for nodes to initialize..."
sleep 5

echo "Checking cluster status...."
docker compose ps

echo "Cluster is now running. Accessthe nodes at:"
echo "- Node 1: http://localhost:8081/api/v1/kv"
echo "- Node 2: http://localhost:8082/api/v1/kv"
echo "- Node 3: http://localhost:8083/api/v1/kv"