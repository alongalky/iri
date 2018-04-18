package com.iota.iri.service;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.Stack;
import java.util.stream.Collectors;

import com.iota.iri.LedgerValidator;
import com.iota.iri.Milestone;
import com.iota.iri.TransactionValidator;
import com.iota.iri.controllers.MilestoneViewModel;
import com.iota.iri.controllers.TransactionViewModel;
import com.iota.iri.model.Hash;
import com.iota.iri.storage.Tangle;
import com.iota.iri.utils.CumulativeWeight;
import com.iota.iri.zmq.MessageQ;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TipsSelector {

    private final Logger log = LoggerFactory.getLogger(TipsSelector.class);
    private final Tangle tangle;
    private final Milestone milestone;
    private final LedgerValidator ledgerValidator;
    private final TransactionValidator transactionValidator;
    private final MessageQ messageQ;
    private final boolean testnet;
    private final int milestoneStartIndex;
    private final int maxDepth;
    private Set<Hash> maxDepthOk;

    public TipsSelector(final Tangle tangle,
                       final LedgerValidator ledgerValidator,
                       final TransactionValidator transactionValidator,
                       final Milestone milestone,
                       final int maxDepth,
                       final MessageQ messageQ,
                       final boolean testnet,
                       final int milestoneStartIndex) {
        this.tangle = tangle;
        this.ledgerValidator = ledgerValidator;
        this.transactionValidator = transactionValidator;
        this.milestone = milestone;
        this.maxDepth = maxDepth;
        this.messageQ = messageQ;
        this.testnet = testnet;
        this.milestoneStartIndex = milestoneStartIndex;
        this.maxDepthOk = new HashSet<>();
    }

    public void init() { }

    public void shutdown() { }

    public Hash[] getTransactionsToApprove(int depth) throws Exception {
        Hash[] tips = new Hash[2];
        if (depth > maxDepth) {
            depth = maxDepth;
        }

        milestone.latestSnapshot.rwlock.readLock().lock();

        try {
            Hash entryPoint = entryPoint(depth);
            Set<Hash> validTransactions = getValidAncestors(entryPoint);
            Map<Hash, Long> cumulativeWeights = 
                CumulativeWeight.calculateCumulativeWeight(validTransactions, entryPoint);

            Set<Hash> visitedHashes = new HashSet<>();
            Map<Hash, Long> diff = new HashMap<>();

            for (int i = 0; i < 2; i++) {
                long startTime = System.nanoTime();

                final SecureRandom random = new SecureRandom();

                try {
                    tips[i] = randomWalk(
                            validTransactions, 
                            visitedHashes,
                            diff,
                            entryPoint,
                            cumulativeWeights,
                            random);
                } catch (Exception e) {
                    e.printStackTrace();
                    log.error("Encountered error: " + e.getLocalizedMessage());
                    throw e;
                } finally {
                    API.incEllapsedTime_getTxToApprove(System.nanoTime() - startTime);
                }

                // Update world view, so second selected tip will be consistent with the first
                if (tips[i] == null || !ledgerValidator.updateDiff(visitedHashes, diff, tips[i])) {
                    return null;
                }
            }

            if (ledgerValidator.checkConsistency(Arrays.asList(tips))) {
                return tips;
            }
        } finally {
            milestone.latestSnapshot.rwlock.readLock().unlock();
        }
        throw new RuntimeException("inconsistent tips pair selected");
    }

    private Hash entryPoint(final int depth) throws Exception {
        int milestoneIndex = Math.max(milestone.latestSolidSubtangleMilestoneIndex - depth - 1, 0);
        MilestoneViewModel milestoneViewModel =
                MilestoneViewModel.findClosestNextMilestone(tangle, milestoneIndex, testnet, milestoneStartIndex);
        if (milestoneViewModel != null && milestoneViewModel.getHash() != null) {
            return milestoneViewModel.getHash();
        }

        return milestone.latestSolidSubtangleMilestone;
    }

    Hash randomWalk(
            final Set<Hash> validTransactions,
            final Set<Hash> visitedHashes,
            final Map<Hash,
            Long> diff,
            final Hash entryPoint,
            final Map<Hash, Long> cumulativeWeights,
            Random rnd) throws Exception {
        Hash currentTxHash = entryPoint, tail = currentTxHash;
        Hash[] approverHashes;
        int traversedTails = 0;
        TransactionViewModel currentTx;
        int approverIndex;
        double ratingWeight;
        double[] walkRatings;
        Map<Hash, Long> myDiff = new HashMap<>(diff);
        Set<Hash> myApprovedHashes = new HashSet<>(visitedHashes);

        while (currentTxHash != null) {
            currentTx = TransactionViewModel.fromHash(tangle, currentTxHash);

            Set<Hash> approvers = currentTx.getApprovers(tangle).getHashes().stream()
                .filter(t -> validTransactions.contains(t))
                .collect(Collectors.toSet());

            if (currentTx.getCurrentIndex() == 0) {
                if (!ledgerValidator.updateDiff(myApprovedHashes, myDiff, currentTx.getHash())) {
                    log.info("Reason to stop: !LedgerValidator");
                    messageQ.publish("rtsv %s", currentTx.getHash());
                    break;
                }
                // set the tail here!
                tail = currentTxHash;
                traversedTails++;
            }
            if (approvers.size() == 0) {
                log.info("Reason to stop: TransactionViewModel is a tip");
                messageQ.publish("rtst %s", currentTxHash);
                break;
            }
            else {
                // walk to the next approver
                approverHashes = approvers.toArray(new Hash[approvers.size()]);

                walkRatings = new double[approverHashes.length];
                double maxRating = 0;
                long tipRating = cumulativeWeights.get(currentTxHash);
                for (int i = 0; i < approverHashes.length; i++) {
                    //transition probability = ((Hx-Hy)^-3)/maxRating
                    walkRatings[i] = Math.pow(tipRating - cumulativeWeights.getOrDefault(approverHashes[i], 0L), -3);
                    maxRating += walkRatings[i];
                }
                ratingWeight = rnd.nextDouble() * maxRating;
                for (approverIndex = approverHashes.length; approverIndex-- > 1; ) {
                    ratingWeight -= walkRatings[approverIndex];
                    if (ratingWeight <= 0) {
                        break;
                    }
                }
                currentTxHash = approverHashes[approverIndex];
            }
        }
        log.info("Tx traversed to find tip: " + traversedTails);
        messageQ.publish("mctn %d", traversedTails);
        return tail;
    }

    boolean belowMaxDepth(Hash tip, int depth) throws Exception {
        //if tip is confirmed stop
        if (TransactionViewModel.fromHash(tangle, tip).snapshotIndex() >= depth) {
            return false;
        }
        //if tip unconfirmed, check if any referenced tx is confirmed below maxDepth
        Queue<Hash> nonAnalyzedTransactions = new LinkedList<>(Collections.singleton(tip));
        Set<Hash> analyzedTranscations = new HashSet<>();
        Hash hash;
        while ((hash = nonAnalyzedTransactions.poll()) != null) {
            if (analyzedTranscations.add(hash)) {
                TransactionViewModel transaction = TransactionViewModel.fromHash(tangle, hash);
                if (transaction.snapshotIndex() != 0 && transaction.snapshotIndex() < depth) {
                    return true;
                }
                if (transaction.snapshotIndex() == 0) {
                    if (maxDepthOk.contains(hash)) {
                        //log.info("Memoization!");
                    }
                    else {
                        nonAnalyzedTransactions.offer(transaction.getTrunkTransactionHash());
                        nonAnalyzedTransactions.offer(transaction.getBranchTransactionHash());
                    }
                }
            }
        }
        maxDepthOk.add(tip);
        return false;
    }

    private Set<Hash> getValidAncestors(Hash entryPoint) throws Exception {
        Set<Hash> ancestors = new HashSet<>();

        Stack<Hash> stack = new Stack<>();
        stack.push(entryPoint);
        while (!stack.empty()) {
            Hash currentTxHash = stack.pop();

            TransactionViewModel currentTx = TransactionViewModel.fromHash(tangle, currentTxHash);
            Set<Hash> approvers = currentTx.getApprovers(tangle).getHashes();
            for (Hash approver : approvers) {
                if (!ancestors.contains(approver) && isValid(approver)) {
                    stack.push(approver);
                }
            }

            ancestors.add(currentTxHash);
        }

        return ancestors;
    }

    private boolean isValid(Hash transactionHash) throws Exception {
        TransactionViewModel transaction = TransactionViewModel.fromHash(tangle, transactionHash);
        
        // Validations for tail transactions only
        if (transaction.getCurrentIndex() == 0) {
            if (transaction.getType() == TransactionViewModel.PREFILLED_SLOT) {
                return false;
            }
            if (!transactionValidator.checkSolidity(transactionHash, false)) {
                return false;
            }
            if (belowMaxDepth(transactionHash, maxDepth)) {
                return false;
            }
        }

        return true;
    }
}
