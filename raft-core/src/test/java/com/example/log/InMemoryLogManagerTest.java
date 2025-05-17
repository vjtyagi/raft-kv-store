package com.example.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.model.RaftLogEntry;

public class InMemoryLogManagerTest {
    private InMemoryLogManager logManager;

    @BeforeEach
    public void setup() {
        logManager = new InMemoryLogManager();
    }

    @Test
    public void testInitialState() {
        assertEquals(-1, logManager.getLastLogIndex());
        assertEquals(0, logManager.getLastLogTerm());
        assertEquals(0, logManager.size());
        assertFalse(logManager.hasLogEntry(0));
        assertTrue(logManager.getEntriesFrom(0).isEmpty());
    }

    @Test
    public void testAppendSingleEntry() {
        int index = logManager.append(1, "SET x=1");
        assertEquals(0, index);
        assertEquals(0, logManager.getLastLogIndex());
        assertEquals(1, logManager.getLastLogTerm());
        assertEquals(1, logManager.size());
        assertTrue(logManager.hasLogEntry(0));

        RaftLogEntry entry = logManager.getLogEntry(0);
        assertEquals(0, entry.getIndex());
        assertEquals(1, entry.getTerm());
        assertEquals("SET x=1", entry.getCommand());
    }

    @Test
    public void testAppendMultipleEntries() {
        logManager.append(1, "SET x=1");
        logManager.append(1, "SET y=2");
        logManager.append(2, "SET z=3");
        assertEquals(2, logManager.getLastLogIndex());
        assertEquals(2, logManager.getLastLogTerm());
        assertEquals(3, logManager.size());

        RaftLogEntry entry1 = logManager.getLogEntry(0);
        RaftLogEntry entry2 = logManager.getLogEntry(1);
        RaftLogEntry entry3 = logManager.getLogEntry(2);

        assertEquals(1, entry1.getTerm());
        assertEquals(1, entry2.getTerm());
        assertEquals(2, entry3.getTerm());

        assertEquals("SET x=1", entry1.getCommand());
        assertEquals("SET y=2", entry2.getCommand());
        assertEquals("SET z=3", entry3.getCommand());

    }

    @Test
    public void testGetLogEntryWithInvalidIndex() {
        logManager.append(1, "SET x=1");
        assertThrows(IndexOutOfBoundsException.class, () -> logManager.getLogEntry(1));
    }

    @Test
    public void testGetEntriesFrom() {
        logManager.append(1, "SET x=1");
        logManager.append(1, "SET y=2");
        logManager.append(2, "SET z=3");
        List<RaftLogEntry> entries = logManager.getEntriesFrom(1);

        assertEquals(2, entries.size());
        assertEquals("SET y=2", entries.get(0).getCommand());
        assertEquals("SET z=3", entries.get(1).getCommand());

        // When index is out of range, should return empty list
        entries = logManager.getEntriesFrom(3);
        assertTrue(entries.isEmpty());
        // Test when index is negative, should start from 0
        entries = logManager.getEntriesFrom(-1);
        assertEquals(3, entries.size());
    }

    @Test
    public void testGetLogEntryTerm() {
        logManager.append(1, "SET x=1");
        logManager.append(2, "SET y=2");

        assertEquals(1, logManager.getLogEntryTerm(0));
        assertEquals(2, logManager.getLogEntryTerm(1));

        // out of range indices should return 0
        assertEquals(0, logManager.getLogEntryTerm(-1));
        assertEquals(0, logManager.getLogEntryTerm(2));
    }

    @Test
    public void testAppendEntries() {
        logManager.append(1, "SET x=1");
        logManager.append(1, "SET y=2");

        // Create new entries to append
        List<RaftLogEntry> newEntries = new ArrayList<>();
        newEntries.add(new RaftLogEntry(2, 2, "SET z=3"));
        newEntries.add(new RaftLogEntry(3, 2, "SET w=4"));

        boolean success = logManager.appendEntries(1, newEntries);
        assertTrue(success);
        assertEquals(4, logManager.size());
        assertEquals("SET z=3", logManager.getLogEntry(2).getCommand());
        assertEquals("SET w=4", logManager.getLogEntry(3).getCommand());
    }

    @Test
    public void testAppendEntriesWithConflict() {
        logManager.append(1, "SET x=1");
        logManager.append(1, "SET y=2");
        logManager.append(1, "SET z=3");

        // Create conflicting entries
        List<RaftLogEntry> conflictingEntries = new ArrayList<>();
        conflictingEntries.add(new RaftLogEntry(1, 2, "SET a=1"));
        conflictingEntries.add(new RaftLogEntry(2, 2, "SET b=2"));

        boolean success = logManager.appendEntries(0, conflictingEntries);
        assertTrue(success);
        assertEquals(3, logManager.size());
        assertEquals("SET a=1", logManager.getLogEntry(1).getCommand());
        assertEquals("SET b=2", logManager.getLogEntry(2).getCommand());
        assertEquals(2, logManager.getLastLogTerm());

    }

    @Test
    public void testAppendEntriesWithInvaildPrevLogIndex() {
        logManager.append(1, "SET x=1");

        // Try to append prevLogIndex that doesn't exist
        List<RaftLogEntry> entries = new ArrayList<>();
        entries.add(new RaftLogEntry(1, 1, "SET y=2"));

        boolean success = logManager.appendEntries(5, entries);
        assertFalse(success);
        assertEquals(1, logManager.size());
    }

    @Test
    public void testAppendEmptyEntries() {
        boolean success = logManager.appendEntries(0, Collections.emptyList());
        assertTrue(success);
        assertEquals(0, logManager.size());
    }

    @Test
    public void testHasLogEntry() {
        logManager.append(1, "SET x=1");
        assertTrue(logManager.hasLogEntry(0));
    }
}
