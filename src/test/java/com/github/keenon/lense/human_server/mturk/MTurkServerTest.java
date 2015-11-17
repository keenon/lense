package com.github.keenon.lense.human_server.mturk;

import com.pholser.junit.quickcheck.ForAll;
import com.pholser.junit.quickcheck.generator.InRange;
import org.junit.contrib.theories.Theories;
import org.junit.contrib.theories.Theory;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.Random;

import static org.junit.Assert.assertEquals;

/**
 * Created by keenon on 10/15/15.
 *
 * The point here is to randomly hammer the MTurkServer with requests, to make sure that everything is working exactly
 * as we had hoped it would.
 */
@RunWith(Theories.class)
public class MTurkServerTest {
    @Theory
    public void testQueries(@ForAll(sampleSize = 2) @InRange(maxInt = 50, minInt = 5) int numWorkers) throws Exception {
        Random r = new Random(42);

        String urlToReturn = ""+r.nextInt();
        int currentWorkers = r.nextInt(20);

        MTurkServer server = new MTurkServer((numToHire) -> urlToReturn, () -> currentWorkers);

        new Thread(server).start();

        // Wait for boot up
        Thread.sleep(200);

        MTurkClientTest[] testThreads = new MTurkClientTest[numWorkers];
        Thread[] threads = new Thread[numWorkers];
        for (int i = 0; i < numWorkers; i++) {
            testThreads[i] = new MTurkClientTest(r);
            threads[i] = new Thread(testThreads[i]);
            threads[i].start();
        }
        for (int i = 0; i < numWorkers; i++) {
            threads[i].join();
        }

        for (MTurkClientTest test : testThreads) {
            if (test.numWorkerQueries > 0) {
                assertEquals(currentWorkers, test.numReceieved);
            }
            if (test.numURLQueries > 0) {
                assertEquals(urlToReturn, test.urlReceived);
            }
        }

        // Shut down the API server
        server.stop();
    }

    private static class MTurkClientTest implements Runnable {
        public String urlReceived;
        public int numReceieved;

        public int numURLQueries = 0;
        public int numWorkerQueries = 0;

        MTurkClient client;

        Random r;
        public MTurkClientTest(Random r) throws IOException {
            this.r = r;
            client = new MTurkClient("localhost", 2110);
        }

        @Override
        public void run() {
            int numQueries = r.nextInt(50) + 1;

            for (int i = 0; i < numQueries; i++) {
                if (r.nextBoolean()) {
                    numReceieved = client.getNumberOfWorkersBlocking();
                    numWorkerQueries++;
                }
                else {
                    urlReceived = client.hireWorkersBlocking(r.nextInt(5));
                    numURLQueries++;
                }
            }

            client.close();
        }
    }
}