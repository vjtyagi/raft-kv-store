package com.example.command;

import java.util.ArrayList;
import java.util.List;

import com.example.raft.statemachine.StateMachineCommand;

import lombok.Getter;

@Getter
public class ConfigChangeCommand implements StateMachineCommand {
    public enum ConfigChangeType {
        JOINT,
        FINAL
    }

    private final ConfigChangeType type;
    private final List<String> oldConfig;
    private final List<String> newConfig;

    public ConfigChangeCommand(ConfigChangeType type, List<String> oldConfig, List<String> newConfig) {
        this.type = type;
        this.oldConfig = oldConfig != null ? new ArrayList<>(oldConfig) : null;
        this.newConfig = newConfig != null ? new ArrayList<>(newConfig) : null;
    }

    @Override
    public String serialize() {
        StringBuilder sb = new StringBuilder();
        sb.append("CONFIG_CHANGE|").append(type);

        if (oldConfig != null) {
            sb.append("|OLD:");
            for (int i = 0; i < oldConfig.size(); i++) {
                if (i > 0)
                    sb.append(",");
                sb.append(oldConfig.get(i));
            }
        }

        if (newConfig != null) {
            sb.append("|NEW:");
            for (int i = 0; i < newConfig.size(); i++) {
                if (i > 0)
                    sb.append(",");
                sb.append(newConfig.get(i));
            }
        }
        return sb.toString();
    }

    public static ConfigChangeCommand deserialize(String serialized) {
        // Format:
        // CONFIG_CHANGE|JOINT|FINAL|OLD:node1,node2,node3|NEW:node1,node2,node3,node4
        // or CONFIG_CHANGE|FINAL|NEW:node1,node2,node3,node4
        String[] parts = serialized.split("\\|");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Invalid ConfigChangeCommand format");
        }

        try {
            ConfigChangeType type = ConfigChangeType.valueOf(parts[1]);
            List<String> oldConfig = null;
            List<String> newConfig = null;

            for (int i = 2; i < parts.length; i++) {
                if (parts[i].startsWith("OLD:")) {
                    String[] nodeIds = parts[i].substring(4).split(",");
                    oldConfig = new ArrayList<>();
                    for (String nodeId : nodeIds) {
                        if (!nodeId.isEmpty()) {
                            oldConfig.add(nodeId);
                        }
                    }
                } else if (parts[i].startsWith("NEW:")) {
                    String[] nodeIds = parts[i].substring(4).split(",");
                    newConfig = new ArrayList<>();
                    for (String nodeId : nodeIds) {
                        if (!nodeId.isEmpty()) {
                            newConfig.add(nodeId);
                        }
                    }
                }
            }
            return new ConfigChangeCommand(type, oldConfig, newConfig);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid ConfigChangeCommand: " + e.getMessage());
        }
    }
}
