package com.github.keenon.lense.human_source;

import com.github.keenon.lense.human_server.HumanSourceServer;
import com.github.keenon.lense.human_server.HumanWorker;
import com.github.keenon.lense.human_source.HumanSource;
import com.github.keenon.lense.human_source.MTurkHumanSource;
import com.github.keenon.loglinear.model.ConcatVectorNamespace;
import com.github.keenon.loglinear.model.GraphicalModel;
import com.pholser.junit.quickcheck.ForAll;
import com.pholser.junit.quickcheck.From;
import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.generator.InRange;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import com.github.keenon.lense.human_server.mturk.MTurkServer;
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
 * Created by keenon on 10/16/15.
 *
 * This makes an effort to test a couple of annoying aspects of doing this over the network, most notably the fact that
 * we need resources to be freed up when we run multiple jobs in waves and then close the job while work is in progress.
 */
@RunWith(Theories.class)
public class MTurkHumanSourceTest {
    /**
     * The point here is that we care about subsequent connections not having to restart the main server in order to get
     * access to resources. We also don't want outstanding job requests for dead sockets effecting things.
     */
    @Theory
    public void releaseResources(@ForAll(sampleSize = 4) @InRange(maxInt = 50, minInt = 5) int numThreads,
                                 @ForAll(sampleSize = 1) @From(GraphicalModelGenerator.class) GraphicalModel model) throws Exception {
        ConcatVectorNamespace namespace = new ConcatVectorNamespace();

        HumanSourceServer server = new HumanSourceServer();
        new Thread(server).start();

        MTurkServer turkServer = new MTurkServer((numToHire) -> {
            return "url";
        }, () -> {
            return 0;
        });
        new Thread(turkServer).start();

        Random r = new Random();

        // Wait for the server to start up
        Thread.sleep(200);

        Thread[] threads = new Thread[numThreads];
        for (int i = 0; i < numThreads; i++) {
            threads[i] = new Thread(() -> {
                try {
                    MTurkHumanSource source = new MTurkHumanSource("localhost", namespace, null);
                    source.makeJobPosting(model, (humanHandle) -> {
                        // do nothing
                    });
                    Thread.sleep(r.nextInt(1000));
                    source.close();

                    // Wait a little while for the closing to propagate down the socket
                    Thread.sleep(100);
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (InterruptedException e) {
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
                blockingWorkers[iFinal] = new BlockingWorker();
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
            assertFalse(blockingWorkers[i].inJob);
        }

        // now comes the second test: can we get a person to show up at a new thread?

        boolean[] gotResponse = new boolean[1];
        MTurkHumanSource source = new MTurkHumanSource("localhost", namespace, null);
        source.makeJobPosting(model, (humanHandle) -> {
            gotResponse[0] = true;
        });

        Thread.sleep(500);

        assertTrue(gotResponse[0]);

        source.close();

        // Wait for the server to stop
        server.stop();
        turkServer.stop();
        Thread.sleep(200);

        for (int i = 0; i < 12; i++) {
            System.err.println("**********************************");
        }
    }

    private static class BlockingWorker extends HumanWorker {
        public boolean inJob = false;

        @Override
        public void startNewJob(String JSON) {
            inJob = true;
        }

        @Override
        public void launchQuery(String JSON, Consumer<Integer> callback) {
            // never call the callback, on purpose
        }

        @Override
        public void endCurrentJob() {
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
        ConcatVectorNamespace namespace = new ConcatVectorNamespace();

        HumanSourceServer server = new HumanSourceServer();
        new Thread(server).start();

        MTurkServer turkServer = new MTurkServer((numToHire) -> {
            return "url";
        }, () -> {
            return 0;
        });
        new Thread(turkServer).start();

        // Wait for the server to start up
        Thread.sleep(200);

        HumanSource[] clients = new MTurkHumanSource[8];
        for (int i = 0; i < clients.length; i++) {
            clients[i] = new MTurkHumanSource("localhost", namespace, null);
        }

        Random r = new Random();

        GraphicalModelGenerator modelGenerator = new GraphicalModelGenerator(GraphicalModel.class);
        SourceOfRandomness sourceOfRandomness = new SourceOfRandomness(r);

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

                jobRecords[iFinal] = new JobRecord(modelGenerator.generate(sourceOfRandomness, null), clients[r.nextInt(clients.length)], r.nextInt(10), globalQueryIndex, onlyOnceID);
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
        turkServer.stop();

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
            Map<HumanSource, Set<Integer>> seenOnlyOnceIDsPerClient = new HashMap<>();

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
        GraphicalModel model;
        HumanSource client;
        int numQueries;
        int onlyOnceID;

        int[] queries;
        int[] queryIDs;
        int queriesReturned = 0;
        Object queryReturnBarrier = new Object();
        Random r = new Random();

        AtomicInteger globalQueryCounter;

        public JobRecord(GraphicalModel model, HumanSource client, int numQueries, AtomicInteger globalQueryCounter, int onlyOnceID) {
            this.model = model;
            this.client = client;
            this.numQueries = Math.min(model.getVariableSizes().length, numQueries);
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
            client.makeJobPosting(model, (humanHandle) -> {
                // Job accepted
                System.err.println("Job accepted");
                // Someone responded to our job posting
                humanHandle.setDisconnectedCallback(() -> {
                    // Job failed
                    System.err.println("Job failed");
                    queriesReturned = numQueries;
                    synchronized (queryReturnBarrier) {
                        queryReturnBarrier.notifyAll();
                    }
                });
                // Launch all our queries
                for (int i = 0; i < numQueries; i++) {
                    final int iFinal = i;
                    System.err.println("Launching "+iFinal);

                    queryIDs[i] = globalQueryCounter.getAndIncrement();

                    model.getVariableMetaDataByReference(i).put(MTurkHumanSource.QUERY_JSON, "{\"id\":"+queryIDs[i]+"}");

                    humanHandle.makeQuery(i, (returnValue) -> {
                        // Query succeeded
                        queries[iFinal] = returnValue;
                        queriesReturned++;
                        System.err.println("Received " + iFinal + "=" + returnValue);
                        synchronized (queryReturnBarrier) {
                            queryReturnBarrier.notifyAll();
                        }
                    }, () -> {
                        // Query failed
                        queries[iFinal] = -1;
                        queriesReturned++;
                        System.err.println("Failed " + iFinal);
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

                humanHandle.release();
            });
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

    public static class GraphicalModelGenerator extends Generator<GraphicalModel> {
        public GraphicalModelGenerator(Class<GraphicalModel> type) {
            super(type);
        }

        private Map<String, String> generateMetaData(SourceOfRandomness sourceOfRandomness, Map<String, String> metaData) {
            int numPairs = sourceOfRandomness.nextInt(9);
            for (int i = 0; i < numPairs; i++) {
                int key = sourceOfRandomness.nextInt();
                int value = sourceOfRandomness.nextInt();
                metaData.put("key:" + key, "value:" + value);
            }
            return metaData;
        }

        @Override
        public GraphicalModel generate(SourceOfRandomness sourceOfRandomness, GenerationStatus generationStatus) {
            GraphicalModel model = new GraphicalModel();

            // Create the variables and factors. These are deliberately tiny so that the brute force approach is tractable

            int[] variableSizes = new int[sourceOfRandomness.nextInt(10, 50)];
            for (int i = 0; i < variableSizes.length; i++) {
                variableSizes[i] = sourceOfRandomness.nextInt(1, 8);
            }

            model.getModelMetaDataByReference().put(MTurkHumanSource.QUERY_JSON, "{}");

            for (int i = 0; i < variableSizes.length; i++) {
                model.getVariableMetaDataByReference(i).put(MTurkHumanSource.QUERY_JSON, "{}");
            }

            return model;
        }
    }
}