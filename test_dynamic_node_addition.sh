#!/bin/bash

#Configuration
NODE1_PORT=8081
NODE2_PORT=8082
NODE3_PORT=8083
BASE_URL="http://localhost"


start_initial_cluster() {
    echo "==== Starting initial 2-Node cluster ==="
    echo "Stopping any existing cluster...."
    docker compose -f docker-compose-addition.yml down

    echo "cleaning up old data..."
    rm -rf ./data/raft/*
    rm -rf ./logs/*

    echo "Building images..."
    docker compose -f docker-compose-addition.yml build

    echo "Starting node1..."
    docker compose -f docker-compose-addition.yml up -d node1
    docker compose -f docker-compose-addition.yml up -d node2

    echo "Waiting for nodes to initialize..."
    sleep 5

    echo "Initial 2-node cluster started"
    docker compose -f docker-compose-addition.yml ps


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
        if check_node_health $NODE1_PORT "node1" && check_node_health $NODE2_PORT "node2"; then
            echo "Both nodes are ready"
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
        for port in $NODE1_PORT $NODE2_PORT; do
            local node_id=$(get_node_id_from_port $port)
            local debug_info=$(curl -s $BASE_URL:$port/debug/state 2>/dev/null)
            if [ $? -eq 0 ] && [ ! -z "debug_info" ]; then
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

    #Find and anounce current leader
    find_leader || return 1

    echo "Verifying peer relationships..."
    local node1_peers=$(curl -s $BASE_URL:$NODE1_PORT/debug/state | jq -r '.clusterState.peers[]' 2>/dev/null | tr '\n' ' ')
    local node2_peers=$(curl -s $BASE_URL:$NODE2_PORT/debug/state | jq -r '.clusterState.peers[]' 2>/dev/null | tr '\n' ' ')

    echo "Node1 peers: [$node1_peers]"
    echo "Node2 peers: [$node2_peers]"

    #Check if both nodes have same term
    local node1_term=$(curl -s $BASE_URL:$NODE1_PORT/debug/state | jq -r '.clusterState.currentTerm')
    local node2_term=$(curl -s $BASE_URL:$NODE2_PORT/debug/state | jq -r '.clusterState.currentTerm')
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

    #Try GET from follower to test consistency
    local follower_port
    if [ "$LEADER_PORT" = "$NODE1_PORT" ]; then
        follower_port=$NODE2_PORT
    else
        follower_port=$NODE1_PORT
    fi
    echo "Testing read from follower (port $follower_port)"
    execute_get "key1" $follower_port || retunrn 1

    echo "Initial operations completed successfully"
    return 0

}




capture_baseline_state(){
    echo "=== Capturing baseline state==="

    echo "Capturing key metrics from leader ($CURRENT_LEADER)"

    local debug_info=$(curl -s --max-time 5 --fail $BASE_URL:$LEADER_PORT/debug/state 2>/dev/null)
    if [ $? -eq 0 ] && [ ! -z "$debug_info" ]; then
        BASELINE_LOG_SIZE=$(echo "$debug_info" | grep -o '"logSize":[0-9]*' | cut -d':' -f2)
        BASELINE_COMMIT_INDEX=$(echo "$debug_info" | grep -o '"commitIndex":[0-9-]*' | cut -d':' -f2)
        BASELINE_CURRENT_TERM=$(echo "$debug_info" | grep -o '"currentTerm":[0-9]*' | cut -d':' -f2)

        echo "Baseline metrix captured from leader"
        echo " Log size: $BASELINE_LOG_SIZE"
        echo " Commit Index: $BASELINE_COMMIT_INDEX"
        echo " Current Term: $BASELINE_CURRENT_TERM"

        return 0
    else
        echo "Failed to capture baseline state from leader"
        return 1
    fi
}


start_new_node() {
    echo "=== Starting New Node (node3) ==="
    
    # Start node3 with Docker Compose
    echo "Starting node3..."
    docker compose -f docker-compose-addition.yml up -d node3
    
    echo "Waiting for node3 to initialize..."
    sleep 5
    
    # Just check if node3 is responding - don't analyze state yet
    echo "Checking node3 basic connectivity..."
    local max_attempts=15
    local attempt=1
    
    while [ $attempt -le $max_attempts ]; do
        echo "Connectivity check attempt $attempt/$max_attempts..."
        
        # Just check if the node is responding to any HTTP request
        if curl -s --max-time 5 --fail $BASE_URL:$NODE3_PORT/debug/state >/dev/null 2>&1; then
            echo "✓ Node3 is responding to HTTP requests"
            echo "✓ Node3 started successfully (not yet part of cluster)"
            return 0
        fi
        
        echo "Node3 not ready yet, waiting 2 seconds..."
        sleep 2
        ((attempt++))
    done
    
    echo "Timeout waiting for node3 to be ready"
    return 1
}

add_node_to_cluster(){
    echo "=== Adding node3 to cluster using joint consensus ==="

    echo "Setting node3 to joining state"

    local join_state_response=$(curl -s -w "%{http_code}" -o /tmp/join_state_response \
        -X POST \
        "$BASE_URL:$NODE3_PORT/api/v1/cluster/start-join")
    
    if [ "$join_state_response" = "200" ]; then
        echo "Sucessfully set node3 to joining state"
        echo "Response: $(cat /tmp/join_state_response)"
    else
        echo "Failed to set node3 to joining state (HTTP: $join_state_response)"
        echo "Error: $(cat /tmp/join_state_response 2>/dev/null)"
        return 1
    fi



    # echo "Bootstrapping node3's peerlist"
    # local bootstrap_response=$(curl -s -w "%{http_code}" -o /tmp/bootstrap_node3 \
    #     -X POST \
    #     -H "Content-Type: application/json" \
    #     -d '{"peers": ["node1", "node2"]}' \
    #     "$BASE_URL:$NODE3_PORT/api/v1/cluster/bootstrap-peers")

    # if [ "$bootstrap_response" = "200" ]; then
    #     echo "Successfully bootstrapped node3's peer list"
    # else
    #     echo "Failed to bootstrap node3's peer list (HTTP: $bootstrap_response)"
    #     return 1
    # fi

    # Initiate join process through leader using /join endpoint
    echo "Initiating joint consensus process via leader ($CURRENT_LEADER)"
    local join_response=$(curl -s -w "%{http_code}" -o /tmp/join_response \
        -X POST \
        -H "Content-Type: application/json" \
        -d "{\"nodeId\" : \"node3\",  \"lastLogIndex\": -1}" \
        "$BASE_URL:$LEADER_PORT/api/v1/cluster/join")
    if [ "$join_response" = "200" ]; then
        echo "Successfully initiated joint consensus process"
    else 
        echo "Failed to initiate joint consensus process (HTTP: $join_response)"
        return 1
    fi

    echo "Wait for joint consensus process to complete (10s to allow for both phases)"
    sleep 10
    

    # # Step 1: adding node3 to cluster via leader
    # echo "Adding node3 to existing cluster via leader ($CURRENT_LEADER)"
    # local add_response=$(curl -s -w "%{http_code}" -o /tmp/add_node_response \
    #     -X POST \
    #     -H "Content-Type: application/json" \
    #     -d '{"nodeId": "node3"}' \
    #     "$BASE_URL:$LEADER_PORT/api/v1/cluster/add-node")
    
    # if [ "$add_response" = "200" ]; then
    #     echo "Successfully added node3 to cluster's peer list"
    #     echo "Response : $(cat /tmp/add_node_response)"
    # else
    #     echo "Failed to add node3 to cluster (HTTP: $add_response)"
    #     echo "Error: $(cat /tmp/add_node_response 2>/dev/null)"
    #     return 1
    # fi

   

    
    #Step 4: Verifying cluster membership

    #Check leader's view for cluster
    echo "Checking leader's current peers"
    local leader_peers=$(curl -s "$BASE_URL:$LEADER_PORT/api/v1/cluster/peers")
    echo "Leader peers: $leader_peers"

    echo "Checking node's current peers"
    local node3_peers=$(curl -s "$BASE_URL:$NODE3_PORT/api/v1/cluster/peers")
    echo "Node3 peers: $node3_peers"

    #Step 5: Verify node3 is in correct initial state using debug endpoint
    echo "Checking node3's current peers"
    local node3_debug=$(curl -s $BASE_URL:$NODE3_PORT/debug/state)
    local node3_state=$(echo "$node3_debug" | grep -o '"currentState":"[^"]*"' | cut -d'"' -f4)
    local node3_log_size=$(echo "$node3_debug" | grep -o '"logSize":[0-9]*' | cut -d':' -f2)

    echo "Node3 Raft State: $node3_state"
    
    return 0
}

monitor_replication_progress(){
    echo "Monitory node3 replication progress"
    local expected_entries=$((BASELINE_LOG_SIZE+2))
    local expected_commit=$((BASELINE_COMMIT_INDEX+2))
    echo "Target: Log=$expected_entries, Commit=$expected_commit, Term=$BASELINE_CURRENT_TERM"

    local max_wait=60
    local elapsed=0

    while [ $elapsed -lt $max_wait ]; do
        local debug=$(curl -s $BASE_URL:$NODE3_PORT/debug/state 2>/dev/null)
        if [ $? -eq 0 ] && [ ! -z "$debug" ]; then
            local log_size=$(echo "$debug" | grep -o '"logSize":[0-9]*' | cut -d':' -f2)
            local commit_index=$(echo "$debug" | grep -o '"commitIndex":[0-9-]*' | cut -d':' -f2)
            local term=$(echo "$debug" | grep -o '"currentTerm":[0-9]*' | cut -d':' -f2)
            local is_joining=$(echo "$debug" | jq -r '.nodeState.isJoining' 2>/dev/null || echo "unknown")
            local is_caught_up=$(echo "$debug" | jq -r '.nodeState.isCaughtUp' 2>/dev/null || echo "unknown")
            echo "[$elapsed s] Node3: Log=$log_size/$expected_entries, Commit=$commit_index/$expected_commit, Term=$term/$BASELINE_CURRENT_TERM"
            echo "[$elapsed s] Node3: Joining=$is_joining, CaughtUp=$is_caught_up"

            if [ "$log_size" = "$expected_entries" ] && \
               [ "$commit_index" = "$expected_commit" ] && \
               [ "$term" = "$BASELINE_CURRENT_TERM" ] && \
               [ "$is_joining" = "false" ]; then
               echo "Success Node3 caught up in ${elapsed}s"
               return 0
            fi
        else 
            echo "[${elapsed}s] failed to to get node3 debug info"
        fi
        sleep 2
        elapsed=$((elapsed+2))
    done

    echo "Timeout node3 didn't catchup within ${max_wait}s"
    return 1
}

# Check if required tools are available
command -v docker >/dev/null 2>&1 || { echo "Docker is required but not installed"; exit 1; }
command -v jq >/dev/null 2>&1 || { echo "jq is required but not installed"; exit 1;}

main(){
    echo "Starting raft cluster test with docker compose"

    start_initial_cluster || { echo "FAiled to start cluster"; exit 1;}

    verify_cluster_health || { echo "Cluster health check failed"; exit 1; }

    execute_initial_operations || { echo "Initial operations failed"; exit 1;}
    
    capture_baseline_state || { echo "Failed to get baseline state"; exit 1; }
    
    start_new_node || { echo "Failed to start node3"; exit 1;}

    add_node_to_cluster || { echo "Failed to add node to cluster"; exit 1;}

    monitor_replication_progress || { echo "Failed to replicate"; exit 1;}

    echo "Steps 1-3 completed successfully"
    echo "Current Leader: $CURRENT_LEADER (port $LEADER_PORT)"

    docker compose down

    echo "cleaning up old data..."
    # rm -rf ./data/raft/*
    # rm -rf ./logs/*
}
main