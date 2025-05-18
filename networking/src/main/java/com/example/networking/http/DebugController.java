package com.example.networking.http;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.kvstore.InMemoryKVStore;
import com.example.kvstore.statemachine.KVStoreStateMachine;
import com.example.model.RaftLogEntry;
import com.example.node.RaftNode;
import com.example.raft.statemachine.StateMachine;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/debug")
@RequiredArgsConstructor
@Slf4j
public class DebugController {
    private final RaftNode raftNode;

    @GetMapping("/state")
    public Map<String, Object> getCompleteDebugState() {

        Map<String, Object> debugInfo = new HashMap<>();
        try {
            // Basic node information
            debugInfo.put("nodeId", raftNode.getNodeId());
            debugInfo.put("timestamp", System.currentTimeMillis());

            // Cluster state
            Map<String, Object> clusterState = new HashMap<>();
            clusterState.put("currentState", raftNode.getState().toString());
            clusterState.put("currentTerm", raftNode.getCurrentTerm());
            clusterState.put("votedFor", raftNode.getVotedFor());
            clusterState.put("leaderId", raftNode.getLeaderId());
            clusterState.put("isLeader", raftNode.isLeader());
            clusterState.put("peers", raftNode.getPeers());

            if (raftNode.isLeader()) {
                clusterState.put("nextIndex", raftNode.getNextIndex());
                clusterState.put("replicationIndex", raftNode.getReplicationIndex());
            }
            debugInfo.put("clusterState", clusterState);

            // Log Metadata
            Map<String, Object> logMetadata = new HashMap<>();
            logMetadata.put("logSize", raftNode.getLogManager().size());
            logMetadata.put("lastLogIndex", raftNode.getLogManager().getLastLogIndex());
            logMetadata.put("lastLogTerm", raftNode.getLogManager().getLastLogTerm());
            logMetadata.put("commitIndex", raftNode.getCommitIndex());
            debugInfo.put("logMetadata", logMetadata);

            // Log Entries
            List<RaftLogEntry> entries = raftNode.getLogManager().getEntriesFrom(0);
            Map<String, Object> logInfo = new HashMap<>();
            logInfo.put("totalEntries", entries.size());
            logInfo.put("entries", entries);
            debugInfo.put("logEntries", logInfo);

            // Kv store state
            Map<String, Object> kvInfo = new HashMap<>();
            StateMachine stateMachine = raftNode.getStateMachine();
            if (stateMachine instanceof KVStoreStateMachine) {
                KVStoreStateMachine kvStateMachine = (KVStoreStateMachine) stateMachine;
                if (kvStateMachine.getKVStore() instanceof InMemoryKVStore) {
                    InMemoryKVStore kvStore = (InMemoryKVStore) kvStateMachine.getKVStore();

                    Map<String, String> allEntries = kvStore.getAllEntries();
                    kvInfo.put("keyValuePairs", allEntries);
                    kvInfo.put("totalKeys", allEntries.size());
                    kvInfo.put("available", true);
                } else {
                    kvInfo.put("available", false);
                    kvInfo.put("reason", "KVstore type not supported for debug");
                }
            } else {
                kvInfo.put("available", false);
                kvInfo.put("reason", "StateMachine is not KVStoreStateMachine");
            }

            debugInfo.put("kvState", kvInfo);

            // Joining and JointConsensus state
            Map<String, Object> nodeState = new HashMap<>();
            nodeState.put("isJoining", raftNode.isJoining());
            nodeState.put("isCaughtUp", raftNode.isCaughtUp());
            nodeState.put("inJointConsensus", raftNode.isInJointConsensus());
            debugInfo.put("nodeState", nodeState);

            debugInfo.put("success", true);

        } catch (Exception e) {
            log.error("Error retrieving debug information", e);
            debugInfo.put("success", false);
            debugInfo.put("error", e.getMessage());
        }

        return debugInfo;
    }

}
