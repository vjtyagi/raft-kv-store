package com.example.node;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

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

import ch.qos.logback.core.util.InterruptUtil;
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
                heartBeatTimer.stop();
                break;

            default:
                break;
        }
        state = newState;
        System.out.println(nodeId + " State: " + state);

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
        System.out.println(nodeId + ": Election timeout occurred in state " + state);
        log.info("{}: Election timeout occurred in state {}, current term: {}",
                nodeId, state, getCurrentTerm());
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
        // if (peerIds.isEmpty()) {
        // System.out.println(nodeId + ": single-node cluster, becoming leader
        // immediately");
        // log.debug("single-node cluster, {nodeId} becoming leader immediately",
        // nodeId);
        // transitionTo(RaftState.LEADER);
        // return;
        // }
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
        for (String peerId : peerIds) {
            CompletableFuture<RequestVoteResponse> future = rpcService.sendRequestVote(peerId, request);
            future.thenAccept(response -> processVoteResponse(peerId, response))
                    .exceptionally(e -> {
                        log.debug("Error sending requestvote to {}", peerId, e);
                        System.err
                                .println(nodeId + " : Error sending RequestVote to " + peerId + ": " + e.getMessage());
                        return null;
                    });
        }
    }

    private synchronized void processVoteResponse(String peerId, RequestVoteResponse response) {
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
            int totalNodes = peerIds.size() + 1;
            int majority = (totalNodes / 2) + 1;
            log.info("{}: Received vote from {} in term {}, vote count: {}/{} for majority",
                    nodeId, peerId, currentTerm, votes, majority);
            System.out.println(nodeId + ": vote count is now " + votes + "(need majority) + " + " for majority");
            // If majority, become leader
            if (votes >= majority) {
                transitionTo(RaftState.LEADER);
            }
        } else {
            log.debug("{}: Vote denied by {} in term {}", nodeId, peerId, currentTerm);
        }
    }

    /**
     * Initializes leader state when tarnsitioning to leader
     */
    private synchronized void becomeLeader() {
        int currentTerm = getCurrentTerm();
        System.out.println(nodeId + " Becoming leader for term " + currentTerm);
        this.leaderId = nodeId;
        // Initializes nextIndex and matchIndex for all peers
        int nextIdx = logManager.getLastLogIndex() + 1;
        for (String peerId : peerIds) {
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
        int currentTerm = getCurrentTerm();
        if (state != RaftState.LEADER) {
            log.warn("{}: Attempted to send heartbeats while not LEADER (state: {})",
                    nodeId, state);
            return;
        }
        System.out.println(nodeId + ": SENDING HEARTBEATS for term " + currentTerm);
        log.debug("{}: Sending heartbeats to {} peers for term {}",
                nodeId, peerIds.size(), currentTerm);
        for (String peerId : peerIds) {
            sendAppendEntries(peerId);
        }
    }

    /**
     * Send AppendEntries RPC to a specific peer
     */
    private void sendAppendEntries(String peerId) {
        // System.out.println("Sending appendEntries for " + peerId);
        int currentTerm = getCurrentTerm();
        int prevLogIndex = nextIndex.get(peerId) - 1;
        int prevLogTerm = prevLogIndex >= 0 ? logManager.getLogEntryTerm(prevLogIndex) : 0;
        int peerNextIndex = nextIndex.get(peerId);
        int peerReplicationIndex = replicationIndex.get(peerId);
        if (peerReplicationIndex == 0 && peerNextIndex > 1) {
            peerNextIndex = 0;
            nextIndex.put(peerId, 0);
        }
        // Get entries to send
        List<RaftLogEntry> entries = logManager.getEntriesFrom(nextIndex.get(peerId));
        log.debug("{}: Sending {} entries to {} starting at index {} (prevLogIndex={})",
                nodeId, entries.size(), peerId, peerNextIndex, prevLogIndex);
        // Create AppendEntriesRequest
        AppendEntriesRequest request = new AppendEntriesRequest(currentTerm, nodeId, prevLogIndex, prevLogTerm, entries,
                commitIndex);
        // Send RPC
        CompletableFuture<AppendEntriesResponse> future = rpcService.sendAppendEntries(peerId, request);
        future.thenAccept(response -> processAppendEntriesResponse(peerId, request, response))
                .exceptionally(e -> {
                    log.error("{nodeId}: Error sending appendEntries to {peerId}", nodeId, peerId, e);
                    System.err.println(nodeId + ": Error sending appendentries to " + peerId + ": " + e.getMessage());
                    return null;
                });
    }

    private synchronized void processAppendEntriesResponse(String peerId, AppendEntriesRequest request,
            AppendEntriesResponse response) {
        // ignore if no longer leader or term doesn't match

        // System.out.println("Leader: " + nodeId + " received appendEntries response
        // from " + peerId);
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
            // update nextIndex and matchIndex for successful append entries
            int newReplicationIndex = request.getPrevLogIndex() + request.getEntries().size();
            nextIndex.put(peerId, newReplicationIndex + 1);
            replicationIndex.put(peerId, newReplicationIndex);

            // Check if we can advance commit index(later)
            updateCommitIndex();

        } else {
            // Decrement nextIndex and retry
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
                    sendAppendEntries(peerId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            // sendAppendEntries(peerId);
        }
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
        int currentTerm = getCurrentTerm();
        System.out.println("Starting updateCommitIndex, current commitIndex=" + commitIndex);
        for (int n = logManager.getLastLogIndex(); n > commitIndex; n--) {
            System.out.println("Checking n=" + n);
            // Only commit log entries from currentTerm
            if (logManager.getLogEntryTerm(n) != currentTerm) {
                System.out.println("Skipping n=" + n + " (wrong term)");
                continue;
            }
            int count = 1; // count self
            for (String peerId : peerIds) {
                if (replicationIndex.get(peerId) >= n) {
                    count++;
                    System.out.println(peerId + " has replicated n=" + n);
                }
            }
            System.out.println("n=" + n + ", count=" + count + ", majority=" + ((peerIds.size() / 2) + 1));
            int totalNodes = peerIds.size() + 1;
            int majority = totalNodes / 2 + 1;

            if (count >= majority) {
                // majority has this replicationIndex(n)
                System.out.println("COMMITTING n=" + n);
                commitIndex = n;
                applyLogEntries();
                break;
            } else {
                System.out.println("Not enough for majority at n=" + n);
            }
        }
        System.out.println("Final commitIndex=" + commitIndex);
    }

    /**
     * Applies log entries that have been committed but not yet applied
     */
    private void applyLogEntries() {
        while (lastApplied < commitIndex) {
            lastApplied++;
            RaftLogEntry entry = logManager.getLogEntry(lastApplied);
            // apply to state machine (To be implemented)
            // System.out.println(nodeId + ": Applied log entry " + lastApplied + ": " +
            // entry.getCommand());
            if (stateMachine != null) {
                try {
                    StateMachineCommand command = stateMachine.deserializeCommand(entry.getCommand());
                    boolean success = stateMachine.apply(command);
                    if (!success) {
                        log.error("Failed to apply command at index: {}", lastApplied);
                    }
                } catch (IllegalArgumentException e) {
                    log.error("Failed to deserialize command at index: {}", lastApplied, e);
                } catch (Exception e) {
                    log.error("Error applying command at index: {}", lastApplied, e);
                }
            }
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
            log.info("{}: Append command at index {}", nodeId, index);
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
        System.out.println(
                nodeId + ": Received RequestVote from " + request.getCandidateId() + " for term " + request.getTerm());
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
