package com.example.log;

import java.beans.PersistenceDelegate;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.example.model.RaftLogEntry;
import com.example.persistence.PersistenceManager;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PersistentLogManager implements LogManager {
    private final PersistenceManager persistenceManager;
    private final ReadWriteLock logLock = new ReentrantReadWriteLock();
    private final ReadWriteLock stateLock = new ReentrantReadWriteLock();
    private List<RaftLogEntry> logCache; // In-memory cache of log entries
    private volatile int currentTerm = 0;
    private volatile String votedFor = null;

    public PersistentLogManager(PersistenceManager persistenceManager) throws IOException {
        this.persistenceManager = persistenceManager;
        this.logCache = new ArrayList<>(persistenceManager.loadLogEntries());
        loadInitialState();
    }

    private void loadInitialState() throws IOException {
        stateLock.readLock().lock();
        try {
            this.currentTerm = persistenceManager.loadCurrentTerm();
            this.votedFor = persistenceManager.loadVotedFor();
        } finally {
            stateLock.readLock().unlock();
        }
    }

    @Override
    public int getLastLogIndex() {
        logLock.readLock().lock();
        try {
            return logCache.isEmpty() ? -1 : logCache.size() - 1;
        } finally {
            logLock.readLock().unlock();
        }
    }

    @Override
    public int getLastLogTerm() {
        logLock.readLock().lock();
        try {
            return logCache.isEmpty() ? 0 : logCache.get(logCache.size() - 1).getTerm();
        } finally {
            logLock.readLock().unlock();
        }
    }

    @Override
    public int getLogEntryTerm(int index) {
        logLock.readLock().lock();
        try {
            if (index < 0 || index >= logCache.size()) {
                return 0;
            }
            return logCache.get(index).getTerm();
        } finally {
            logLock.readLock().unlock();
        }
    }

    @Override
    public boolean hasLogEntry(int index) {
        logLock.readLock().lock();
        try {
            return index >= 0 && index < logCache.size();
        } finally {
            logLock.readLock().unlock();
        }
    }

    @Override
    public RaftLogEntry getLogEntry(int index) {
        logLock.readLock().lock();
        try {
            if (index < 0 || index >= logCache.size()) {
                throw new IndexOutOfBoundsException("Invalid log index: " + index);
            }
            return logCache.get(index);
        } finally {
            logLock.readLock().unlock();
        }
    }

    @Override
    public List<RaftLogEntry> getEntriesFrom(int fromIndex) {
        logLock.readLock().lock();
        try {
            if (fromIndex >= logCache.size()) {
                return Collections.emptyList();
            }
            fromIndex = Math.max(0, fromIndex);
            List<RaftLogEntry> result = new ArrayList<>(logCache.size() - fromIndex);
            for (int i = fromIndex; i < logCache.size(); i++) {
                result.add(logCache.get(i));
            }
            return result;
        } finally {
            logLock.readLock().unlock();
        }
    }

    @Override
    public boolean appendEntries(int prevLogIndex, List<RaftLogEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return true; // could be heartbeat
        }
        logLock.writeLock().lock();
        try {
            // check if prevLogIndex entry exists and matches
            if (prevLogIndex >= 0) {
                if (prevLogIndex >= logCache.size()) {
                    return false;
                }
            }
            // Start appending at the position after prevLogIndex
            int newEntryIndex = prevLogIndex + 1;
            // Process Each entry
            for (RaftLogEntry entry : entries) {
                if (newEntryIndex < logCache.size()) {
                    // check for conflict with existing entry
                    RaftLogEntry existingEntry = logCache.get(newEntryIndex);
                    if (existingEntry.getTerm() != entry.getTerm()) {
                        // Conflict - remove this and following entries
                        logCache.subList(newEntryIndex, logCache.size()).clear();
                        logCache.add(entry);
                    }
                } else {
                    // newIndex = logCache.size() => simply append
                    logCache.add(entry);
                }
                newEntryIndex++;
            }

            // Persist the updated log
            try {
                persistenceManager.saveLogEntries(logCache);
            } catch (IOException e) {
                System.err.println("Failed to persist log entries " + e.getMessage());
                throw new RuntimeException("Failed to persist log entries ", e);
            }
            return true;
        } finally {
            logLock.writeLock().unlock();
        }
    }

    @Override
    public int append(int term, String command) {
        logLock.writeLock().lock();
        try {
            int newIndex = logCache.size();
            RaftLogEntry entry = new RaftLogEntry(newIndex, term, command);
            logCache.add(entry);

            // Persist the new entry
            try {
                persistenceManager.saveLogEntry(entry);
            } catch (IOException e) {
                System.err.println("Failed to persist log entry:  " + e.getMessage());
                throw new RuntimeException("Failed to persist log entry", e);
            }
            return newIndex;

        } finally {
            logLock.writeLock().unlock();
        }
    }

    @Override
    public int size() {
        logLock.readLock().lock();
        try {
            return logCache.size();
        } finally {
            logLock.readLock().unlock();
        }
    }

    @Override
    public void saveCurrentTerm(int term) {
        log.info("Save currentTerm : {}", term);
        stateLock.writeLock().lock();
        try {
            if (term > currentTerm) {
                this.currentTerm = term;
                this.votedFor = null;
                persistenceManager.saveCurrentTerm(term);
                persistenceManager.saveVotedFor(votedFor);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to persist current term", e);
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    @Override
    public int getCurrentTerm() {
        stateLock.readLock().lock();
        try {
            return currentTerm;
        } finally {
            stateLock.readLock().unlock();
        }
    }

    @Override
    public void saveVotedFor(String votedFor) {
        log.info("Save votedFor: {}", votedFor);
        stateLock.writeLock().lock();
        try {
            if ((this.votedFor == null && votedFor != null)
                    || (this.votedFor != null && !this.votedFor.equals(votedFor))) {
                this.votedFor = votedFor;
                persistenceManager.saveVotedFor(votedFor);
            }

        } catch (IOException e) {
            throw new RuntimeException("Failed to persist votedFor", e);
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    @Override
    public String getVotedFor() {
        stateLock.readLock().lock();
        try {
            return votedFor;
        } finally {
            stateLock.readLock().unlock();
        }
    }

}
