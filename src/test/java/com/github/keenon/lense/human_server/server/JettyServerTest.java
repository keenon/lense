package com.github.keenon.lense.human_server.server;

import com.github.keenon.lense.human_server.HumanSourceClient;
import com.github.keenon.lense.human_server.HumanSourceServer;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.junit.Test;

/**
 * Created by keenon on 10/13/15.
 *
 * This is a silly test that just launches the actual server and creates a couple of jobs and queries on the server for
 * a person or set of people to do from their browsers.
 */
public class JettyServerTest {
    int queriesAnswered = 0;

    // @Test
    public void GUITest() throws Exception {
        new Thread(new HumanSourceServer()).start();
        new Thread(new JettyServer()).start();

        // Let the HumanSourceServer boot up
        Thread.sleep(200);

        HumanSourceClient client = new HumanSourceClient("localhost", 2109);

        JSONObject jobDescription = new JSONObject();
        jobDescription.put("type", "sequence");
        JSONArray sequence = new JSONArray();
        for (String tok : "the quick brown fox jumped over the lazy dog".split(" ")) {
            sequence.add(tok);
        }
        jobDescription.put("sequence", sequence);

        Object queryBarrier = new Object();
        int[] queriesAnswered = new int[1];

        HumanSourceClient.JobHandle handle = client.createJob(jobDescription.toJSONString(), 0, () -> System.err.println("Job accepted!"), () -> {
            System.err.println("Job abandoned!");
            queriesAnswered[0] = sequence.size();
            synchronized (queryBarrier) {
                queryBarrier.notifyAll();
            }
        });

        int[] queryResponses = new int[sequence.size()];

        for (int i = 0; i < sequence.size(); i++) {
            JSONObject queryDescription = new JSONObject();
            queryDescription.put("html", "please label token "+i);
            JSONArray choices = new JSONArray();
            for (String tok : "ORG LOC PER NONE".split(" ")) {
                choices.add(tok);
            }
            queryDescription.put("choices", choices);

            final int index = i;

            handle.launchQuery(queryDescription.toJSONString(), (answer) -> {
                queryResponses[index] = answer;
                queriesAnswered[0]++;
                synchronized (queryBarrier) {
                    queryBarrier.notifyAll();
                }
            }, () -> {
                queryResponses[index] = -1;
                queriesAnswered[0]++;
                synchronized (queryBarrier) {
                    queryBarrier.notifyAll();
                }
            });
        }

        while (queriesAnswered[0] < sequence.size()) {
            synchronized (queryBarrier) {
                queryBarrier.wait();
            }
        }

        System.err.println("Finished.");
    }
}