package com.example.node_runner.config;

import java.io.IOException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.kvstore.InMemoryKVStore;
import com.example.kvstore.KVStore;
import com.example.kvstore.statemachine.KVStoreStateMachine;
import com.example.log.InMemoryLogManager;
import com.example.log.LogManager;
import com.example.log.PersistentLogManager;
import com.example.networking.config.NetworkConfig;
import com.example.networking.rpc.HttpRaftRpcService;
import com.example.node.RaftNode;
import com.example.persistence.FilePersistenceManager;
import com.example.persistence.PersistenceManager;
import com.example.raft.statemachine.StateMachine;
import com.example.rpc.RaftRpcService;
import com.example.timer.ElectionTimer;
import com.example.timer.ElectionTimerImpl;
import com.example.timer.HeartBeatTimer;
import com.example.timer.HeartBeatTimerImpl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Configuration
@Slf4j
@RequiredArgsConstructor
public class NodeRunnerConfig {
    private final NodeConfig raftConfig;

    @Bean
    public PersistenceManager persistenceManager() throws IOException {
        FilePersistenceManager persistenceManager = new FilePersistenceManager(
                raftConfig.getStorageDir(),
                raftConfig.getNodeId());
        persistenceManager.initialize();
        return persistenceManager;
    }

    @Bean
    public LogManager logManager(PersistenceManager persistenceManager) throws IOException {
        return new PersistentLogManager(persistenceManager);
    }

    @Bean
    public ElectionTimer electionTimer() {
        return new ElectionTimerImpl(raftConfig.getElectionTimeoutMs(), raftConfig.getElectionTimeoutVarianceMs());
    }

    @Bean
    public HeartBeatTimer heartBeatTimer() {
        return new HeartBeatTimerImpl(raftConfig.getHeartbeatIntervalMs());
    }

    @Bean
    public NetworkConfig networkConfig() {
        NetworkConfig networkConfig = new NetworkConfig();
        for (String peerId : raftConfig.getPeers()) {
            String baseUrl = networkConfig.resolveNodeUrl(peerId);
            networkConfig.getNodeUrls().put(peerId, baseUrl);
        }
        return networkConfig;
    }

    @Bean
    public RaftRpcService raftRpcService(NetworkConfig networkConfig) {
        return new HttpRaftRpcService(networkConfig);
    }

    @Bean
    public KVStore kvStore() {
        return new InMemoryKVStore();
    }

    @Bean
    public StateMachine stateMachine(KVStore kvStore) {
        return new KVStoreStateMachine(kvStore);
    }

    @Bean
    public RaftNode raftNode(
            LogManager logManager,
            RaftRpcService rpcService,
            ElectionTimer electionTimer,
            HeartBeatTimer heartBeatTimer,
            StateMachine stateMachine) {
        return new RaftNode(raftConfig.getNodeId(), raftConfig.getPeers(), logManager, rpcService, electionTimer,
                heartBeatTimer, stateMachine);
    }
}
