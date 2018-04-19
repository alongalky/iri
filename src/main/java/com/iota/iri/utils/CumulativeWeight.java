package com.iota.iri.utils;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.iota.iri.model.Hash;

public interface CumulativeWeight {
    public static Map<Hash, Long> calculateCumulativeWeight(Set<Hash> validTransactions, Hash entryPoint) {
        // TBD: this is a stub, currently vies all validTransactions weight 1
        return validTransactions.stream()
            .collect(Collectors.toMap(Function.identity(), hash -> new Long(1)));
    }
}
