package com.iota.iri.utils;

import com.iota.iri.model.Hash;
import com.iota.iri.storage.Tangle;

public interface ConsistencyChecker {
    public static boolean areConsistent(
            Tangle tangle,
            Hash tx1,
            Hash tx2) {
        return true;
    }
}
