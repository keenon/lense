package com.github.keenon.lense.human_server.server;

import com.github.keenon.lense.human_server.HumanSourceServerTest;
import com.github.keenon.lense.human_server.payments.HistoricalDatabase;
import com.pholser.junit.quickcheck.ForAll;
import com.pholser.junit.quickcheck.generator.InRange;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.junit.Assert;
import org.junit.contrib.theories.Theories;
import org.junit.contrib.theories.Theory;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

/**
 * Created by keenon on 10/10/15.
 *
 * This is to attempt to test lifecycle events on the server side. We want to check that everything plays nice with the
 * HumanSourceServer.
 */
@RunWith(Theories.class)
public class HumanWorkerWebSocketTest {
    /**
     * This is basically just to check that the JSON serialization works properly, and everything is indeed hooked up to
     * the HumanSourceServer. Workers accept jobs, then do them.
     *
     * @param numThreads
     * @param numJobs
     * @throws Exception
     */
    @Theory
    public void testQueries(@ForAll(sampleSize = 2) @InRange(maxInt = 10, minInt = 2) int numThreads,
                            @ForAll(sampleSize = 2) @InRange(maxInt = 100, minInt = 20) int numJobs) throws Exception {
        List<WebSocketMockBrowser> mockBrowsers = new ArrayList<>();

        HumanSourceServerTest.busyTest(() -> {
            WebSocketMockBrowser mockWorkerWithBrowser = new WebSocketMockBrowser();
            mockBrowsers.add(mockWorkerWithBrowser);
            new Thread(mockWorkerWithBrowser).start();
            return mockWorkerWithBrowser;
        }, numThreads, numJobs);

        // Test that the payment system is working

        for (WebSocketMockBrowser mockBrowser : mockBrowsers) {
            Assert.assertEquals(HistoricalDatabase.numberOfTasksThisWorkUnit("" + mockBrowser.workerID), mockBrowser.totalQueriesReceived);
        }
    }

    public static class WebSocketMockBrowser extends HumanSourceServerTest.TestWorker implements Runnable {
        public HumanWorkerWebSocket humanWorkerWebSocket;
        public final Queue<String> messages = new ArrayDeque<>();
        Random r = new Random();

        int assignmentID;
        int hitID;
        int workerID;

        boolean running = true;

        int totalQueriesReceived = 0;

        public WebSocketMockBrowser() {
            humanWorkerWebSocket = new HumanWorkerWebSocket();

            assignmentID = r.nextInt();
            hitID = r.nextInt();
            workerID = r.nextInt();

            // Initialize the socket

            humanWorkerWebSocket.initialize((msg) -> {
                System.err.println("Sending message: "+msg);
                synchronized (messages) {
                    messages.add(msg);
                    messages.notifyAll();
                }
            });
        }

        public void stop() {
            running = false;
            synchronized (messages) {
                messages.notifyAll();
            }
        }

        /**
         * In this thread loop, we simulate the JSON responses of a browser to the calls to thu HumanWorkerWebSocket
         */
        @Override
        public void run() {

            // Wait to simulate browser bootup

            try {
                Thread.sleep(r.nextInt(200));
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            // Send the initialization message

            JSONObject initialization = new JSONObject();
            initialization.put("type", "ready-message");
            initialization.put("assignment-id", ""+assignmentID);
            initialization.put("hit-id", ""+hitID);
            initialization.put("worker-id", ""+workerID);

            try {
                humanWorkerWebSocket.onMessage(initialization.toJSONString());
            } catch (IOException e) {
                e.printStackTrace();
            }

            // Spin on accepting work socket requests and replying with content

            while (running) {
                String message;
                synchronized (messages) {
                    while (messages.isEmpty()) {
                        try {
                            messages.wait();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    message = messages.poll();
                }
                System.err.println("Received message "+message);
                JSONObject msg = (JSONObject) JSONValue.parse(message);
                if (msg.containsKey("total-queries-answered")) {
                    totalQueriesReceived = ((Long)msg.get("total-queries-answered")).intValue();
                }
                if (msg.containsKey("type")) {
                    if (msg.get("type").equals("query")) {
                        new Thread(() -> {
                            try {
                                Thread.sleep(r.nextInt(100));
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }

                            int responseValue = r.nextInt(10);

                            int id = ((Long)((JSONObject) msg.get("payload")).get("id")).intValue();
                            queryResponses.put(id, responseValue);

                            JSONObject queryResponse = new JSONObject();
                            queryResponse.put("type", "query-response");
                            queryResponse.put("query-response", responseValue);

                            try {
                                humanWorkerWebSocket.onMessage(queryResponse.toJSONString());
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }).start();
                    }
                }
            }
        }

        @Override
        public void startNewJob(String JSON) {
            humanWorkerWebSocket.startNewJob(JSON);
        }

        @Override
        public void launchQuery(String JSON, Consumer<Integer> callback) {
            humanWorkerWebSocket.launchQuery(JSON, callback);
        }

        @Override
        public void endCurrentJob() {
            humanWorkerWebSocket.endCurrentJob();
        }

        @Override
        public void sayGoodbye() {
            humanWorkerWebSocket.sayGoodbye();
        }
    }
}