package com.example.node;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.command.ConfigChangeCommand;
import com.example.command.ConfigChangeCommand.ConfigChangeType;
import com.example.log.LogManager;
import com.example.model.AppendEntriesRequest;
import com.example.model.AppendEntriesResponse;
import com.example.model.RaftLogEntry;
import com.example.model.RequestVoteRequest;
import com.example.model.RequestVoteResponse;
import com.example.raft.statemachine.StateMachine;
import com.example.raft.statemachine.StateMachineCommand;
import com.example.rpc.RaftRpcService;
import com.example.state.RaftState;
import com.example.timer.ElectionTimer;
import com.example.timer.HeartBeatTimer;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RaftNode {
    // current state
    private volatile RaftState state = RaftState.FOLLOWER;
    private volatile int commitIndex = -1; // highest log entry known to be replicated across majority of nodes
    private volatile int lastApplied = -1; // highest log entry applied to state machine

    private final String nodeId;
    private List<String> peerIds;
    private volatile String leaderId = null;

    // components and services
    private final LogManager logManager;
    private final RaftRpcService rpcService;
    private final ElectionTimer electionTimer;
    private final HeartBeatTimer heartBeatTimer;

    // state machine
    private final StateMachine stateMachine;

    // Candidate state
    private final AtomicInteger voteCount = new AtomicInteger();

    // Leader state (only valid when state = LEADER)
    private Map<String, Integer> nextIndex = new HashMap<>(); // For each peer, next log index to send
    private Map<String, Integer> replicationIndex = new HashMap<>(); // for each peer, highest log entry known to be

    // Configuration state for joint consensus
    private volatile boolean inJointConsensus = false;
    private volatile List<String> oldConfiguration = null;
    private volatile List<String> newConfiguration = null;

    // Catch-up tracking for joining nodes
    private volatile boolean isJoining = false;
    private volatile boolean isCaughtUp = false;

    private final NodeFailureDetector nodeFailureDetector;
    private static final int NODE_FAILURE_THRESHOLD = 10;
    private final Map<String, Boolean> removalInProgress = new ConcurrentHashMap<>();

    public RaftNode(String nodeId, List<String> peerIds, LogManager logManager, RaftRpcService rpcService,
            ElectionTimer electionTimer, HeartBeatTimer heartBeatTimer) {
        this(nodeId, peerIds, logManager, rpcService, electionTimer, heartBeatTimer, null);
    }

    public RaftNode(String nodeId, List<String> peerIds, LogManager logManager, RaftRpcService rpcService,
            ElectionTimer electionTimer, HeartBeatTimer heartBeatTimer, StateMachine stateMachine) {
        this.nodeId = nodeId;
        this.peerIds = peerIds;
        this.logManager = logManager;
        this.rpcService = rpcService;
        this.electionTimer = electionTimer;
        this.heartBeatTimer = heartBeatTimer;
        this.stateMachine = stateMachine;

        // Setup timer callbacks
        this.electionTimer.setTimeoutHandler(this::onElectionTimeout);
        this.heartBeatTimer.setHeartBeatHandler(this::sendHeartBeats);

        // Register RPC handlers
        this.rpcService.registerRequestVoteHandler(this::handleRequestVote);
        this.rpcService.registerAppendEntriesHandler(this::handleAppendEntries);
        this.nodeFailureDetector = new NodeFailureDetector(NODE_FAILURE_THRESHOLD, this::handleNodeFailure);
    }

    /**
     * Handles detected node failure by initiating it's removal from the cluster
     * This is called by NodeFailureDetector when a node reaches the failure
     * threshold
     * 
     */
    private void handleNodeFailure(String failedNodeId) {
        if (state != RaftState.LEADER) {
            log.warn("{}, Ignoring node failure for {} as we're not the leader", nodeId, failedNodeId);
            return;
        }
        log.warn("{} Handling node failure for {}, initiating removal process", nodeId, failedNodeId);
        initiateNodeRemoval(failedNodeId);
    }

    private boolean initiateNodeRemoval(String nodeToRemove) {
        try {
            if (removalInProgress.putIfAbsent(nodeToRemove, true) != null) {
                log.info("{}: Removal of {} already in progress, ignoring duplicate request", nodeId, nodeToRemove);
                return false;
            }
            if (!peerIds.contains(nodeToRemove)) {
                log.warn("{} cannot remove {} as it's not in peer list", nodeId, nodeToRemove);
                return false;
            }
            // Get current configuration(all nodes including leader)
            List<String> oldConfig = new ArrayList<>(peerIds);
            oldConfig.add(nodeId);

            // Create new configuration without failed node
            List<String> newConfig = new ArrayList<>();
            for (String peer : oldConfig) {
                if (!peer.equals(nodeToRemove)) {
                    newConfig.add(peer);
                }
            }

            // Check if removal would break quorum
            int oldQuorum = (oldConfig.size() / 2) + 1;
            int newQuorum = (newConfig.size() / 2) + 1;
            if (newConfig.size() < oldQuorum) {
                log.error("{} cannot remove {} at it would break quorum need {} node for majority", nodeId,
                        nodeToRemove, oldQuorum);
                return false;
            }

            log.info("{} Initiating joing consensus to remove node {} - Old: {}, New: {}", nodeId, nodeToRemove,
                    oldConfig, newConfig);
            ConfigChangeCommand jointCommand = new ConfigChangeCommand(
                    ConfigChangeType.JOINT, oldConfig, newConfig);

            // Append command to log
            boolean successs = appendCommand(jointCommand);

            if (successs) {
                log.info("{} successfully initiated JOINT configuration to remove node {}", nodeId, nodeToRemove);
                Thread.ofVirtual().start(() -> {
                    try {
                        int jointConfigIndex = logManager.getLastLogIndex();
                        log.info("{}: Joint Config entry is at index {}, waiting for it to commit", nodeId,
                                jointConfigIndex);
                        long startTime = System.currentTimeMillis();

                        while (isLeader() && commitIndex < jointConfigIndex) {
                            Thread.sleep(100);
                            if (System.currentTimeMillis() - startTime > 10000) {
                                log.warn("{}: Timeout waiting for joint config to commit", nodeId);
                                break;
                            }
                        }

                        if (!isLeader()) {
                            log.warn("{} no longer leader, abandoning final config for removal of ", nodeId,
                                    nodeToRemove);
                            return;
                        }
                        log.info("{}: Proceeding to final configuration", nodeId);
                        ConfigChangeCommand finalCommand = new ConfigChangeCommand(
                                ConfigChangeType.FINAL, null, newConfig);
                        Thread.sleep(10);
                        boolean finalSuccess = appendCommand(finalCommand);
                        if (finalSuccess) {
                            log.info("{}: Successfully completed removal of node {}", nodeId, nodeToRemove);
                            removalInProgress.remove(nodeToRemove);
                        } else {
                            log.error("{} failed to append FINAL configuration for removal of {}", nodeId,
                                    nodeToRemove);
                        }
                    } catch (Exception e) {
                        log.error("{} Error during final configuration for removal of {}", nodeId, nodeToRemove, e);
                    }
                });
                return true;
            } else {
                log.error("{} failed to append JOINT configuration for removal of {}", nodeId, nodeToRemove);
                return false;
            }

        } catch (Exception e) {
            log.error("{} Error initiating removal of node {}", nodeId, nodeToRemove, e);
            return false;
        }
    }

    /**
     * Mark this node as joining the cluster
     * This will prevent node from voting until it's caught up
     * 
     */
    public void setJoining(boolean joining) {
        this.isJoining = joining;
        this.isCaughtUp = !isJoining;
    }

    public void setCaughtUp(boolean caughtUp) {
        this.isCaughtUp = caughtUp;
    }

    public boolean isJoining() {
        return isJoining;
    }

    public boolean isCaughtUp() {
        return isCaughtUp;
    }

    public boolean isInJointConsensus() {
        return inJointConsensus;
    }

    public Map<String, Integer> getNextIndex() {
        return new HashMap<>(nextIndex);
    }

    public Map<String, Integer> getReplicationIndex() {
        return new HashMap<>(replicationIndex);
    }

    public void setNextIndex(String peerId, int index) {
        nextIndex.put(peerId, index);
    }

    public void setReplicationIndex(String peerId, int index) {
        replicationIndex.put(peerId, index);
    }

    public int getCommitIndex() {
        return commitIndex;
    }

    public void setState(RaftState state) {
        this.state = state;
    }

    public void setCurrentTerm(int term) {
        logManager.saveCurrentTerm(term);
    }

    public int getCurrentTerm() {
        return logManager.getCurrentTerm();
    }

    public String getVotedFor() {
        return logManager.getVotedFor();
    }

    /**
     * Starts the RaftNode
     * 
     */
    public void start() {
        log.info("{}: Starting Raft node", nodeId);
        if (state == RaftState.FOLLOWER) {
            electionTimer.start();
        } else {
            transitionTo(RaftState.FOLLOWER);
        }
        rpcService.start();
    }

    /**
     * Stops the Raft node
     * 
     */
    public void stop() {
        System.out.println(nodeId + ": Stopping Raft Node");
        electionTimer.stop();
        heartBeatTimer.stop();
        rpcService.stop();
    }

    /**
     * Transition this node to a new state with proper cleanup and initialization
     */
    private synchronized void transitionTo(RaftState newState) {
        if (state == newState) {
            return;
        }

        RaftState oldState = state;
        log.info("{}: Transitioning from {} to {} in term {}", nodeId, oldState, newState, getCurrentTerm());
        System.out.println(nodeId + ": Transitioning from " + oldState + " to " + newState);
        // cleanup old state
        switch (oldState) {
            case FOLLOWER:
                electionTimer.stop();
                break;
            case CANDIDATE:
                // clean up election state
                electionTimer.stop();
                break;
            case LEADER:
                // stop heartbeats
                this.leaderId = null;
                log.info("{}: Stopping heartbeat timer when stepping down from leader", nodeId);
                heartBeatTimer.stop();
                break;

            default:
                break;
        }
        state = newState;
        System.out.println(nodeId + " State: " + state);
        if (oldState == RaftState.LEADER && newState != RaftState.LEADER) {
            nodeFailureDetector.resetAllCounters();
        }
        // initialize new state
        switch (newState) {
            case FOLLOWER:
                // start election timer
                electionTimer.start();
                break;
            case CANDIDATE:
                // start election
                this.leaderId = null;
                startElection();
                break;
            case LEADER:
                // Become leader
                this.leaderId = nodeId;
                nodeFailureDetector.resetAllCounters();
                becomeLeader();
                break;

            default:
                break;
        }

    }

    /**
     * Triggered when an election timeout occurs
     * Transitions to candidate state and starts a new election
     */
    private void onElectionTimeout() {

        log.info("{}: Election timeout occurred in state {}, current term: {}",
                nodeId, state, getCurrentTerm());

        if (isJoining && !isCaughtUp) {
            log.info("{}: Node is joining and not caught up, ignoring election timeout", nodeId);
            electionTimer.reset();
            return;
        }

        if (state != RaftState.LEADER) {
            log.debug("{}: Starting election due to timeout", nodeId);
            transitionTo(RaftState.CANDIDATE);
        } else if (state == RaftState.LEADER) {
            log.warn("{}: Election timeout occurred while LEADER - this shouldn't happen", nodeId);

        }
    }

    /**
     * Starts a new election when transitioning to candidate state
     */
    private synchronized void startElection() {
        logManager.incrementCurrentTerm();
        logManager.saveVotedFor(nodeId);
        voteCount.set(1);

        int currentTerm = getCurrentTerm();
        System.out.println(nodeId + ": starting election for term " + currentTerm);
        log.debug("{} Starting election for term: {}", nodeId, currentTerm);
        // Reset Election timer in case we don't get majority
        electionTimer.reset();

        // Create RequestVote Rpc
        final int lastLogIndex = logManager.getLastLogIndex();
        final int lastLogTerm = logManager.getLastLogTerm();
        RequestVoteRequest request = new RequestVoteRequest(currentTerm, nodeId, lastLogIndex, lastLogTerm);
        // Send RequestVote RPCs to all peers
        for (String peerId : getPeersForVoting()) {
            CompletableFuture<RequestVoteResponse> future = rpcService.sendRequestVote(peerId, request);
            future.thenAccept(response -> processVoteResponse(peerId, response))
                    .exceptionally(e -> {
                        log.debug("Error sending requestvote to {}", peerId, e);
                        return null;
                    });
        }
    }

    private List<String> getPeersForVoting() {
        if (!inJointConsensus) {
            return peerIds;
        } else {
            // In joint consensus, we need to send all peers in both configuration

            List<String> allPeers = new ArrayList<>();
            if (oldConfiguration != null) {
                for (String peer : oldConfiguration) {
                    if (!peer.equals(nodeId) && !allPeers.contains(peer)) {
                        allPeers.add(peer);
                    }
                }
            }
            if (newConfiguration != null) {
                for (String peer : newConfiguration) {
                    if (!peer.equals(nodeId) && !allPeers.contains(peer)) {
                        allPeers.add(peer);
                    }
                }
            }

            return allPeers;
        }

    }

    private synchronized void processVoteResponse(String peerId, RequestVoteResponse response) {
        try {
            int currentTerm = getCurrentTerm();
            log.debug("{}: Processing vote from {} in term {} (my term: {}), granted: {}",
                    nodeId, peerId, response.getTerm(), currentTerm, response.isVoteGranted());

            if (state == RaftState.LEADER) {
                log.debug("{} Ignoring vote from {} as we're already a leader", nodeId, peerId);
                return;
            }
            // Ignore if no longer candidate or term doesn't match
            if (state != RaftState.CANDIDATE) {
                return;
            }
            // If responder has higher term, step down
            if (response.getTerm() > currentTerm) {
                log.info("{}: Stepping down due to higher term in vote response from {}", nodeId, peerId);
                currentTerm = response.getTerm();
                logManager.saveCurrentTerm(currentTerm);
                logManager.saveVotedFor(null);
                transitionTo(RaftState.FOLLOWER);
                return;
            }
            if (response.getTerm() < currentTerm) {
                return;
            }
            // Count vote if granted
            if (response.isVoteGranted()) {
                int votes = voteCount.incrementAndGet();
                // int totalNodes = peerIds.size() + 1;
                // int majority = (totalNodes / 2) + 1;
                // log.info("{}: Received vote from {} in term {}, vote count: {}/{} for
                // majority",
                // nodeId, peerId, currentTerm, votes, majority);
                // System.out.println(nodeId + ": vote count is now " + votes + "(need majority)
                // + " + " for majority");
                // If majority, become leader
                if (hasMajority(votes)) {
                    transitionTo(RaftState.LEADER);
                }
            } else {
                log.debug("{}: Vote denied by {} in term {}", nodeId, peerId, currentTerm);
            }
        } catch (Exception e) {
            log.error("Error occurred in processVoteResponse", e);
        }

    }

    private boolean hasMajority(int votes) {
        if (!inJointConsensus) {
            int majority = (peerIds.size() + 1) / 2 + 1;
            log.debug("{}: vote count is now {} need {} for majority", nodeId, votes, majority);
            return votes >= majority;
        } else {
            // In joint consensus - need majority in both old and new configuration
            int oldConfigSize = oldConfiguration.size();
            int newConfigSize = newConfiguration.size();
            int oldMajority = oldConfigSize / 2 + 1;
            int newMajority = newConfigSize / 2 + 1;
            return votes >= oldMajority && votes >= newMajority;

        }
    }

    /**
     * Initializes leader state when tarnsitioning to leader
     */
    private synchronized void becomeLeader() {
        int currentTerm = getCurrentTerm();
        log.info(" {} Becoming leader for term {}", nodeId, currentTerm);
        this.leaderId = nodeId;
        // Initializes nextIndex and matchIndex for all peers
        int nextIdx = logManager.getLastLogIndex() + 1;

        List<String> allPeers = getPeersForVoting();

        for (String peerId : allPeers) {
            nextIndex.put(peerId, nextIdx);
            replicationIndex.put(peerId, 0);
        }
        sendHeartBeats();
        heartBeatTimer.start();
    }

    /**
     * Send heartbeats(empty AppendEntries RPCs) to all peers
     */
    private void sendHeartBeats() {
        try {
            int currentTerm = getCurrentTerm();
            if (state != RaftState.LEADER) {
                log.warn("{}: Attempted to send heartbeats while not LEADER (state: {})",
                        nodeId, state);
                return;
            }
            List<String> targetPeers;
            if (!inJointConsensus) {
                targetPeers = new ArrayList<>(peerIds);
            } else {
                targetPeers = new ArrayList<>();
                for (String peer : newConfiguration) {
                    if (!peer.equals(nodeId)) {
                        targetPeers.add(peer);
                    }
                }
            }
            log.debug("sendHeartbeats newConfiguration: {}", newConfiguration);
            log.debug("{}: Sending heartbeats to {} peers for term {}",
                    nodeId, targetPeers, currentTerm);
            List<String> allPeers = getPeersForVoting();
            for (String peerId : targetPeers) {
                try {
                    sendAppendEntries(peerId);
                } catch (Exception e) {
                    log.error("{}: Error sending heartbeat to {}: {}", nodeId, peerId, e.getMessage(), e);
                }

            }
        } catch (Exception e) {
            log.error("Error: {} failed to send heartbeats ", nodeId, e);
        }

    }

    /**
     * Send AppendEntries RPC to a specific peer
     */
    private void sendAppendEntries(String peerId) {
        try {

            if (state != RaftState.LEADER) {
                log.warn("{}: sendAppendEntries called for {} while not leader (state: {})", nodeId, peerId, state);
                return;
            }
            if (!nextIndex.containsKey(peerId)) {
                int lastIdx = logManager.getLastLogIndex();
                log.warn("{}: Adding missing nextIndex for {} to {}", nodeId, peerId, lastIdx + 1);
                nextIndex.put(peerId, lastIdx + 1);
                replicationIndex.put(peerId, 0);
            }

            int currentTerm = getCurrentTerm();
            int prevLogIndex = nextIndex.get(peerId) - 1;
            int prevLogTerm = prevLogIndex >= 0 ? logManager.getLogEntryTerm(prevLogIndex) : 0;
            int peerNextIndex = nextIndex.get(peerId);

            // Get entries to send
            List<RaftLogEntry> entries = logManager.getEntriesFrom(nextIndex.get(peerId));
            log.debug("{}: Sending {} commitIndex={} entries to {} starting at index {} (prevLogIndex={})",
                    nodeId, entries.size(), commitIndex, peerId, peerNextIndex, prevLogIndex);
            log.debug("Sending entries={} to node {}", entries, peerId);
            // Create AppendEntriesRequest
            AppendEntriesRequest request = new AppendEntriesRequest(currentTerm, nodeId, prevLogIndex, prevLogTerm,
                    entries,
                    commitIndex);
            // Send RPC
            CompletableFuture<AppendEntriesResponse> future = rpcService.sendAppendEntries(peerId, request);
            future.thenAccept(response -> processAppendEntriesResponse(peerId, request, response))
                    .exceptionally(e -> {
                        log.error("{nodeId}: Error sending appendEntries to {peerId}", nodeId, peerId, e);
                        // Record failure when rpc throws exception
                        nodeFailureDetector.recordFailure(peerId);
                        return null;
                    });
        } catch (Exception e) {
            log.error("{}: Exception preparing AppendEntries for {}: {}", nodeId, peerId, e.getMessage());
            nodeFailureDetector.recordFailure(peerId);
        }

    }

    private synchronized void processAppendEntriesResponse(String peerId, AppendEntriesRequest request,
            AppendEntriesResponse response) {
        // ignore if no longer leader or term doesn't match
        try {
            // If responder has higher term, step down
            int currentTerm = getCurrentTerm();
            if (response.getTerm() > currentTerm) {
                currentTerm = response.getTerm();
                logManager.saveCurrentTerm(currentTerm);
                logManager.saveVotedFor(null);
                transitionTo(RaftState.FOLLOWER);
                return;
            }

            if (state != RaftState.LEADER || currentTerm != request.getTerm()) {
                return;
            }
            // Handle success/failure
            if (response.isSuccess()) {
                // Record successfull communication
                nodeFailureDetector.recordSuccess(peerId);
                // update nextIndex and matchIndex for successful append entries
                int newReplicationIndex = request.getPrevLogIndex() + request.getEntries().size();
                nextIndex.put(peerId, newReplicationIndex + 1);
                replicationIndex.put(peerId, newReplicationIndex);

                // Check if we can advance commit index(later)
                updateCommitIndex();

            } else {
                // Record failed communication - But only for actual failures
                // for simplicity we're catching all failures here
                nodeFailureDetector.recordFailure(peerId);

                if (!shouldReplicateToPeer(peerId)) {
                    log.debug("{}: Not retrying for {} as it's no longer in the active configuration",
                            nodeId, peerId);
                    return;
                }
                // Decrement nextIndex and retry
                if (!nextIndex.containsKey(peerId)) {
                    log.warn("{}: Peer {} no longer in nextIndex map after failed append. Ignoring.",
                            nodeId, peerId);
                    return;
                }
                int oldNextIndex = nextIndex.get(peerId);
                int newNextIndex = Math.max(0, oldNextIndex - 1);
                nextIndex.put(peerId, newNextIndex);
                log.info("{}: AppendEntries failed for {} - nextIndex: {} -> {} (prevLogIndex was {})",
                        nodeId, peerId, oldNextIndex, newNextIndex, request.getPrevLogIndex());
                if (newNextIndex == 0) {
                    log.info("{}: Sending entire log to {} from begining", nodeId, peerId);
                }
                // Retry immediately
                CompletableFuture.runAsync(() -> {
                    try {
                        Thread.sleep(10);
                        if (state != RaftState.LEADER || !shouldReplicateToPeer(peerId)) {
                            log.debug("{}: Skipping retry for {}, leader={}, should-replicate={}",
                                    nodeId, peerId, state == RaftState.LEADER, shouldReplicateToPeer(peerId));
                            return;
                        }
                        log.debug("{}: Retrying sendAppendEntries for {}", nodeId, peerId);

                        sendAppendEntries(peerId);
                    } catch (InterruptedException e) {
                        log.debug("Retry thread interrupted", e);
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        log.debug("Retry failed", e);
                    }
                });
                // sendAppendEntries(peerId);
            }
        } catch (Exception e) {
            log.error("Error: processAppendEntriesResponse", e);
        }

    }

    public boolean shouldReplicateToPeer(String peerId) {
        if (!inJointConsensus) {
            return peerIds.contains(peerId);
        }
        if (newConfiguration != null && newConfiguration.contains(peerId)) {
            return true;
        }

        // Special case for joint consensus - during transition we still need to
        // replicate to peers in old configuration until final is commiteed
        if (oldConfiguration != null && oldConfiguration.contains(peerId)) {
            if (nodeFailureDetector != null && nodeFailureDetector.isNodeConsideredFailed(peerId)) {
                return false;
            }
            return true;
        }
        return false;
    }

    /**
     * Updates commitIndex on the leader based on replicationIndex values from peers
     * 
     */
    public void updateCommitIndex() {
        /**
         * Example:
         * 5 node cluster(leader + 4 followers)
         * Leader has log entries upto index - 10 (lastLogIndex)
         * replicationIndex for followers: [8,9,7,10]
         * Leader calculates:
         * for n = 10; only 2 nodes have it (leader + 1 follower) => not majority
         * for n = 9; 3 nodes have it(leader+2 followers) => majority
         */
        try {
            int currentTerm = getCurrentTerm();
            System.out.println("Starting updateCommitIndex, current commitIndex=" + commitIndex);
            for (int n = logManager.getLastLogIndex(); n > commitIndex; n--) {
                System.out.println("Checking n=" + n);
                // Only commit log entries from currentTerm
                if (logManager.getLogEntryTerm(n) != currentTerm) {
                    continue;
                }

                if (countNodesWithLogIndex(n) >= getMajoritySize()) {
                    // majority has this replicationIndex(n)
                    System.out.println("COMMITTING n=" + n);
                    commitIndex = n;
                    applyLogEntries();
                    break;
                } else {
                    System.out.println("Not enough for majority at n=" + n);
                }
            }
            log.info("Final commitIndex=" + commitIndex);
        } catch (Exception e) {
            log.error("Error: updateCommitIndex", e);
        }

    }

    /**
     * 
     * Count the number of nodes(including self) that have replicated upto the given
     * log index
     * Handles joint consensus configurations
     */
    private int countNodesWithLogIndex(int logIndex) {
        int count = 1; // count self;
        if (!inJointConsensus) {
            for (String peerId : peerIds) {
                if (replicationIndex.getOrDefault(peerId, 0) >= logIndex) {
                    count++;
                }
            }
            return count;
        } else {
            // For joint consensus, we need separate counts for each configuration
            Map<String, Boolean> counted = new HashMap<>();

            // Count nodes in old configuration
            int oldCount = oldConfiguration.contains(nodeId) ? 1 : 0;
            for (String peerId : oldConfiguration) {
                if (!peerId.equals(nodeId) && replicationIndex.getOrDefault(peerId, 0) >= logIndex) {
                    oldCount++;
                    counted.put(peerId, true);
                }
            }

            // count nodes in new configuration
            int newCount = newConfiguration.contains(nodeId) ? 1 : 0;
            for (String peerId : newConfiguration) {
                if (!peerId.equals(nodeId) && replicationIndex.getOrDefault(peerId, 0) >= logIndex) {
                    if (!counted.containsKey(peerId)) {
                        newCount++;
                    }
                }
            }
            // check if we have majority in both configurations
            int oldMajority = oldConfiguration.size() / 2 + 1;
            int newMajority = newConfiguration.size() / 2 + 1;
            if (oldCount >= oldMajority && newCount >= newMajority) {
                return Math.max(oldCount, newCount);
            } else {
                return 0;// not enough for joint consensus
            }
        }

    }

    private int getMajoritySize() {
        if (!inJointConsensus) {
            return (peerIds.size() + 1) / 2 + 1;
        } else {
            // for joint consensus we need max of both majorities
            int oldMajority = oldConfiguration.size() / 2 + 1;
            int newMajority = newConfiguration.size() / 2 + 1;
            return Math.max(oldMajority, newMajority);
        }
    }

    /**
     * Applies log entries that have been committed but not yet applied
     */
    private void applyLogEntries() {
        log.info("applyLogEntries called lastApplied: {}, commitIndex: {}", lastApplied, commitIndex);
        while (lastApplied < commitIndex) {
            lastApplied++;
            RaftLogEntry entry = logManager.getLogEntry(lastApplied);
            // apply to state machine (To be implemented)
            // System.out.println(nodeId + ": Applied log entry " + lastApplied + ": " +
            // entry.getCommand());
            if (stateMachine != null) {

                try {
                    String serialized = entry.getCommand();
                    log.info("serialized command: {}", serialized);
                    StateMachineCommand command = null;
                    if (serialized.startsWith("CONFIG_CHANGE")) {
                        log.info("Deserializing ConfigChangeCommand: {}", serialized);
                        command = ConfigChangeCommand.deserialize(serialized);
                        applyConfigChangeCommand((ConfigChangeCommand) command);
                    } else {
                        command = stateMachine.deserializeCommand(entry.getCommand());
                        boolean success = stateMachine.apply(command);
                        if (!success) {
                            log.error("Failed to apply command at index: {}", lastApplied);
                        }
                    }

                } catch (IllegalArgumentException e) {
                    log.error("Failed to deserialize command at index: {}", lastApplied, e);
                } catch (Exception e) {
                    log.error("Error applying command at index: {}", lastApplied, e);
                }
            } else {
                log.warn("StateMachine is null", stateMachine);
            }
        }
    }

    private void applyConfigChangeCommand(ConfigChangeCommand command) {
        try {
            ConfigChangeType type = command.getType();
            if (type == ConfigChangeType.JOINT) {
                // Enter joint consensus
                log.info("{}: Entering joint consensus - old: {}, new: {}", nodeId, command.getOldConfig(),
                        command.getNewConfig());
                oldConfiguration = new ArrayList<>(command.getOldConfig());
                newConfiguration = new ArrayList<>(command.getNewConfig());
                inJointConsensus = true;

                // A new node becomes caught up when it sees the joint consensus entry
                if (isJoining && newConfiguration.contains(nodeId) && !oldConfiguration.contains(nodeId)) {
                    log.info("{}: node is now caught up and part of joint configuration", nodeId);
                    isCaughtUp = true;
                }
                // if we're the leader update nextIndex and replciationIndex for any new peers
                if (state == RaftState.LEADER) {
                    int nextIdx = logManager.getLastLogIndex() + 1;
                    for (String peerId : newConfiguration) {
                        if (!peerId.equals(nodeId) && !oldConfiguration.contains(peerId)) {
                            log.info("{}: Initializing nextIndex for new peer {} to {}", nodeId, peerId, nextIdx);
                            if (!nextIndex.containsKey(peerId)) {
                                nextIndex.put(peerId, nextIdx);
                                replicationIndex.put(peerId, 0);
                            }
                        }

                    }
                    for (String peerId : oldConfiguration) {
                        if (!newConfiguration.contains(peerId)) {
                            log.info("{}: Removing replication state for peer {} being removed in joint consensus",
                                    nodeId, peerId);
                            nextIndex.remove(peerId);
                            replicationIndex.remove(peerId);
                        }
                    }
                }
            } else if (type == ConfigChangeType.FINAL) {
                log.info("{}: switching to final configuration: {}", nodeId, command.getNewConfig());

                // update peer list
                List<String> newPeers = new ArrayList<>();
                for (String peer : command.getNewConfig()) {
                    if (!peer.equals(nodeId)) {
                        newPeers.add(peer);
                    }
                }
                this.peerIds = newPeers;
                this.inJointConsensus = false;
                this.oldConfiguration = null;
                this.newConfiguration = null;

                // if this node was joining and is now in final configuration
                // it's done joining
                if (isJoining && command.getNewConfig().contains(nodeId)) {
                    log.info("{}: node successfully joined in final configuration", nodeId);
                    isJoining = false;
                }
                // if we're the leader update nextIndex and replciationIndex for any new peers
                if (state == RaftState.LEADER) {
                    int nextIdx = logManager.getLastLogIndex() + 1;
                    for (String peerId : peerIds) {
                        if (!nextIndex.containsKey(peerId)) {
                            nextIndex.put(peerId, nextIdx);
                            replicationIndex.put(peerId, 0);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error: applyConfigChangeCommand", e);
        }

    }

    /**
     * Append a new command to the log. only valid when node is leader
     * 
     * @param command Command to append
     * @return true if command was accepted, false otherwise
     */
    public synchronized boolean appendCommand(String command) {
        if (state != RaftState.LEADER) {
            return false;
        }
        int currentTerm = getCurrentTerm();
        int index = logManager.append(currentTerm, command);
        System.out.println(nodeId + " : Append command at index " + index + " command: " + command);
        // Replicate to followers immediately
        sendHeartBeats();
        return true;
    }

    public synchronized boolean appendCommand(StateMachineCommand command) {
        log.debug("AppendCommand called");
        if (state != RaftState.LEADER) {
            return false;
        }
        try {
            int currentTerm = getCurrentTerm();
            String serialized = command.serialize();
            log.debug("Applying command {} to {} ", serialized, nodeId);
            int index = logManager.append(currentTerm, serialized);
            boolean isConfigChange = command instanceof ConfigChangeCommand;
            if (isConfigChange) {
                ConfigChangeCommand configCmd = (ConfigChangeCommand) command;
                log.info("{}: Locally applying config change immediately on leader: {}",
                        nodeId, configCmd.getType());
                applyConfigChangeCommand(configCmd);
            }
            // replicate to followers immediately
            sendHeartBeats();
            return true;
        } catch (Exception e) {
            log.error("Failed to append command", e);
            return false;
        }
    }

    /**
     * Handles incoming RequestVote RPC
     * 
     */
    public RequestVoteResponse handleRequestVote(RequestVoteRequest request) {
        int currentTerm = getCurrentTerm();

        if (isJoining && !isCaughtUp) {
            log.info("{} node is joining and not caught up, denying vote to {}", nodeId, request.getCandidateId());
            return new RequestVoteResponse(currentTerm, false);
        }

        if (request.getTerm() > currentTerm) {
            log.info("{}: Updating term from {} to {} due to vote request",
                    nodeId, logManager.getCurrentTerm(), request.getTerm());
            logManager.saveCurrentTerm(request.getTerm());
            logManager.saveVotedFor(null);
            transitionTo(RaftState.FOLLOWER);
        }

        switch (state) {
            case FOLLOWER:
                return handleRequestVoteAsFollower(request);
            case CANDIDATE:
                return handleRequestVoteAsCandidate(request);
            case LEADER:
                return handleRequestVoteAsLeader(request);

            default:
                throw new IllegalStateException("Unknown state: " + state);

        }
    }

    /**
     * Handles incoming AppendEntries RPC
     */
    public AppendEntriesResponse handleAppendEntries(AppendEntriesRequest request) {
        // System.out.println(nodeId + ": RECEIVED AppendEntries from " +
        // request.getLeaderId() +
        // " (term=" + request.getTerm() + ", my term=" + currentTerm + ")");
        // if request term is lower, reject immediately
        int currentTerm = getCurrentTerm();
        log.debug("{}: Received AppendEntries from {} (leader term: {}, my term: {})",
                nodeId, request.getLeaderId(), request.getTerm(), currentTerm);
        if (request.getTerm() >= currentTerm && request.getLeaderId() != null) {
            this.leaderId = request.getLeaderId();
        }
        if (request.getTerm() < currentTerm) {
            System.out.println(nodeId + ": REJECTING AppendEntries - lower term");
            log.debug("{}: Rejecting AppendEntries from {} - lower term ({} < {})",
                    nodeId, request.getLeaderId(), request.getTerm(), currentTerm);
            return new AppendEntriesResponse(currentTerm, false);
        }
        if (request.getTerm() > currentTerm) {
            log.info("{}: Stepping down due to higher term in AppendEntries from {}",
                    nodeId, request.getLeaderId());
            currentTerm = request.getTerm();
            logManager.saveCurrentTerm(currentTerm);
            logManager.saveVotedFor(null);
            transitionTo(RaftState.FOLLOWER);
        }
        switch (state) {
            case FOLLOWER:
                return handleAppendEntriesAsFollower(request);

            case CANDIDATE:
                return handleAppendEntriesAsCandidate(request);
            case LEADER:
                return handleAppendEntriesAsLeader(request);

            default:
                throw new IllegalStateException("Unknown state: " + state);

        }
    }

    private RequestVoteResponse handleRequestVoteAsFollower(RequestVoteRequest request) {
        // if term < currentTerm reject
        int currentTerm = getCurrentTerm();
        String votedFor = getVotedFor();
        if (request.getTerm() < currentTerm) {
            return new RequestVoteResponse(currentTerm, false);
        }
        // If already voted for someone else in this term, reject
        if (votedFor != null && !votedFor.equals(request.getCandidateId())) {
            return new RequestVoteResponse(currentTerm, false);
        }
        // Check if candidate's log is as upto-date as ours
        int lastLogIndex = logManager.getLastLogIndex();
        int lastLogTerm = logManager.getLastLogTerm();
        boolean isLogUptoDate = request.getLastLogTerm() > lastLogTerm
                || (request.getLastLogTerm() == lastLogTerm && request.getLastLogIndex() >= lastLogIndex);
        if (isLogUptoDate) {
            // Grant vote
            votedFor = request.getCandidateId();
            logManager.saveVotedFor(votedFor);
            // Reset election timer when granting vote
            electionTimer.reset();
            log.info("{}: Sending vote response to {} - term={}, granted={}",
                    nodeId, request.getCandidateId(), currentTerm, true);
            return new RequestVoteResponse(currentTerm, true);
        } else {
            log.info("{}: Sending vote response to {} - term={}, granted={}",
                    nodeId, request.getCandidateId(), currentTerm, false);
            return new RequestVoteResponse(currentTerm, false);
        }

    }

    private RequestVoteResponse handleRequestVoteAsCandidate(RequestVoteRequest request) {
        // As candidate we've already voted for ourself

        return new RequestVoteResponse(getCurrentTerm(), false);
    }

    private RequestVoteResponse handleRequestVoteAsLeader(RequestVoteRequest request) {
        // As a leader we don't vote for others in our term
        return new RequestVoteResponse(getCurrentTerm(), false);
    }

    private AppendEntriesResponse handleAppendEntriesAsFollower(AppendEntriesRequest request) {
        // Reset election timer as we heard from leader;
        // System.out.println(nodeId + ": RESETTING election timer (follower received
        // heartbeat)");
        int currentTerm = getCurrentTerm();
        log.debug("{}: Resetting election timer after AppendEntries from {}",
                nodeId, request.getLeaderId());
        electionTimer.reset();
        this.leaderId = request.getLeaderId();
        // Check log consistency
        if (request.getPrevLogIndex() >= 0) {
            boolean hasEntry = logManager.hasLogEntry(request.getPrevLogIndex());
            if (!hasEntry || logManager.getLogEntryTerm(request.getPrevLogIndex()) != request.getPrevLogTerm()) {
                log.warn("{}: REJECTING AppendEntries - log consistency check failed", nodeId);
                log.debug("REJECTING AppendEntries request: {}, hasEntry: {}", request, hasEntry);
                log.debug("logManager logEntryTerm : {}", logManager.getLogEntryTerm(request.getPrevLogIndex()));

                return new AppendEntriesResponse(currentTerm, false);
            }

        }
        // Append entries to log
        logManager.appendEntries(request.getPrevLogIndex(), request.getEntries());
        // logManager.getEntriesFrom(0);
        // Update commit index
        if (request.getLeaderCommit() > commitIndex) {
            commitIndex = Math.min(request.getLeaderCommit(), logManager.getLastLogIndex());
            applyLogEntries();
        }
        return new AppendEntriesResponse(currentTerm, true);
    }

    private AppendEntriesResponse handleAppendEntriesAsCandidate(AppendEntriesRequest request) {
        int currentTerm = getCurrentTerm();
        if (request.getTerm() >= currentTerm) {
            transitionTo(RaftState.FOLLOWER);
            return handleAppendEntriesAsFollower(request);
        }
        return new AppendEntriesResponse(currentTerm, false);
    }

    private AppendEntriesResponse handleAppendEntriesAsLeader(AppendEntriesRequest request) {
        int currentTerm = getCurrentTerm();
        if (request.getTerm() == currentTerm) {
            System.err.println("Received appendEntries from another leader in the same term");
            log.warn("{} Received append entries from another leader {} for the term {}", nodeId,
                    request.getLeaderId(), request.getTerm());

        }
        return new AppendEntriesResponse(currentTerm, false);
    }

    // Getters for testing
    public RaftState getState() {
        return state;
    }

    public String getNodeId() {
        return nodeId;
    }

    public List<String> getPeers() {
        return peerIds;
    }

    /**
     * Updates peer list of this node
     */
    public void updatePeers(List<String> newPeers) {
        log.info("{} : Updating peers from {} to {}", nodeId, peerIds, newPeers);
        List<String> oldPeers = new ArrayList<>(this.peerIds);
        this.peerIds = new ArrayList<>(newPeers);

        if (state == RaftState.LEADER) {
            for (String peerId : newPeers) {
                if (!oldPeers.contains(peerId)) {
                    log.info("{}: Initializing replication state for new peer: {}", nodeId, peerId);
                    int nextIdx = logManager.getLastLogIndex() + 1;
                    nextIndex.put(peerId, nextIdx);
                    replicationIndex.put(peerId, 0);
                    // Immediately start replication to new peer
                    log.info("{}: Starting immediate replication to new peer :{}", nodeId, peerId);
                    sendAppendEntries(peerId);
                }
            }
        }

        List<String> peersToRemove = new ArrayList<>();
        for (String peerId : nextIndex.keySet()) {
            if (!newPeers.contains(peerId)) {
                peersToRemove.add(peerId);
            }
        }
        for (String peerId : peersToRemove) {
            log.info("{}: Removing replication state for peer : {}", nodeId, peerId);
            nextIndex.remove(peerId);
            replicationIndex.remove(peerId);
        }
        System.out.println(nodeId + " updated peers list: " + newPeers);
        log.info("{}: Peer update complete. Current peers: {}", nodeId, peerIds);

    }

    public boolean isLeader() {
        return state == RaftState.LEADER;
    }

    public String getLeaderId() {
        return isLeader() ? nodeId : leaderId;
    }

    public StateMachine getStateMachine() {
        return stateMachine;
    }

    public LogManager getLogManager() {
        return logManager;
    }

}
