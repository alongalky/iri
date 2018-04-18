package com.iota.iri.utils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.iota.iri.model.Hash;

import org.junit.Assert;
import org.junit.Test;

public class CumulativeWeightsTest {
    @Test
    public void calculateCumulativeWeightReturnsValuesForAllTransactions() {
        Hash[] hashValues = new Hash[] {
            new Hash("AAAAAWWIECDGDDZXHGJNMEVJNSVOSMECPPVRPEVRZFVIZYNNXZNTOTJOZNGCZNQVSPXBXTYUJUOXYASLS"),
            new Hash("BBBBBWWIECDGDDZXHGJNMEVJNSVOSMECPPVRPEVRZFVIZYNNXZNTOTJOZNGCZNQVSPXBXTYUJUOXYASLS"),
        };
        Set<Hash> transactions = new HashSet<Hash>(Arrays.asList(hashValues));

        Map<Hash, Long> weights = CumulativeWeight.calculateCumulativeWeight(transactions, hashValues[0]);

        for (Hash tx : transactions) {
          Assert.assertTrue(weights.containsKey(tx));
          System.out.println("Asserting!");
        }
    }
}
