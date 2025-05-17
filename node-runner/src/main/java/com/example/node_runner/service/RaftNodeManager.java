package com.example.node_runner.service;

import java.io.File;

import org.slf4j.Logger;
import org.springframework.stereotype.Service;

import com.example.kvstore.KVStore;
import com.example.kvstore.statemachine.KVStoreStateMachine;
import com.example.log.LogManager;
import com.example.node.RaftNode;
import com.example.node_runner.config.NodeConfig;
import com.example.raft.statemachine.StateMachine;
import com.example.rpc.RaftRpcService;
import com.example.state.RaftState;
import com.example.timer.ElectionTimer;
import com.example.timer.HeartBeatTimer;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class RaftNodeManager {

    private final NodeConfig config;

    @Getter
    private RaftNode raftNode;

    public RaftNodeManager(
            NodeConfig config, RaftNode raftNode) {
        this.config = config;
        this.raftNode = raftNode;

        // this.initializeLogging();
    }

    @PostConstruct
    public void start() {
        log.info("RaftNodeManager starting RaftNode: {}", raftNode.getNodeId());
        raftNode.start();
    }

    @PreDestroy
    public void stop() {
        if (raftNode != null) {
            raftNode.stop();
        }
    }

    // Node status methods
    public RaftState getState() {
        return raftNode != null ? raftNode.getState() : null;
    }

    public String getNodeId() {
        return config.getNodeId();
    }

    public boolean isLeader() {
        return raftNode != null && raftNode.isLeader();
    }

    public int getCurrentTerm() {
        return raftNode != null ? raftNode.getCurrentTerm() : -1;
    }

    public String getLeaderId() {
        return raftNode != null ? raftNode.getLeaderId() : null;
    }
}
