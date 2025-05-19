package com.example.node;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import lombok.extern.slf4j.Slf4j;

/**
 * Tracks node failures based on failed RPC attempts
 * Only the leader should use this to track follower node failures
 */
@Slf4j
public class NodeFailureDetector {
    private final Map<String, AtomicInteger> failureCounters = new ConcurrentHashMap<>();
    private final int failureThreshold;
    private final Consumer<String> nodeFailureHandler;

    public NodeFailureDetector(int failureThreshold, Consumer<String> nodeFailureHandler) {
        this.failureThreshold = failureThreshold;
        this.nodeFailureHandler = nodeFailureHandler;
    }

    /**
     * Record a successfull communication with a node
     * 
     * @param nodeId Id of the node
     */
    public void recordSuccess(String nodeId) {
        AtomicInteger counter = failureCounters.get(nodeId);
        if (counter != null && counter.get() > 0) {
            log.debug("Resetting failure counter for node {}", nodeId);
            counter.set(0);
        }
    }

    /**
     * Record a failed communication with a node
     * 
     * @param nodeId id of the node
     * @return true if node has reached the failure threshold, false otherwise
     */
    public boolean recordFailure(String nodeId) {
        AtomicInteger counter = failureCounters.computeIfAbsent(nodeId, k -> new AtomicInteger(0));
        int failures = counter.incrementAndGet();
        log.debug("Recorded failure #{} for node {}", failures, nodeId);
        if (failures >= failureThreshold) {
            log.warn("Node {} has reached failure threshold ({} consecutive failures)", nodeId, failureThreshold);
            // Reset counter to prevent repeated triggers
            counter.set(0);

            if (nodeFailureHandler != null) {
                nodeFailureHandler.accept(nodeId);
            }
            return true;
        }
        return false;
    }

    /**
     * Reset the failure counter for specific node
     * 
     * @param nodeId id of the node
     */
    public void resetCounter(String nodeId) {
        AtomicInteger counter = failureCounters.get(nodeId);
        if (counter != null) {
            counter.set(0);
        }
    }

    /**
     * Reset all failure counters
     */
    public void resetAllCounters() {
        failureCounters.clear();
        log.debug("All failure counters reset");
    }

    /**
     * Get failure count for a node
     * 
     * @param nodeId id of the node
     * @return current failure count, or 0 if node has no failures
     */
    public int getFailuresCount(String nodeId) {
        AtomicInteger counter = failureCounters.get(nodeId);
        return counter != null ? counter.get() : 0;
    }

    public boolean isNodeConsideredFailed(String nodeId) {
        AtomicInteger failureCount = failureCounters.get(nodeId);
        if (failureCount == null) {
            return false;
        }
        return failureCount.get() >= failureThreshold;
    }
}
