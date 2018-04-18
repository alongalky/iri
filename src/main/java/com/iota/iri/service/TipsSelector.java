package com.iota.iri.service;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.Stack;
import java.util.Arrays;

import com.iota.iri.LedgerValidator;
import com.iota.iri.Milestone;
import com.iota.iri.TransactionValidator;
import com.iota.iri.controllers.MilestoneViewModel;
import com.iota.iri.controllers.TransactionViewModel;
import com.iota.iri.model.Hash;
import com.iota.iri.storage.Tangle;
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
    }

    public void init() { }

    public void shutdown() { }

    public Hash[] getTransactionsToApprove(int depth) throws Exception {
        Hash[] tips = new Hash[2];
        if (depth > maxDepth) {
            depth = maxDepth;
        }

        if (milestone.latestSolidSubtangleMilestoneIndex <= milestoneStartIndex &&
                milestone.latestMilestoneIndex != milestoneStartIndex) {
            return null;
        }

        milestone.latestSnapshot.rwlock.readLock().lock();

        Hash entryPoint = entryPoint(depth);
        try {
            Set<Hash> visitedHashes = new HashSet<>();
            Map<Hash, Long> diff = new HashMap<>();

            for (int i = 0; i < 2; i++) {
                long startTime = System.nanoTime();

                final SecureRandom random = new SecureRandom();

                Map<Hash, Long> ratings = new HashMap<>();
                Set<Hash> analyzedTips = new HashSet<>();
                Set<Hash> maxDepthOk = new HashSet<>();
                try {
                    analyzedTips.clear();
                    if (ledgerValidator.updateDiff(visitedHashes, diff, entryPoint)) {
                        tips[i] = randomWalk(visitedHashes, diff, entryPoint, ratings, maxDepth, maxDepthOk, random);
                    }
                    else {
                        throw new RuntimeException("Entry point transaction failed consistency check: " + entryPoint.toString());
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    log.error("Encountered error: " + e.getLocalizedMessage());
                    throw e;
                } finally {
                    API.incEllapsedTime_getTxToApprove(System.nanoTime() - startTime);
                }

                //update world view, so next tips selected will be inter-consistent
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

    Hash randomWalk(final Set<Hash> visitedHashes, final Map<Hash, Long> diff, final Hash start, final Map<Hash, Long> ratings, final int maxDepth, final Set<Hash> maxDepthOk, Random rnd) throws Exception {
        Hash tip = start, tail = tip;
        Hash[] tips;
        Set<Hash> tipSet;
        Set<Hash> analyzedTips = new HashSet<>();
        int traversedTails = 0;
        TransactionViewModel transactionViewModel;
        int approverIndex;
        double ratingWeight;
        double[] walkRatings;
        Map<Hash, Long> myDiff = new HashMap<>(diff);
        Set<Hash> myApprovedHashes = new HashSet<>(visitedHashes);

        while (tip != null) {
            transactionViewModel = TransactionViewModel.fromHash(tangle, tip);
            tipSet = transactionViewModel.getApprovers(tangle).getHashes();
            if (transactionViewModel.getCurrentIndex() == 0) {
                if (transactionViewModel.getType() == TransactionViewModel.PREFILLED_SLOT) {
                    log.info("Reason to stop: transactionViewModel == null");
                    messageQ.publish("rtsn %s", transactionViewModel.getHash());
                    break;
                }
                else if (!transactionValidator.checkSolidity(transactionViewModel.getHash(), false)) {
                    log.info("Reason to stop: !checkSolidity");
                    messageQ.publish("rtss %s", transactionViewModel.getHash());
                    break;
                }
                else if (belowMaxDepth(transactionViewModel.getHash(), maxDepth, maxDepthOk)) {
                    log.info("Reason to stop: belowMaxDepth");
                    break;
                }
                else if (!ledgerValidator.updateDiff(myApprovedHashes, myDiff, transactionViewModel.getHash())) {
                    log.info("Reason to stop: !LedgerValidator");
                    messageQ.publish("rtsv %s", transactionViewModel.getHash());
                    break;
                }
                // set the tail here!
                tail = tip;
                traversedTails++;
            }
            if (tipSet.size() == 0) {
                log.info("Reason to stop: TransactionViewModel is a tip");
                messageQ.publish("rtst %s", tip);
                break;
            }
            else if (tipSet.size() == 1) {
                Iterator<Hash> hashIterator = tipSet.iterator();
                if (hashIterator.hasNext()) {
                    tip = hashIterator.next();
                }
                else {
                    tip = null;
                }
            }
            else {
                // walk to the next approver
                tips = tipSet.toArray(new Hash[tipSet.size()]);
                if (!ratings.containsKey(tip)) {
                    serialUpdateRatings(myApprovedHashes, tip, ratings, analyzedTips);
                    analyzedTips.clear();
                }

                walkRatings = new double[tips.length];
                double maxRating = 0;
                long tipRating = ratings.get(tip);
                for (int i = 0; i < tips.length; i++) {
                    //transition probability = ((Hx-Hy)^-3)/maxRating
                    walkRatings[i] = Math.pow(tipRating - ratings.getOrDefault(tips[i], 0L), -3);
                    maxRating += walkRatings[i];
                }
                ratingWeight = rnd.nextDouble() * maxRating;
                for (approverIndex = tips.length; approverIndex-- > 1; ) {
                    ratingWeight -= walkRatings[approverIndex];
                    if (ratingWeight <= 0) {
                        break;
                    }
                }
                tip = tips[approverIndex];
                if (transactionViewModel.getHash().equals(tip)) {
                    log.info("Reason to stop: transactionViewModel==itself");
                    messageQ.publish("rtsl %s", transactionViewModel.getHash());
                    break;
                }
            }
        }
        log.info("Tx traversed to find tip: " + traversedTails);
        messageQ.publish("mctn %d", traversedTails);
        return tail;
    }

    void serialUpdateRatings(final Set<Hash> visitedHashes, final Hash txHash, final Map<Hash, Long> ratings, final Set<Hash> analyzedTips) throws Exception {
        Stack<Hash> hashesToRate = new Stack<>();
        hashesToRate.push(txHash);
        Hash currentHash;
        boolean addedBack;
        while (!hashesToRate.empty()) {
            currentHash = hashesToRate.pop();
            TransactionViewModel transactionViewModel = TransactionViewModel.fromHash(tangle, currentHash);
            addedBack = false;
            Set<Hash> approvers = transactionViewModel.getApprovers(tangle).getHashes();
            for (Hash approver : approvers) {
                if (ratings.get(approver) == null && !approver.equals(currentHash)) {
                    if (!addedBack) {
                        addedBack = true;
                        hashesToRate.push(currentHash);
                    }
                    hashesToRate.push(approver);
                }
            }
            if (!addedBack && analyzedTips.add(currentHash)) {
                long rating = approvers.stream().map(ratings::get).filter(Objects::nonNull)
                        .reduce((a, b) -> capSum(a, b, Long.MAX_VALUE / 2)).orElse(0L);
                ratings.put(currentHash, rating);
            }
        }
    }

    Set<Hash> updateHashRatings(Hash txHash, Map<Hash, Set<Hash>> ratings, Set<Hash> analyzedTips) throws Exception {
        Set<Hash> rating;
        if (analyzedTips.add(txHash)) {
            TransactionViewModel transactionViewModel = TransactionViewModel.fromHash(tangle, txHash);
            rating = new HashSet<>(Collections.singleton(txHash));
            Set<Hash> approverHashes = transactionViewModel.getApprovers(tangle).getHashes();
            for (Hash approver : approverHashes) {
                rating.addAll(updateHashRatings(approver, ratings, analyzedTips));
            }
            ratings.put(txHash, rating);
        }
        else {
            if (ratings.containsKey(txHash)) {
                rating = ratings.get(txHash);
            }
            else {
                rating = new HashSet<>();
            }
        }
        return rating;
    }

    long recursiveUpdateRatings(Hash txHash, Map<Hash, Long> ratings, Set<Hash> analyzedTips) throws Exception {
        long rating = 1;
        if (analyzedTips.add(txHash)) {
            TransactionViewModel transactionViewModel = TransactionViewModel.fromHash(tangle, txHash);
            Set<Hash> approverHashes = transactionViewModel.getApprovers(tangle).getHashes();
            for (Hash approver : approverHashes) {
                rating = capSum(rating, recursiveUpdateRatings(approver, ratings, analyzedTips), Long.MAX_VALUE / 2);
            }
            ratings.put(txHash, rating);
        }
        else {
            if (ratings.containsKey(txHash)) {
                rating = ratings.get(txHash);
            }
            else {
                rating = 0;
            }
        }
        return rating;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    boolean belowMaxDepth(Hash tip, int depth, Set<Hash> maxDepthOk) throws Exception {
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

    static long capSum(long a, long b, long max) {
        if (a + b < 0 || a + b > max) {
            return max;
        }
        return a + b;
    }
}
