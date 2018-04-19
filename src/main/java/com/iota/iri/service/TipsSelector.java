package com.iota.iri.service;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.Stack;

import com.iota.iri.Milestone;
import com.iota.iri.TransactionValidator;
import com.iota.iri.controllers.MilestoneViewModel;
import com.iota.iri.controllers.TransactionViewModel;
import com.iota.iri.model.Hash;
import com.iota.iri.storage.Tangle;
import com.iota.iri.utils.ConsistencyChecker;
import com.iota.iri.utils.CumulativeWeight;
import com.iota.iri.utils.RandomWalk;

public class TipsSelector {

    private final Tangle tangle;
    private final Milestone milestone;
    private final TransactionValidator transactionValidator;
    private final boolean testnet;
    private final int milestoneStartIndex;
    private final int maxDepth;
    private final Set<Hash> maxDepthOk;
    private final RandomWalk randomWalk;
    private final double defaultAlpha = 0.001d;

    public TipsSelector(final Tangle tangle,
                       final TransactionValidator transactionValidator,
                       final Milestone milestone,
                       final int maxDepth,
                       final boolean testnet,
                       final int milestoneStartIndex) {
        this.tangle = tangle;
        this.transactionValidator = transactionValidator;
        this.milestone = milestone;
        this.maxDepth = maxDepth;
        this.testnet = testnet;
        this.milestoneStartIndex = milestoneStartIndex;
        this.maxDepthOk = new HashSet<>();
        this.randomWalk = new RandomWalk(new SecureRandom());
    }

    public void init() { }

    public void shutdown() { }

    public Hash[] getTransactionsToApprove(int depth) throws Exception {
        Hash[] tips = new Hash[2];
        if (depth > maxDepth) {
            depth = maxDepth;
        }

        Hash entryPoint;
        Set<Hash> validTransactions;

        milestone.latestSnapshot.rwlock.readLock().lock();
        try {
            entryPoint = entryPoint(depth);
            validTransactions = getValidAncestors(entryPoint);
        } finally {
            milestone.latestSnapshot.rwlock.readLock().unlock();
        }

        Map<Hash, Long> cumulativeWeights = 
            CumulativeWeight.calculateCumulativeWeight(validTransactions, entryPoint);

        for (int i = 0; i < 2; i++) {
            tips[i] = randomWalk.walk(
                tangle, 
                entryPoint,
                defaultAlpha,
                cumulativeWeights,
                t -> validTransactions.contains(t) && 
                    ConsistencyChecker.areConsistent(
                        tangle, t, tips[0] == null ? entryPoint : tips[0]));
        }

        return tips;
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
