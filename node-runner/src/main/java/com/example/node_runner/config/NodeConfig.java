package com.example.node_runner.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

@Configuration
@ConfigurationProperties(prefix = "raft")
@Data
public class NodeConfig {
    private String nodeId;
    private List<String> peers;
    private String storageDir;
    private int electionTimeoutMs = 500;
    private int electionTimeoutVarianceMs = 500;
    private int heartbeatIntervalMs = 100;
    private int clientPort;
    private int rpcPort;
}
