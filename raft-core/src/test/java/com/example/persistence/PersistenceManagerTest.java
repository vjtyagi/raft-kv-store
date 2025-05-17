package com.example.persistence;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.model.RaftLogEntry;

public class PersistenceManagerTest {

    private List<RaftLogEntry> sampleEntries;

    @BeforeEach
    public void setup() {
        sampleEntries = Arrays.asList(
                new RaftLogEntry(0, 1, "SET key1 value1"),
                new RaftLogEntry(1, 1, "SET key2 value2"),
                new RaftLogEntry(2, 2, "DELETE key1"));
    }

    @Test
    public void testPersistenceManagerContract() {
        // A Persistence Manager should be able to
        // 1. Save and load current Term
        // 2. Save and load votedFor field
        // 3. Save and load log entries(both batch and individual)
        // 4. Initialize and close resources
    }

}
