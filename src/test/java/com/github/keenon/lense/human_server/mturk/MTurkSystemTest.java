package com.github.keenon.lense.human_server.mturk;

import com.github.keenon.lense.human_server.HumanSourceClient;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Created by keenon on 10/14/15.
 *
 * This is very similar to the Jetty Server test. We basically just boot up a system, and then ask the tester to go
 * open up a browser and check that everything appears in order.
 */
public class MTurkSystemTest {
    public static void main(String[] args) throws Exception {
        System.err.println("PLEASE, double check that you are pointed at the sandbox address in your MTurk config (mine is at /users/keenon/.aws/mturk.properties)");
        System.err.println("Press enter if you're sure, otherwise kill the test");

        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        br.readLine();

        MTurkSystem mTurkSystem = new MTurkSystem();

        MTurkClient mTurkClient = new MTurkClient("localhost", 2110);
        String url = mTurkClient.hireWorkersBlocking(1);
        System.err.println("Made remote MTurk posting: "+url);

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