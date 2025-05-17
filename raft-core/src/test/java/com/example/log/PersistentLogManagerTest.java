package com.example.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.model.RaftLogEntry;
import com.example.persistence.FilePersistenceManager;
import com.example.persistence.PersistenceManager;

public class PersistentLogManagerTest {
    private String testDir = "test-persistent-log";
    private PersistenceManager persistenceManager;
    private PersistentLogManager logManager;

    @BeforeEach
    public void setUp() throws IOException {
        // Create a test directory
        new File(testDir).mkdirs();
        persistenceManager = new FilePersistenceManager(testDir, "test-node");
        persistenceManager.initialize();
        logManager = new PersistentLogManager(persistenceManager);
    }

    @AfterEach
    public void tearDown() throws IOException {
        persistenceManager.close();
        deleteDirectory(new File(testDir));
    }

    @Test
    public void testAppendAndGetEntries() throws IOException {
        int term1 = 1;
        String command1 = "SET key1 value1";
        int index1 = logManager.append(term1, command1);
        assertEquals(0, index1);

        int term2 = 2;
        String command2 = "SET key2 value2";
        int index2 = logManager.append(term2, command2);
        assertEquals(1, index2);

        // Test getting entries
        assertEquals(2, logManager.size());
        RaftLogEntry entry1 = logManager.getLogEntry(index1);
        assertEquals(index1, entry1.getIndex());
        assertEquals(term1, entry1.getTerm());
        assertEquals(command1, entry1.getCommand());

        RaftLogEntry entry2 = logManager.getLogEntry(index2);
        assertEquals(index2, entry2.getIndex());
        assertEquals(term2, entry2.getTerm());
        assertEquals(command2, entry2.getCommand());
    }

    @Test
    public void testGetEntriesFrom() throws IOException {
        logManager.append(1, "Set key1 value1");
        logManager.append(1, "Set key2 value2");
        logManager.append(2, "DELETE key1");
        List<RaftLogEntry> entries = logManager.getEntriesFrom(1);
        assertEquals(2, entries.size());
        assertEquals(1, entries.get(0).getIndex());
        assertEquals(2, entries.get(1).getIndex());

    }

    @Test
    public void testAppendEntries() throws IOException {
        RaftLogEntry entry1 = new RaftLogEntry(0, 1, "SET key1 value1");
        RaftLogEntry entry2 = new RaftLogEntry(1, 1, "SET key2 value2");
        List<RaftLogEntry> entries = List.of(entry1, entry2);

        // Append Entries
        boolean result = logManager.appendEntries(-1, entries);
        assertTrue(result);
        assertEquals(2, logManager.size());
        assertEquals(entry1.getCommand(), logManager.getLogEntry(0).getCommand());
        assertEquals(entry2.getCommand(), logManager.getLogEntry(1).getCommand());
    }

    @Test
    public void testLastLogIndexAndTerm() throws IOException {
        assertEquals(-1, logManager.getLastLogIndex());
        assertEquals(0, logManager.getLastLogTerm());

        logManager.append(1, "SET key1 value1");
        assertEquals(0, logManager.getLastLogIndex());
        assertEquals(1, logManager.getLastLogTerm());

        logManager.append(2, "SET key2 value2");
        assertEquals(1, logManager.getLastLogIndex());
        assertEquals(2, logManager.getLastLogTerm());

    }

    @Test
    public void testPersistenceAcrossRestarts() throws IOException {
        logManager.append(1, "set key1 value1");
        logManager.append(2, "set key2 value2");

        // close and recreate logManager(simulating restart)
        logManager = new PersistentLogManager(persistenceManager);

        assertEquals(2, logManager.size());
        assertEquals(1, logManager.getLastLogIndex());
        assertEquals(2, logManager.getLastLogTerm());

        RaftLogEntry entry0 = logManager.getLogEntry(0);
        assertEquals(0, entry0.getIndex());
        assertEquals(1, entry0.getTerm());
        assertEquals("set key1 value1", entry0.getCommand());
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
