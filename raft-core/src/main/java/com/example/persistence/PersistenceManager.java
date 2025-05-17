package com.example.persistence;

import java.io.IOException;
import java.util.List;

import com.example.model.RaftLogEntry;

public interface PersistenceManager {
    /**
     * Saves the current term to persistent storage
     * 
     * @param term The current term
     * @throws IOException if an I/O error occurs
     */
    void saveCurrentTerm(int term) throws IOException;

    /**
     * Loads the current term from persistent storage
     * 
     * @return the persisted current term, or 0 if not found
     * @throws IOException
     */
    int loadCurrentTerm() throws IOException;

    /**
     * Saves the votedFor field to persistent storage
     * 
     * @param votedFor the ID of the node voted for in the current term, or null if
     *                 none
     * @throws IOException if an I/O error occurs
     */
    void saveVotedFor(String votedFor) throws IOException;

    /**
     * Loads votedFor field from persistent storage
     * 
     * @return The persisted votedFor value, or null if not found
     * @throws IOException if an I/O error occurs
     */
    String loadVotedFor() throws IOException;

    /**
     * Saves a list of log entires to persistent storage
     * 
     * @param entries the log entries to save
     * @throws IOException if an I/O error occurs
     */
    void saveLogEntries(List<RaftLogEntry> entries) throws IOException;

    /**
     * Saves a single log entry to persistent storage
     * 
     * @param entry the log entry to save
     * @throws IOException if an I/O error occurs
     */
    void saveLogEntry(RaftLogEntry entry) throws IOException;

    /**
     * Loads all log entries from persistent storage
     * 
     * @return the list of persisted log entries
     * @throws IOException if an I/O error occurs
     */
    List<RaftLogEntry> loadLogEntries() throws IOException;

    /**
     * Initializes the persistence system
     * 
     * @throws IOException if an I/O error occurs
     */
    void initialize() throws IOException;

    /**
     * Cleans up resources used by the persistence system
     * 
     * @throws IOException if an I/O error occurs
     */
    void close() throws IOException;

}
