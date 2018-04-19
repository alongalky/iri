package com.iota.iri.utils;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.iota.iri.controllers.TransactionViewModel;
import com.iota.iri.model.Hash;
import com.iota.iri.service.TipsSolidifier;
import com.iota.iri.storage.Tangle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RandomWalk {
    public static Logger log = LoggerFactory.getLogger(TipsSolidifier.class);

    private SecureRandom random;
    
    public RandomWalk(SecureRandom random) {
        this.random = random;
    }

    // isValid contains all the consistency / verification checks
    public Hash walk (
        Tangle tangle,
        Hash entryPoint,
        double alpha,
        Map<Hash, Long> cumulativeWeights,
        Predicate<Hash> isValid
    ) throws Exception {
        Hash currentTxHash = entryPoint;
        Hash currentTailTx = currentTxHash;
        int traversedTails = 0;

        while (currentTxHash != null) {
            TransactionViewModel currentTx = TransactionViewModel.fromHash(tangle, currentTxHash);

            if (currentTx.getCurrentIndex() == 0) {
                currentTailTx = currentTxHash;
                traversedTails++;
            }

            Set<Hash> approvers = currentTx.getApprovers(tangle).getHashes().stream()
                .filter(isValid::test).collect(Collectors.toSet());

            // walk to the next approver
            List<Hash> approverHashes = approvers.stream().collect(Collectors.toList());

            List<Long> approverWeights = approverHashes.stream()
              .map(t -> cumulativeWeights.get(t))
              .collect(Collectors.toList());

            currentTxHash = chooseApprover(approverHashes, approverWeights, alpha);
        }

        log.info("Tx traversed to find tip: " + traversedTails);
        return currentTailTx;
    }

    public Hash chooseApprover (
            List<Hash> approvers,
            List<Long> cumulativeWeights,
            double alpha) {
        
        if (approvers.size() == 0) {
            return null;
        }
        
        long maxCumulativeWeight = cumulativeWeights.stream().reduce(Long.MIN_VALUE, Math::max);

        // Normalize the weights to make the largest weight zero. This avoids
        // numerical issues such as exp(-100) == 0.
        List<Long> normalizedCumulativeWeights = cumulativeWeights.stream()
            .map(w -> w - maxCumulativeWeight)
            .collect(Collectors.toList());

        List<Double> weights = normalizedCumulativeWeights.stream()
            .map(w -> Math.exp(alpha * w))
            .collect(Collectors.toList());
        
        return weightedChoice(approvers, weights);
    }

    public Hash weightedChoice(List<Hash> items, List<Double> weights) {
        double sum = weights.stream().reduce(0d, (a,b) -> a + b);
        double rand = this.random.nextDouble() * sum;

        double cumSum = weights.get(0);
        for (int i=1; i < items.size(); i++) {
            if (rand < cumSum) {
                return items.get(i-1);
            }
            cumSum += weights.get(i);
        }

        return items.get(items.size() - 1);
    }
}
