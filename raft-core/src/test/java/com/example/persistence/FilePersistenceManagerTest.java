package com.example.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.model.RaftLogEntry;

public class FilePersistenceManagerTest {
    private String testDir = "test-raft-data";
    private PersistenceManager persistenceManager;

    @BeforeEach
    public void setUp() throws IOException {
        // Create a test directory
        Files.createDirectories(Paths.get(testDir));
        persistenceManager = new FilePersistenceManager(testDir, "test-node");
        persistenceManager.initialize();
    }

    @AfterEach
    public void tearDown() throws IOException {
        persistenceManager.close();
        deleteDirectory(new File(testDir));
    }

    @Test
    public void testSaveAndLoadCurrentTerm() throws IOException {
        int term = 5;
        persistenceManager.saveCurrentTerm(term);
        assertEquals(term, persistenceManager.loadCurrentTerm());
    }

    @Test
    public void testSaveAndLoadVotedFor() throws IOException {
        String votedFor = "node2";
        persistenceManager.saveVotedFor(votedFor);

        assertEquals(votedFor, persistenceManager.loadVotedFor());

        persistenceManager.saveVotedFor(null);
        assertNull(persistenceManager.loadVotedFor());
    }

    @Test
    public void testSaveAndLoadLogEntries() throws IOException {
        List<RaftLogEntry> entries = new ArrayList<>();
        entries.add(new RaftLogEntry(0, 1, "SET key1 value1"));
        entries.add(new RaftLogEntry(1, 1, "SET key2 value2"));

        // save entries
        persistenceManager.saveLogEntries(entries);

        // Load and verify
        List<RaftLogEntry> loadedEntries = persistenceManager.loadLogEntries();
        assertEquals(entries.size(), loadedEntries.size());
        for (int i = 0; i < entries.size(); i++) {
            RaftLogEntry original = entries.get(i);
            RaftLogEntry loaded = loadedEntries.get(i);
            assertEquals(original.getIndex(), loaded.getIndex());
            assertEquals(original.getTerm(), loaded.getTerm());
            assertEquals(original.getCommand(), loaded.getCommand());
        }
    }

    @Test
    public void testSaveAndLoadSingleLogEntry() throws IOException {
        RaftLogEntry entry = new RaftLogEntry(0, 1, "SET key1 value1");
        persistenceManager.saveLogEntry(entry);

        // Save another entry
        RaftLogEntry entry2 = new RaftLogEntry(1, 1, "SET key2 value2");
        persistenceManager.saveLogEntry(entry2);

        // Load and verify
        List<RaftLogEntry> loadedEntries = persistenceManager.loadLogEntries();
        assertEquals(2, loadedEntries.size());
        assertEquals(entry.getIndex(), loadedEntries.get(0).getIndex());
        assertEquals(entry.getTerm(), loadedEntries.get(0).getTerm());
        assertEquals(entry.getCommand(), loadedEntries.get(0).getCommand());

        assertEquals(entry2.getIndex(), loadedEntries.get(1).getIndex());
        assertEquals(entry2.getTerm(), loadedEntries.get(1).getTerm());
        assertEquals(entry2.getCommand(), loadedEntries.get(1).getCommand());

    }

    @Test
    public void testRecoveryAfterRestart() throws IOException {
        int term = 3;
        String votedFor = "node3";
        List<RaftLogEntry> entries = new ArrayList<>();
        entries.add(new RaftLogEntry(0, 1, "SET key1 value1"));
        entries.add(new RaftLogEntry(1, 2, "SET key2 value2"));

        persistenceManager.saveCurrentTerm(term);
        persistenceManager.saveVotedFor(votedFor);
        persistenceManager.saveLogEntries(entries);

        // close and create a new instance( simulating restart)
        persistenceManager.close();
        persistenceManager = new FilePersistenceManager(testDir, "test-node");
        persistenceManager.initialize();

        // Verify recovery
        assertEquals(term, persistenceManager.loadCurrentTerm());
        assertEquals(votedFor, persistenceManager.loadVotedFor());
        List<RaftLogEntry> loadedEntries = persistenceManager.loadLogEntries();
        assertEquals(entries.size(), loadedEntries.size());

    }

    private void deleteDirectory(File directory) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        directory.delete();
    }
}
