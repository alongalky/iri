package com.iota.iri.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.iota.iri.LedgerValidator;
import com.iota.iri.Milestone;
import com.iota.iri.Snapshot;
import com.iota.iri.TransactionValidator;
import com.iota.iri.conf.Configuration;
import com.iota.iri.controllers.TipsViewModel;
import com.iota.iri.controllers.TransactionViewModel;
import com.iota.iri.model.Hash;
import com.iota.iri.network.TransactionRequester;
import com.iota.iri.storage.Tangle;
import com.iota.iri.storage.rocksDB.RocksDBPersistenceProvider;
import com.iota.iri.zmq.MessageQ;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static com.iota.iri.controllers.TransactionViewModelTest.getRandomTransactionHash;
import static com.iota.iri.controllers.TransactionViewModelTest.getRandomTransactionTrits;
import static com.iota.iri.controllers.TransactionViewModelTest.getRandomTransactionWithTrunkAndBranch;

/**
/**
 * Created by paul on 4/27/17.
 */
public class TipsSelectorTest {

    private static final TemporaryFolder dbFolder = new TemporaryFolder();
    private static final TemporaryFolder logFolder = new TemporaryFolder();
    private static Tangle tangle;
    private static TipsSelector tipsSelector;

    @Test
    public void capSum() throws Exception {
        long a = 0, b, max = Long.MAX_VALUE/2;
        for(b = 0; b < max; b+= max/100) {
            a = TipsSelector.capSum(a, b, max);
            Assert.assertTrue("a should never go above max", a <= max);
        }
    }

    @AfterClass
    public static void tearDown() throws Exception {
        tangle.shutdown();
        dbFolder.delete();
    }

    @BeforeClass
    public static void setUp() throws Exception {
        tangle = new Tangle();
        dbFolder.create();
        logFolder.create();
        tangle.addPersistenceProvider(new RocksDBPersistenceProvider(dbFolder.getRoot().getAbsolutePath(), logFolder.getRoot().getAbsolutePath(),1000));
        tangle.init();
        TipsViewModel tipsViewModel = new TipsViewModel();
        MessageQ messageQ = new MessageQ(0, null, 1, false);
        TransactionRequester transactionRequester = new TransactionRequester(tangle, messageQ);
        TransactionValidator transactionValidator = new TransactionValidator(tangle, tipsViewModel, transactionRequester,
                messageQ, Long.parseLong(Configuration.GLOBAL_SNAPSHOT_TIME));
        int milestoneStartIndex = Integer.parseInt(Configuration.MAINNET_MILESTONE_START_INDEX);
        int numOfKeysInMilestone = Integer.parseInt(Configuration.MAINNET_NUM_KEYS_IN_MILESTONE);
        Milestone milestone = new Milestone(tangle, Hash.NULL_HASH, Snapshot.init(
                Configuration.MAINNET_SNAPSHOT_FILE, Configuration.MAINNET_SNAPSHOT_SIG_FILE, false).clone(),
                transactionValidator, false, messageQ, numOfKeysInMilestone,
                milestoneStartIndex, true);
        LedgerValidator ledgerValidator = new LedgerValidator(tangle, milestone, transactionRequester, messageQ);
        tipsSelector = new TipsSelector(tangle, ledgerValidator, transactionValidator, milestone,
                15, messageQ, false, milestoneStartIndex);
    }

    @Test
    public void updateLinearRatingsTestWorks() throws Exception {
        TransactionViewModel transaction, transaction1, transaction2;
        transaction = new TransactionViewModel(getRandomTransactionTrits(), getRandomTransactionHash());
        transaction1 = new TransactionViewModel(getRandomTransactionWithTrunkAndBranch(transaction.getHash(), transaction.getHash()), getRandomTransactionHash());
        transaction2 = new TransactionViewModel(getRandomTransactionWithTrunkAndBranch(transaction1.getHash(), transaction1.getHash()), getRandomTransactionHash());
        transaction.store(tangle);
        transaction1.store(tangle);
        transaction2.store(tangle);
        Map<Hash, Set<Hash>> ratings = new HashMap<>();
        tipsSelector.updateHashRatings(transaction.getHash(), ratings, new HashSet<>());
        Assert.assertEquals(ratings.get(transaction.getHash()).size(), 3);
        Assert.assertEquals(ratings.get(transaction1.getHash()).size(), 2);
        Assert.assertEquals(ratings.get(transaction2.getHash()).size(), 1);
    }

    @Test
    public void updateRatingsTestWorks() throws Exception {
        TransactionViewModel transaction, transaction1, transaction2, transaction3, transaction4;
        transaction = new TransactionViewModel(getRandomTransactionTrits(), getRandomTransactionHash());
        transaction1 = new TransactionViewModel(getRandomTransactionWithTrunkAndBranch(transaction.getHash(), transaction.getHash()), getRandomTransactionHash());
        transaction2 = new TransactionViewModel(getRandomTransactionWithTrunkAndBranch(transaction1.getHash(), transaction1.getHash()), getRandomTransactionHash());
        transaction3 = new TransactionViewModel(getRandomTransactionWithTrunkAndBranch(transaction2.getHash(), transaction1.getHash()), getRandomTransactionHash());
        transaction4 = new TransactionViewModel(getRandomTransactionWithTrunkAndBranch(transaction2.getHash(), transaction3.getHash()), getRandomTransactionHash());
        transaction.store(tangle);
        transaction1.store(tangle);
        transaction2.store(tangle);
        transaction3.store(tangle);
        transaction4.store(tangle);
        Map<Hash, Set<Hash>> ratings = new HashMap<>();
        tipsSelector.updateHashRatings(transaction.getHash(), ratings, new HashSet<>());
        Assert.assertEquals(ratings.get(transaction.getHash()).size(), 5);
        Assert.assertEquals(ratings.get(transaction1.getHash()).size(),4);
        Assert.assertEquals(ratings.get(transaction2.getHash()).size(), 3);
    }

    //@Test
    public void testUpdateRatingsTime() throws Exception {
        int max = 100001;
        long time;
        List<Long> times = new LinkedList<>();
        for(int size = 1; size < max; size *= 10) {
            time = ratingTime(size);
            times.add(time);
        }
        Assert.assertEquals(1, 1);
    }

    public long ratingTime(int size) throws Exception {
        Hash[] hashes = new Hash[size];
        hashes[0] = getRandomTransactionHash();
        new TransactionViewModel(getRandomTransactionTrits(), hashes[0]).store(tangle);
        Random random = new Random();
        for(int i = 1; i < hashes.length; i ++) {
            hashes[i] = getRandomTransactionHash();
            new TransactionViewModel(getRandomTransactionWithTrunkAndBranch(hashes[i-random.nextInt(i)-1], hashes[i-random.nextInt(i)-1]), hashes[i]).store(tangle);
        }
        Map<Hash, Long> ratings = new HashMap<>();
        long start = System.currentTimeMillis();
        tipsSelector.serialUpdateRatings(new HashSet<>(), hashes[0], ratings, new HashSet<>());
        return System.currentTimeMillis() - start;
    }
}