#!/bin/bash

echo "Checking node health..."
for port in 8081 8082 8083; do
    echo -n "Node on port $port: "
    response=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:$port/api/v1/kv/health)
    if [ "$response" = "200" ]; then
        echo "OK"
    else
        echo "FAILED (HTTP $response)"
    fi
done

#Test writing a value
echo -e "\n Writing test value..."
curl -v -X PUT -H "Content-Type: text/plain" --data-raw "test-value" http://localhost:8081/api/v1/kv/test_key

echo -e "\n waiting 2 seconds for replication.."
sleep 2

echo -e "\n Reading test value from all nodes..."
for port in 8081 8082 8083; do
    echo -n "Node on port $port: "
    value=$(curl -s http://localhost:$port/api/v1/kv/test_key)
    echo "$value"
done
echo -e "\n Cluster test completed"