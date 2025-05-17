package com.example.log;

import java.util.List;

import com.example.model.RaftLogEntry;

/*
 * Manages the Raft log entries and provides operations for log manipulation
 */
public interface LogManager {
    /*
     * Returns the index of the last log entry
     * 
     * @return the last log index, or -1 if log is empty
     */
    int getLastLogIndex();

    /**
     * Return the term of last log entry
     */
    int getLastLogTerm();

    /**
     * Returns the term of the log entry at the specified index
     * 
     * @param index the index of the log entry
     * @return the term of the log entry
     * @throws IndexOutOfBoundsException if index is invalid
     */
    int getLogEntryTerm(int index);

    /**
     * Checks if log entry exists at specified index
     * 
     * @param index the index to check
     * @param true  if log entry exists, false otherwise
     */
    boolean hasLogEntry(int index);

    /**
     * Returns log entry at specified index
     * 
     * @param index the index of log entry
     * @return the log entry
     * @throws IndexOutOfBoundsException if index is invalid
     */
    RaftLogEntry getLogEntry(int index);

    /**
     * Returns all log entries starting from the specified index
     * 
     * @param fromIndex the starting index(inclusive)
     * @return list of log entries
     */
    List<RaftLogEntry> getEntriesFrom(int fromIndex);

    /**
     * Appends new log entries after specified index
     * Any existing entreis after prevLogIndex will be removed if they conflict
     * 
     * @param prevLogIndex the index of log entry that precedes the new entries
     * @param entries      the new entries to append
     * @return true if successfull, false otherwise
     */
    boolean appendEntries(int prevLogIndex, List<RaftLogEntry> entries);

    /**
     * Adds a new log entry to the end of the log
     * 
     * @param term    the current term
     * @param command the command to add
     * @return the index of newly added entry
     */
    int append(int term, String command);

    /**
     * Returns the total number of log entries
     * 
     * @return the size of the log
     */
    int size();

    void saveCurrentTerm(int term);

    int getCurrentTerm();

    void saveVotedFor(String votedFor);

    String getVotedFor();

    default void incrementCurrentTerm() {
        saveCurrentTerm(getCurrentTerm() + 1);
    }

}
