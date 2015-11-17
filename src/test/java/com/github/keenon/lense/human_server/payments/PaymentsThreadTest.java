package com.github.keenon.lense.human_server.payments;

import com.github.keenon.lense.human_server.HumanSourceServer;
import com.pholser.junit.quickcheck.ForAll;
import com.pholser.junit.quickcheck.generator.InRange;
import org.junit.contrib.theories.Theories;
import org.junit.contrib.theories.Theory;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Created by keenon on 10/14/15.
 *
 * Tests a payments thread against a whole bunch of weird abuses. Looking for eventual exactly-once semantics on payment
 * processing.
 */
@RunWith(Theories.class)
public class PaymentsThreadTest {
    @Theory
    public void testQueries(@ForAll(sampleSize = 4) @InRange(maxInt = 500, minInt = 5) int numWorkers) throws Exception {
        // Give everyone something standard to synchronize on
        HumanSourceServer hss = new HumanSourceServer();
        new Thread(hss).start();

        Map<String, Integer> numberOfTimesAttempted = new HashMap<>();
        Map<String, Integer> numberOfTimesPaid = new HashMap<>();
        Map<String, Double> amountPaid = new HashMap<>();

        // Pay workers who stay more than 3 seconds

        PaymentsThread.minimumStayingTime = 3000;

        // We set this here, since payments round up to the nearest penny

        PaymentsThread.perUnitBonus = 0.01;

        // Launch payments thread

        Random r = new Random();
        PaymentsThread paymentsThread = new PaymentsThread((HistoricalDatabase.JobRecord jr, Double amount) -> {
            assert(jr.workerID != null);
            assert(jr.assignmentID != null);

            int previousAttempts;
            synchronized (this) {
                previousAttempts = numberOfTimesAttempted.getOrDefault(jr.workerID, 0);
                numberOfTimesAttempted.put(jr.workerID, previousAttempts + 1);
            }

            // Payments succeed with 50% probability, or on the 3rd and greater tries
            if (r.nextDouble() > 0.5 || previousAttempts >= 2) {
                synchronized (this) {
                    numberOfTimesPaid.put(jr.workerID, numberOfTimesPaid.getOrDefault(jr.workerID, 0) + 1);
                    amountPaid.put(jr.workerID, amountPaid.getOrDefault(jr.workerID, 0.0) + amount);
                }

                return true;
            }
            // Otherwise payments fail
            else {
                return false;
            }
        });
        new Thread(paymentsThread).start();

        // Launch worker threads

        MockWorker[] workers = new MockWorker[numWorkers];
        Thread[] threads = new Thread[numWorkers];
        for (int i = 0; i < workers.length; i++) {
            workers[i] = new MockWorker();
            threads[i] = new Thread(workers[i]);
            threads[i].start();
        }

        for (int i = 0; i < workers.length; i++) {
            threads[i].join();
        }

        // Give payments a chance to catch up

        System.err.println("Giving payments a chance to catch up...");
        Thread.sleep(3500);
        paymentsThread.stop();

        // Check our results

        System.err.println("Checking results...");

        for (MockWorker worker : workers) {
            if (worker.numAssignments == 0) {
                assertTrue(!numberOfTimesPaid.containsKey(worker.workerID));
                assertTrue(!amountPaid.containsKey(worker.workerID));
            }
            else {
                assertEquals(worker.numAssignments, (int)numberOfTimesPaid.get(worker.workerID));
                double bonusOwed = PaymentsThread.perUnitBonus * worker.numTasks;
                assertEquals(bonusOwed, amountPaid.get(worker.workerID), 1.0e-9);
            }
        }

        System.err.println("Passed!");

        hss.stop();
        // Give the server a chance to die
        Thread.sleep(500);
    }

    private static class MockWorker implements Runnable {
        Random r = new Random();
        String workerID;

        long startedWork = -1;
        long endedWork = -1;
        int numTasks = 0;
        int numAssignments = 0;

        public MockWorker() {
            workerID = ""+r.nextInt();
        }

        // This takes a maximum of 10 seconds, as written

        @Override
        public void run() {
            numAssignments = r.nextInt(4);

            String assignmentID;

            for (int a = 0; a < numAssignments; a++) {
                assignmentID = ""+r.nextInt();

                try {
                    Thread.sleep(r.nextInt(500));
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                HistoricalDatabase.startedWork(workerID, assignmentID);
                startedWork = System.currentTimeMillis();

                int assignmentNumTasks = r.nextInt(10);
                numTasks += assignmentNumTasks;
                for (int i = 0; i < assignmentNumTasks; i++) {
                    try {
                        Thread.sleep(r.nextInt(100));
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                    HistoricalDatabase.didTask(workerID);
                }

                try {
                    Thread.sleep(r.nextInt(100));
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                HistoricalDatabase.endedWork(workerID);
                endedWork = System.currentTimeMillis();
            }
        }
    }
}