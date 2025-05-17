package com.example.persistence;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.example.model.RaftLogEntry;

public class FilePersistenceManager implements PersistenceManager {

    // why we're using so many sub directories
    // can you visually show how this persistence is strucuted
    private final String baseDir;
    private final String nodeId;
    private final String stateDir;
    private final String logDir;

    // Why separate files ? explain the logic
    private final String currentTermFile;
    private final String votedForFile;
    private final String logFile;

    private final ReadWriteLock stateLock = new ReentrantReadWriteLock();
    private final ReadWriteLock logLock = new ReentrantReadWriteLock();

    public FilePersistenceManager(String baseDir, String nodeId) {
        this.baseDir = baseDir;
        this.nodeId = nodeId;
        this.stateDir = baseDir + File.separator + nodeId + File.separator + "state";
        this.logDir = baseDir + File.separator + nodeId + File.separator + "log";

        this.currentTermFile = stateDir + File.separator + "current_term";
        this.votedForFile = stateDir + File.separator + "voted_for";
        this.logFile = logDir + File.separator + "raft_log";

    }

    @Override
    public void saveCurrentTerm(int term) throws IOException {
        stateLock.writeLock().lock();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(currentTermFile))) {
            writer.write(String.valueOf(term));
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    @Override
    public int loadCurrentTerm() throws IOException {
        stateLock.readLock().lock();
        try (BufferedReader reader = new BufferedReader(new FileReader(currentTermFile))) {
            String line = reader.readLine();
            return (line != null && !line.isEmpty()) ? Integer.parseInt(line) : 0;
        } catch (NumberFormatException e) {
            return 0;
        } finally {
            stateLock.readLock().unlock();
        }
    }

    @Override
    public void saveVotedFor(String votedFor) throws IOException {
        stateLock.writeLock().lock();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(votedForFile))) {
            writer.write(votedFor != null ? votedFor : "");
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    @Override
    public String loadVotedFor() throws IOException {
        stateLock.readLock().lock();
        try (BufferedReader reader = new BufferedReader(new FileReader(votedForFile))) {
            String line = reader.readLine();
            return (line != null && !line.isEmpty()) ? line : null;
        } finally {
            stateLock.readLock().unlock();
        }
    }

    @Override
    public void saveLogEntries(List<RaftLogEntry> entries) throws IOException {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        logLock.writeLock().lock();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile))) {
            for (RaftLogEntry entry : entries) {
                writer.write(serializeLogEntry(entry));
                writer.newLine();
            }
        } finally {
            logLock.writeLock().unlock();
        }
    }

    private String serializeLogEntry(RaftLogEntry entry) {
        return entry.getIndex() + "," + entry.getTerm() + "," + entry.getCommand();
    }

    @Override
    public void saveLogEntry(RaftLogEntry entry) throws IOException {
        if (entry == null) {
            return;
        }
        logLock.writeLock().lock();
        try {
            List<RaftLogEntry> entries = loadLogEntries();
            boolean added = false;
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).getIndex() == entry.getIndex()) {
                    entries.set(i, entry);
                    added = true;
                    break;
                }
            }
            if (!added) {
                entries.add(entry);
            }
            saveLogEntries(entries);
        } finally {
            logLock.writeLock().unlock();
        }
    }

    @Override
    public List<RaftLogEntry> loadLogEntries() throws IOException {
        List<RaftLogEntry> entries = new ArrayList<>();
        logLock.readLock().lock();
        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    RaftLogEntry entry = deserializeLogEntry(line);
                    if (entry != null) {
                        entries.add(entry);
                    }
                }
            }

        } finally {
            logLock.readLock().unlock();
        }
        return entries;
    }

    private RaftLogEntry deserializeLogEntry(String serialized) {
        try {
            String[] parts = serialized.split(",", 3);
            if (parts.length != 3) {
                return null;
            }
            int index = Integer.parseInt(parts[0]);
            int term = Integer.parseInt(parts[1]);
            String command = parts[2];
            return new RaftLogEntry(index, term, command);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public void initialize() throws IOException {
        // Create Directories if they don't exist
        Files.createDirectories(Paths.get(stateDir));
        Files.createDirectories(Paths.get(logDir));

        // Initialize files if they don't exist
        File termFile = new File(currentTermFile);
        if (!termFile.exists()) {
            saveCurrentTerm(0);
        }
        File voteFile = new File(votedForFile);
        if (!voteFile.exists()) {
            saveVotedFor(null);
        }

        File log = new File(logFile);
        if (!log.exists()) {
            log.createNewFile();
        }
    }

    @Override
    public void close() throws IOException {
        // Nothing to do
    }

}
