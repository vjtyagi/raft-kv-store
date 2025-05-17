package com.example.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@Getter
@NoArgsConstructor
public class AppendEntriesResponse {
    private int term; // follower's current term ( used for leader to step down if stale)
    private boolean success; // True if follower accepts the entry

    @Override
    public String toString() {
        return "AppendEntriesResponse [term=" + term + ", success=" + success + "]";
    }

}
