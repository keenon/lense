package com.github.keenon.lense.human_server;

import com.pholser.junit.quickcheck.ForAll;
import com.pholser.junit.quickcheck.generator.InRange;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.junit.contrib.theories.Theories;
import org.junit.contrib.theories.Theory;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.Assert.*;

/**
 * Created by keenon on 10/8/15.
 *
 * This is a simple test to get the HumanSourceServer to accept some API calls, and check that it's handling things
 * the way we want it to.
 */
@RunWith(Theories.class)
public class HumanSourceServerTest {
    /**
     * The point here is that we care about subsequent connections not having to restart the main server in order to get
     * access to resources. We also don't want outstanding job requests for dead sockets effecting things.
     */
    @Theory
    public void releaseResources(@ForAll(sampleSize = 5) @InRange(maxInt = 50, minInt = 5) int numThreads) throws Exception {
        HumanSourceServer server = new HumanSourceServer();
        new Thread(server).start();

        Random r = new Random();

        // Wait for the server to start up
        Thread.sleep(200);

        Thread[] threads = new Thread[numThreads];
        for (int i = 0; i < numThreads; i++) {
            int iFinal = i;
            threads[i] = new Thread(() -> {
                try {
                    HumanSourceClient client = new HumanSourceClient("localhost", 2109);
                    HumanSourceClient.JobHandle handle = client.createJob("{}", iFinal, () -> {
                        // job accepted
                    }, () -> {
                        // job abandoned
                    });
                    if (r.nextBoolean()) {
                        handle.launchQuery("{}", (answer) -> {}, () -> {});
                    }
                    Thread.sleep(r.nextInt(1000));

                    client.close();

                    // Wait a little while for the closing to propagate down the socket
                    Thread.sleep(50);
                } catch (IOException | InterruptedException e) {
                    e.printStackTrace();
                }
            });
            threads[i].start();
        }

        int numJobs = Math.max(1,r.nextInt(numThreads)); // up to but not more than the number of threads

        Thread[] workerThreads = new Thread[numJobs];
        BlockingWorker[] blockingWorkers = new BlockingWorker[numJobs];
        for (int i = 0; i < numJobs; i++) {
            final int iFinal = i;

            workerThreads[i] = new Thread(() -> {
                try {
                    Thread.sleep(r.nextInt(500));
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                blockingWorkers[iFinal] = new BlockingWorker(iFinal);
                server.registerHuman(blockingWorkers[iFinal]);
            });
            workerThreads[i].start();
        }

        // wait for workers to get created

        for (int i = 0; i < numJobs; i++) {
            workerThreads[i].join();
        }

        // wait for job handles to supposedly close

        for (int i = 0; i < numThreads; i++) {
            threads[i].join();
        }

        // now comes the first test. Did we free up all the human resources?

        for (int i = 0; i < numJobs; i++) {
            if (blockingWorkers[i].inJob) {
                System.err.println("Worker "+i+" still in job!");
            }
            assertFalse(blockingWorkers[i].inJob);
        }

        // now comes the second test: can we get a person to show up at a new thread?

        boolean[] jobAccepted = new boolean[1];
        HumanSourceClient client = new HumanSourceClient("localhost", 2109);
        HumanSourceClient.JobHandle handle = client.createJob("{}", 1000, () -> {
            // job accepted
            jobAccepted[0] = true;
        }, () -> {
            // job abandoned
        });
        Thread.sleep(r.nextInt(1000));

        assertTrue(jobAccepted[0]);
        client.close();

        // Wait for the server to stop
        server.stop();
        Thread.sleep(200);

        for (int i = 0; i < 12; i++) {
            System.err.println("**********************************");
        }
    }

    private static class BlockingWorker extends HumanWorker {
        int i;
        public boolean inJob = false;

        public BlockingWorker(int i) {
            this.i = i;
        }

        @Override
        public void startNewJob(String JSON) {
            System.err.println("Worker "+i+" starting job");
            inJob = true;
        }

        @Override
        public void launchQuery(String JSON, Consumer<Integer> callback) {
            // never call the callback, on purpose
        }

        @Override
        public void endCurrentJob() {
            System.err.println("Worker "+i+" leaving job");
            inJob = false;
        }

        @Override
        public void sayGoodbye() {

        }
    }

    @Theory
    public void testQueries(@ForAll(sampleSize = 2) @InRange(maxInt = 50, minInt = 5) int numThreads,
                            @ForAll(sampleSize = 2) @InRange(maxInt = 100, minInt = 20) int numJobs) throws Exception {
        busyTest(BabyConsumer::new, numThreads, numJobs);
    }

    public static void busyTest(Supplier<TestWorker> getWorker, int numThreads, int numJobs) throws IOException, InterruptedException {
        HumanSourceServer server = new HumanSourceServer();
        new Thread(server).start();

        // Wait for the server to start up
        Thread.sleep(200);

        HumanSourceClient[] clients = new HumanSourceClient[8];
        for (int i = 0; i < clients.length; i++) {
            clients[i] = new HumanSourceClient("localhost", 2109);
        }

        Random r = new Random();

        AtomicInteger globalQueryIndex = new AtomicInteger();

        JobRecord[] jobRecords = new JobRecord[numJobs];
        Thread[] threads = new Thread[numJobs];
        for (int i = 0; i < numJobs; i++) {
            final int iFinal = i;

            // This is insufficiently random for my taste, but it's very representative of how onlyOnceIDs will be used
            // in practice.
            int onlyOnceID = (int)Math.floor(((double)i) / numThreads);

            new Thread(() -> {
                try {
                    Thread.sleep(r.nextInt(500));
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                jobRecords[iFinal] = new JobRecord(clients[r.nextInt(clients.length)], r.nextInt(10), globalQueryIndex, onlyOnceID);
                threads[iFinal] = new Thread(jobRecords[iFinal]);
                threads[iFinal].start();
            }).start();
        }

        AtomicInteger numCrashed = new AtomicInteger();

        TestWorker[] testWorkers = new TestWorker[numThreads];
        for (int i = 0; i < testWorkers.length; i++) {
            final int iFinal = i;
            new Thread(() -> {
                try {
                    Thread.sleep(r.nextInt(500));
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                testWorkers[iFinal] = getWorker.get();
                testWorkers[iFinal].crashCallback = () -> {
                    // This is the simulated crash callback on our fake worker
                    if (numCrashed.incrementAndGet() >= testWorkers.length) {
                        System.err.println("Every worker thread simulated crashing, terminating the test");
                        // Wake up all the remaining jobs, since they all failed, so that our system can terminate
                        for (JobRecord jr : jobRecords) {
                            jr.queriesReturned = jr.numQueries;
                            synchronized (jr.queryReturnBarrier) {
                                jr.queryReturnBarrier.notifyAll();
                            }
                        }
                    }
                };
                server.registerHuman(testWorkers[iFinal]);
            }).start();
        }

        Thread.sleep(600);

        for (int i = 0; i < numJobs; i++) {
            threads[i].join();
        }

        System.err.println("Closing handles");
        for (int i = 0; i < clients.length; i++) {
            clients[i].close();
        }

        System.err.println("Killing server");
        server.stop();

        // Wait for the server to stop
        Thread.sleep(200);

        Map<Integer,Integer> jobQueryResults = new HashMap<>();
        for (JobRecord job : jobRecords) {
            for (int i = 0; i < job.queries.length; i++) {
                if (job.queries[i] > -2) {
                    jobQueryResults.put(job.queryIDs[i], job.queries[i]);
                }
            }
        }

        Map<Integer,Integer> workerQueryValues = new HashMap<>();
        for (TestWorker worker : testWorkers) {
            workerQueryValues.putAll(worker.queryResponses);
        }

        Set<Integer> intersectKeys = new HashSet<>();
        intersectKeys.addAll(jobQueryResults.keySet());
        intersectKeys.retainAll(workerQueryValues.keySet());

        for (int i : intersectKeys) {
            assertEquals(jobQueryResults.get(i), workerQueryValues.get(i));
        }

        Set<Integer> jobQueryOnlyKeys = new HashSet<>();
        jobQueryOnlyKeys.addAll(jobQueryResults.keySet());
        jobQueryOnlyKeys.removeAll(workerQueryValues.keySet());

        assertTrue(workerQueryValues.size() >= jobQueryResults.size());

        // Make sure that a given test worker only touches the same onlyOnceID once per job,
        // but leave allowance for the fact that each API handle gets its own namespace

        for (TestWorker worker : testWorkers) {
            Set<JobRecord> seenJobs = new HashSet<>();
            Map<HumanSourceClient, Set<Integer>> seenOnlyOnceIDsPerClient = new HashMap<>();

            for (int id : worker.queryResponses.keySet()) {
                JobRecord selectedJR = null;
                outer: for (JobRecord jr : jobRecords) {
                    for (int qid : jr.queryIDs) {
                        if (id == qid) {
                            selectedJR = jr;
                            break outer;
                        }
                    }
                }
                assert(selectedJR != null);

                if (seenJobs.contains(selectedJR)) continue;
                seenJobs.add(selectedJR);

                if (!seenOnlyOnceIDsPerClient.containsKey(selectedJR.client)) {
                    seenOnlyOnceIDsPerClient.put(selectedJR.client, new HashSet<>());
                }
                Set<Integer> seenOnlyOnceIDs = seenOnlyOnceIDsPerClient.get(selectedJR.client);

                assertTrue(!seenOnlyOnceIDs.contains(selectedJR.onlyOnceID));
                seenOnlyOnceIDs.add(selectedJR.onlyOnceID);
            }
        }

        for (int i = 0; i < 12; i++) {
            System.err.println("**********************************");
        }
    }

    public abstract static class TestWorker extends HumanWorker {
        public Map<Integer, Integer> queryResponses = new HashMap<>();
        public Runnable crashCallback;
    }

    public static class JobRecord implements Runnable {
        HumanSourceClient client;
        int numQueries;
        int onlyOnceID;

        int[] queries;
        int[] queryIDs;
        int queriesReturned = 0;
        Object queryReturnBarrier = new Object();
        Random r = new Random();

        AtomicInteger globalQueryCounter;

        public JobRecord(HumanSourceClient client, int numQueries, AtomicInteger globalQueryCounter, int onlyOnceID) {
            this.client = client;
            this.numQueries = numQueries;
            this.onlyOnceID = onlyOnceID;

            queries = new int[numQueries];
            queryIDs = new int[numQueries];

            for (int i = 0; i < queries.length; i++) {
                queries[i] = -2; // flag to indicate unmarked query
            }

            this.globalQueryCounter = globalQueryCounter;
        }

        @Override
        public void run() {
            HumanSourceClient.JobHandle handle = client.createJob("", onlyOnceID, () -> {
                // Job accepted
                System.err.println("Job accepted");
            }, () -> {
                // Job failed
                System.err.println("Job failed");
                queriesReturned = numQueries;
                synchronized (queryReturnBarrier) {
                    queryReturnBarrier.notifyAll();
                }
            });

            for (int i = 0; i < numQueries; i++) {
                final int iFinal = i;
                System.err.println("Launching "+handle.jobID+":"+iFinal);

                queryIDs[i] = globalQueryCounter.getAndIncrement();

                handle.launchQuery("{\"id\":"+queryIDs[i]+"}", (returnValue) -> {
                    // Query succeeded
                    queries[iFinal] = returnValue;
                    queriesReturned++;
                    System.err.println("Received " + handle.jobID + ":" + iFinal + "=" + returnValue);
                    synchronized (queryReturnBarrier) {
                        queryReturnBarrier.notifyAll();
                    }
                }, () -> {
                    // Query failed
                    queries[iFinal] = -1;
                    queriesReturned++;
                    System.err.println("Failed " + handle.jobID + ":" + iFinal);
                    synchronized (queryReturnBarrier) {
                        queryReturnBarrier.notifyAll();
                    }
                });

                try {
                    Thread.sleep(r.nextInt(500));
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            while (queriesReturned < numQueries) {
                synchronized (queryReturnBarrier) {
                    try {
                        queryReturnBarrier.wait();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }

            // Finish the job, and the thread

            handle.closeJob();
        }
    }

    public static class BabyConsumer extends TestWorker {
        HumanSourceServer server;
        Random r = new Random();
        boolean crashed = false;

        public BabyConsumer() {
            this.server = HumanSourceServer.currentInstance;
        }

        @Override
        public void startNewJob(String JSON) {
        }

        @Override
        public void launchQuery(String JSON, Consumer<Integer> callback) {
            JSONObject obj = (JSONObject) JSONValue.parse(JSON);
            int id = ((Long)obj.get("id")).intValue();

            if (crashed) return;

            // Simulate crashes with 5% probability
            if (r.nextDouble() < 0.05) {
                crashed = true;

                new Thread(() -> {
                    System.err.println("Simulating a crash...");
                    queryResponses.put(id, -1);

                    userQuit.run();
                    crashCallback.run();
                }).start();
                return;
            }

            int returnValue = r.nextInt();
            // Record the responses we make after a random delay
            new Thread(() -> {
                try {
                    Thread.sleep(r.nextInt(200));
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                queryResponses.put(id, returnValue);
                callback.accept(returnValue);
            }).start();
        }

        @Override
        public void endCurrentJob() {
        }

        @Override
        public void sayGoodbye() {
            System.err.println("Saying goodbye");
        }
    }
}