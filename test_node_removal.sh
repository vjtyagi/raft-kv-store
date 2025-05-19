#!/bin/bash
#Configuration
NODE1_PORT=8081
NODE2_PORT=8082
NODE3_PORT=8083
BASE_URL="http://localhost"

start_initial_cluster() {
    echo "==== Starting initial 3-Node cluster ==="
    echo "Stopping any existing cluster...."
    docker compose down

    echo "cleaning up old data..."
    rm -rf ./data/raft/*
    rm -rf ./logs/*

    echo "Building images..."
    docker compose build

    echo "Starting node1..."
    docker compose up -d node1
    
    echo "Starting node2..."
    docker compose up -d node2
    
    echo "Starting node3..."
    docker compose up -d node3

    echo "Waiting for nodes to initialize..."
    sleep 5

    echo "Initial 3-node cluster started"
    docker compose ps
}
check_node_health(){
    local port=$1
    local node_name=$2
    echo "Checking health of $node_name..."
    if response=$(curl -s --max-time 5 -w "%{http_code}" -o /dev/null "$BASE_URL:$port/debug/state" 2>/dev/null); then
        if [ "$response" == "200" ]; then
            echo "$node_name is healthy"
            return 0
        else
            echo "$node_name is not responding (HTTP:$response)"
            return 1
        fi
    else 
        echo "$node_name is not responding"
        return 1
    fi
}

wait_for_nodes(){
    echo "Waiting for nodes to be ready"
    local max_attempts=30
    local attempt=1
    while [ $attempt -le $max_attempts ]; do
        echo "Attempt $attempt/$max_attempts..."
        if check_node_health $NODE1_PORT "node1" && check_node_health $NODE2_PORT "node2" && check_node_health $NODE3_PORT "node3"; then
            echo "All nodes are ready"
            return 0
        fi

        echo "Waiting for 2 seconds before next check..."
        sleep 2
        ((attempt++))
    done
    echo "Timeout waiting for nodes to be ready"
    return 1
}

find_leader(){
    echo "=== Finding Current Leader ==="
    local leader_found=""
    local max_attempts=15
    local attempt=1

    while [ $attempt -le $max_attempts ]; do
        echo "Looking for leader (attempt $attempt/$max_attempts)..."
        for port in $NODE1_PORT $NODE2_PORT $NODE3_PORT; do
            local node_id=$(get_node_id_from_port $port)
            local debug_info=$(curl -s $BASE_URL:$port/debug/state 2>/dev/null)
            if [ $? -eq 0 ] && [ ! -z "$debug_info" ]; then
                local is_leader=$(echo $debug_info | jq -r '.clusterState.isLeader' 2>/dev/null)
                local current_term=$(echo $debug_info | jq -r '.clusterState.currentTerm' 2>/dev/null)

                if [ "$is_leader" == "true" ]; then
                    leader_found=$node_id
                    echo "Leader Found: $node_id (port $port)"
                    echo " Term: $current_term"
                    echo " State: $(echo $debug_info | jq -r '.clusterState.currentState')"
                    echo " Peers: $(echo $debug_info | jq -r '.clusterState.peers | join(",")')"

                    CURRENT_LEADER=$leader_found
                    LEADER_PORT=$port
                    return 0
                fi
            fi
        done
        echo "No leader yet, waiting 2 seconds..."
        sleep 2
        ((attempt++))
    done
    echo "Error: No Leader found after $max_attempts attempts!"
    return 1
}



verify_cluster_health(){
    echo "=== Verifying cluster health ==="

    #Wait for nodes to be ready
    wait_for_nodes || return 1

    #Find and announce current leader
    find_leader || return 1

    echo "Verifying peer relationships..."
    local node1_peers=$(curl -s $BASE_URL:$NODE1_PORT/debug/state | jq -r '.clusterState.peers[]' 2>/dev/null | tr '\n' ' ')
    local node2_peers=$(curl -s $BASE_URL:$NODE2_PORT/debug/state | jq -r '.clusterState.peers[]' 2>/dev/null | tr '\n' ' ')
    local node3_peers=$(curl -s $BASE_URL:$NODE3_PORT/debug/state | jq -r '.clusterState.peers[]' 2>/dev/null | tr '\n' ' ')

    echo "Node1 peers: [$node1_peers]"
    echo "Node2 peers: [$node2_peers]"
    echo "Node3 peers: [$node3_peers]"

    #Check if nodes have same term
    local node1_term=$(curl -s $BASE_URL:$NODE1_PORT/debug/state | jq -r '.clusterState.currentTerm')
    local node2_term=$(curl -s $BASE_URL:$NODE2_PORT/debug/state | jq -r '.clusterState.currentTerm')
    local node3_term=$(curl -s $BASE_URL:$NODE3_PORT/debug/state | jq -r '.clusterState.currentTerm')
    
    echo "Terms: node1=$node1_term, node2=$node2_term, node3=$node3_term"
    echo "Cluster health verification complete"

    return 0
}

get_node_id_from_port() {
    case $1 in
        $NODE1_PORT) echo "node1";;
        $NODE2_PORT) echo "node2";;
        $NODE3_PORT) echo "node3";;
        *) echo "unknown" ;;
    esac
}

get_port_from_node_id(){
    case $1 in 
        "node1") echo $NODE1_PORT ;;
        "node2") echo $NODE2_PORT ;;
        "node3") echo $NODE3_PORT ;;
        *) echo "0";;
    esac
}

execute_put() {
    local key=$1
    local value=$2
    local port=${3:-$LEADER_PORT}
    
    echo "PUT $key = $value (via port $port)"
    local response=$(curl -s -w "%{http_code}" -o /tmp/put_response \
        -X PUT \
        -H "Content-Type: text/plain" \
        -d "$value" \
        "$BASE_URL:$port/api/v1/kv/$key")
    
    if [ "$response" = "200" ]; then
        echo "✓ PUT $key = $value successful"
        return 0
    else
        echo "✗ PUT $key = $value failed (HTTP: $response)"
        cat /tmp/put_response 2>/dev/null
        return 1
    fi
}

execute_get(){
    local key=$1
    local port=${2:-$LEADER_PORT}

    echo "GET $key (via port $port)"
    local response=$(curl -s -w "%{http_code}" -o /tmp/get_response \
    "$BASE_URL:$port/api/v1/kv/$key")

    if [ "$response" = "200" ]; then
        local value=$(cat /tmp/get_response)
        echo "GET $key=$value"
        return 0
    else
        echo "GET $key failed (HTTP: $response)"
        cat /tmp/get_response 2>/dev/null
        return 1
    fi
}

execute_initial_operations(){
    echo "=== Executing initial operations ==="
    echo "Current Leader: $CURRENT_LEADER (port $LEADER_PORT)"

    echo "Performing PUT operations"
    execute_put "key1" "value1" || return 1
    execute_put "key2" "value2" || return 1
    execute_put "key3" "value3" || return 1
    execute_put "key4" "value4" || return 1
    execute_put "counter" "0" || return 1

    #Update an existing key
    execute_put "counter" "1" || return 1

    echo "Waiting for operations to be committed"
    sleep 3

    echo "Verifying with Get operations"
    execute_get "key1" || return 1
    execute_get "key2" || return 1
    execute_get "key3" || return 1
    execute_get "key4" || return 1
    execute_get "counter" || return 1

    echo "Testing read from other nodes for consistency"
    # Try GET from non-leader nodes
    for port in $NODE1_PORT $NODE2_PORT $NODE3_PORT; do
        if [ "$port" != "$LEADER_PORT" ]; then
            execute_get "key1" $port || return 1
        fi
    done

    echo "Initial operations completed successfully"
    return 0
}

simulate_node_failure(){
    #choose a node that is not the leader to fail
    if [ -z "$NODE_TO_FAIL" ]; then
        #pick a follower to fail
        if [ "$CURRENT_LEADER" = "node1" ]; then
            NODE_TO_FAIL="node3"
        elif [ "$CURRENT_LEADER" = "node2" ]; then
            NODE_TO_FAIL="node3"
        else
            NODE_TO_FAIL="node2"
        fi
    fi

    FAILED_NODE_PORT=$(get_port_from_node_id $NODE_TO_FAIL)
    echo "=== Simulating failure of $NODE_TO_FAIL ===="
    echo "Stopping $NODE_TO_FAIL container..."
    docker compose stop $NODE_TO_FAIL

    echo "Verifying $NODE_TO_FAIL is down"
    if check_node_health $FAILED_NODE_PORT $NODE_TO_FAIL; then
        echo "Error: $NODE_TO_FAIL is still responding"
        return 1
    fi
    echo "$NODE_TO_FAIL sucessfully stopped"
    return 0
}

monitor_failure_detection(){
    echo "=== Monitoring automatic failure detection ==="
    echo "Waiting for leader to detect $NODE_TO_FAIL failure"

    #Monitor configuration changes indicating node removal
    local max_wait=120 #2 minutes max
    local elapsed=0
    local node_removed=false

    while [ $elapsed -lt $max_wait ]; do
        echo "Checking if $NODE_TO_FAIL has been detected as failed"
        #check if leader's peer list still contains the failed node
        local leader_debug=$(curl -s $BASE_URL:$LEADER_PORT/debug/state 2>/dev/null)
        if [ $? -eq 0 ] && [ ! -z "$leader_debug" ]; then
            local leader_peers=$(echo "$leader_debug" | jq -r '.clusterState.peers[]' 2>/dev/null | tr '\n' ' ')
            # check for joint consensus
            local in_joint_consensus=$(echo "$leader_debug" | jq -r '.nodeState.inJointConsensus' 2>/dev/null || echo "unknown")

            echo "Leader Peers: $leader_peers"
            echo "In Joint Consensus : $in_joint_consensus"

            if echo "$leader_peers" | grep -q "$NODE_TO_FAIL"; then
                echo "$NODE_TO_FAIL still in peer list"
            else
                echo "$NODE_TO_FAIL no longer in peer list"
                node_removed=true
                break
            fi
        else
            echo "Could not get debug info from the leader"
        fi
        sleep 2
    done

    if [ "$node_removed" = true ]; then
        echo "Success: $NODE_TO_FAIL was automatically removed from the cluster"
        #Verify cluster is still functional
        execute_put "after_failure" "still_working" || {
            echo "Error: cluster operations failing after node removal"
            return 1
        }
        execute_get "after_failure" || {
            echo "Error: canot read from cluster after node removal"
            return 1
        }
        echo "Cluster remains operational after $NODE_TO_FAIL failure and removal"
        return 0
    else
        echo "Timeout: $NODE_TO_FAIL was not automatically removed after $max_wait seconds"
        return 1
    fi

}

main(){
    start_initial_cluster || { echo "Failed to start cluster"; exit 1;}
    verify_cluster_health || { echo "Cluster health check failed"; exit 1; }
    execute_initial_operations || { echo "Initial operations failed"; exit 1;}
    simulate_node_failure || { echo "Failed to simulate node failure"; exit 1; }
    monitor_failure_detection || { echo "Failed to detect node removal"; exit 1; }

    
}



main