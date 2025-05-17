package com.example.log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.example.model.RaftLogEntry;

public class InMemoryLogManager implements LogManager {
    private final List<RaftLogEntry> log = new ArrayList<>();
    private final ReadWriteLock logLock = new ReentrantReadWriteLock();
    private final ReadWriteLock stateLock = new ReentrantReadWriteLock();
    private volatile int currentTerm = 0;
    private volatile String votedFor = null;

    @Override
    public int getLastLogIndex() {
        logLock.readLock().lock();
        try {
            return log.isEmpty() ? -1 : log.size() - 1;
        } finally {
            logLock.readLock().unlock();
        }
    }

    @Override
    public int getLastLogTerm() {
        logLock.readLock().lock();
        try {
            return log.isEmpty() ? 0 : log.get(log.size() - 1).getTerm();
        } finally {
            logLock.readLock().unlock();
        }
    }

    @Override
    public int getLogEntryTerm(int index) {
        logLock.readLock().lock();
        try {
            if (index < 0 || index >= log.size()) {
                return 0;
            }
            return log.get(index).getTerm();
        } finally {
            logLock.readLock().unlock();
        }
    }

    @Override
    public boolean hasLogEntry(int index) {
        logLock.readLock().lock();
        try {
            return index >= 0 && index < log.size();
        } finally {
            logLock.readLock().unlock();
        }
    }

    @Override
    public RaftLogEntry getLogEntry(int index) {
        logLock.readLock().lock();
        try {
            if (index < 0 || index >= log.size()) {
                throw new IndexOutOfBoundsException("Invalid log index: " + index);
            }
            return log.get(index);
        } finally {
            logLock.readLock().unlock();
        }
    }

    @Override
    public List<RaftLogEntry> getEntriesFrom(int fromIndex) {
        logLock.readLock().lock();
        try {
            if (fromIndex >= log.size()) {
                return Collections.emptyList();
            }
            fromIndex = Math.max(0, fromIndex);
            List<RaftLogEntry> result = new ArrayList<>(log.size() - fromIndex);
            for (int i = fromIndex; i < log.size(); i++) {
                result.add(log.get(i));
            }
            return result;
        } finally {
            logLock.readLock().unlock();
        }
    }

    @Override
    public boolean appendEntries(int prevLogIndex, List<RaftLogEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return true; // Nothing to append, could be heartbeat
        }
        logLock.writeLock().lock();
        try {
            // check if previous log entry exists and matches
            if (prevLogIndex >= 0) {
                if (prevLogIndex >= log.size()) {
                    return false; // Previous log entry doesn't exist
                }
                // Consistency check for preivous log entry already handled by RaftNode
            }
            // Start appending at the position after prevLogIndex
            int newEntryIndex = prevLogIndex + 1;

            // Process each entry
            for (RaftLogEntry entry : entries) {
                if (newEntryIndex < log.size()) {
                    // Check for conflict with existing entry
                    RaftLogEntry existingEntry = log.get(newEntryIndex);
                    if (existingEntry.getTerm() != entry.getTerm()) {
                        // Conflict - remove this and all following entries
                        log.subList(newEntryIndex, log.size()).clear();
                        log.add(entry);
                    }
                } else {
                    log.add(entry);
                }
                newEntryIndex++;
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
            int newIndex = log.size();
            log.add(new RaftLogEntry(newIndex, term, command));
            return newIndex;
        } finally {
            logLock.writeLock().unlock();
        }
    }

    @Override
    public int size() {
        logLock.readLock().lock();
        try {
            return log.size();
        } finally {
            logLock.readLock().unlock();
        }
    }

    @Override
    public void saveCurrentTerm(int term) {
        stateLock.writeLock().lock();
        try {
            this.currentTerm = term;
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    @Override
    public int getCurrentTerm() {
        stateLock.readLock().lock();
        try {
            return this.currentTerm;
        } finally {
            stateLock.readLock().unlock();
        }
    }

    @Override
    public void saveVotedFor(String votedFor) {
        stateLock.writeLock().lock();
        try {
            this.votedFor = votedFor;
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    @Override
    public String getVotedFor() {
        stateLock.readLock().lock();
        try {
            return this.votedFor;
        } finally {
            stateLock.readLock().unlock();
        }
    }

}
