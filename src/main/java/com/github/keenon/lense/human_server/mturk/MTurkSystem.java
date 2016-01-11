package com.github.keenon.lense.human_server.mturk;

import com.amazonaws.mturk.addon.HITQuestion;
import com.amazonaws.mturk.requester.HIT;
import com.amazonaws.mturk.service.axis.RequesterService;
import com.amazonaws.mturk.util.PropertiesClientConfig;
import com.github.keenon.lense.human_server.HumanSourceServer;
import com.github.keenon.lense.human_server.HumanWorker;
import com.github.keenon.lense.human_server.payments.PaymentsThread;
import com.github.keenon.lense.human_server.server.JettyServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Created by keenon on 10/14/15.
 *
 * This creates a whole integrated system for recruiting, allocating, querying, and paying MTurk workers.
 */
public class MTurkSystem {
    HumanSourceServer humanSourceServer;
    JettyServer jettyServer;
    PaymentsThread paymentsThread;
    MTurkServer mTurkServer;

    RequesterService mturkService;

    /**
     * Instantiating an MTurkSystem should be enough to handle your human worker issues. It handles the rest, as in
     * booting up the jetty server, the API server, and a payments thread to go through and make payments to workers.
     */
    public MTurkSystem() {
        humanSourceServer = new HumanSourceServer();
        new Thread(humanSourceServer).start();

        jettyServer = new JettyServer();
        new Thread(jettyServer).start();

        paymentsThread = new PaymentsThread(((jobRecord, amountOwed) -> payWorker(jobRecord.workerID, jobRecord.assignmentID, amountOwed)));
        new Thread(paymentsThread).start();

        try {
            mturkService = new RequesterService(new PropertiesClientConfig("/home/keenon/.aws/mturk.properties"));
        }
        catch (Exception e) {

        }

        mTurkServer = new MTurkServer(this::hireWorkers, this::numWorkersPaidFor);
        new Thread(mTurkServer).start();
    }

    /**
     * This creates a standalone MTurkSystem in its own process. Network clients can connect to this from other
     * processes, which will multiplex out the shared resource of our single TLS domain :)
     */
    public static void main(String[] args) throws IOException, InterruptedException {
        MTurkSystem system = new MTurkSystem();

        System.out.println();
        System.out.println("**********************");
        System.out.println("**********************");
        System.out.println("* TURK SERVER SYSTEM *");
        System.out.println("**********************");
        System.out.println("**********************");
        System.out.println();
        System.out.println("Type \"help\" to see a list of commands");
        System.out.println();

        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        while (true) {
            System.out.print("> ");
            String read = br.readLine().trim();
            System.out.println("Read \""+read+"\"");

            if (read.equals("exit") || read.equals("quit")) {
                system.stop();
                Thread.sleep(500);
                System.exit(0);
            }
            else if (read.equals("turk count")) {
                System.out.println("Currently active workers: "+ system.humanSourceServer.humans.size());
                System.out.println("Hiring posts outstanding: TODO");
            }
            else if (read.equals("send everyone home")) {
                System.out.println("Making all user browsers submit early.");
                System.out.println("Please wait for the output from the payment system to quiet down before shutting down the server, otherwise you may fail to pay people.");
                for (HumanWorker human : system.humanSourceServer.humans) {
                    human.sayGoodbye();
                }
            }
            else if (read.startsWith("hire")) {
                String[] parts = read.split(" ");
                if (parts.length != 2) {
                    System.out.println("Improper use of \"hire\" command, needs to be followed by a single integer, as in \"hire 2\"");
                }
                else {
                    try {
                        int numToHire = Integer.parseInt(parts[1]);
                        system.hireWorkers(numToHire);
                        System.out.println("Successfully posted a HIT for "+numToHire+" workers!");
                    }
                    catch (NumberFormatException e) {
                        System.out.println("Improper use of \"hire\" command, needs to be followed by a single integer, as in \"hire 2\"");
                    }
                }
            }
            else if (read.equals("help")) {
                System.out.println("Supported commands:");
                System.out.println("\texit - quits the program");
                System.out.println("\tquit - quits the program");
                System.out.println("\tturk count - returns the current number of workers in the system, and number of unanswered HITs still out there");
                System.out.println("\tsend everyone home - terminates all worker employment immediately");
                System.out.println("\thire N - where N is some integer, as in \"hire 3\", which hires 3 people");
            }
            else {
                System.out.println("Command unrecognized. Type \"help\" for a list of commands");
            }
        }
    }


    /**
     * This will kill the MTurk API, as well as the socket server
     */
    public void stop() {
        humanSourceServer.stop();
        paymentsThread.stop();
        mTurkServer.stop();
    }

    /**
     * This makes a post to hire workers
     */
    public String hireWorkers(int numWorkersToHire) {
        try {
            String title = "30 minutes of real-time decisions, BONUS at ~$12 / hr if busy";
            String description = "Receive $3 for just sitting here for 30 mins, and an extra $0.01 for every 5 labels.";
            String question = new HITQuestion("lense/src/resources/external.question").getQuestion();
            double reward = 3.00;
            HIT hit = mturkService.createHIT(title, description, reward, question, numWorkersToHire);

            String url = mturkService.getWebsiteURL()+"/mturk/preview?groupId="+hit.getHITTypeId();
            System.out.println("Created HIT: " + hit.getHITId());
            System.out.println("You can see it here: "+url);
            return url;
        } catch (Exception e) {
            e.printStackTrace();
            return "error";
        }
    }

    /**
     * This wraps the call that attempts to pay a worker. It is blocking, but that doesn't matter, since the
     * PaymentsThread launches a thread for each payment anyways.
     *
     * @param workerID the MTurk ID of the worker
     * @param assignmentID the MTurk ID of the assignment
     * @param amountOwed the amount owed to the worker, beyond the basic amount of the task
     */
    public boolean payWorker(String workerID, String assignmentID, double amountOwed) {
        // This happens on non-MTurk visits to the page
        if (workerID == null || assignmentID == null) {
            return true;
        }

        try {
            mturkService.approveAssignment(assignmentID, "Automated assignment approval");
            System.out.println("Successfully approved HIT for "+workerID+":"+assignmentID+", who has an outstanding bonus (not yet sent) of $"+amountOwed);
        }
        catch (Exception e) {
            // Potentially already approved the assignment. Don't panic yet.
            e.printStackTrace();
        }

        if (amountOwed == 0.0) return true;

        try {
            mturkService.grantBonus(workerID, amountOwed, assignmentID, "Automated bonus payment of $"+amountOwed);
            System.out.println("Successfully approved bonus for "+workerID+":"+assignmentID+" of $"+amountOwed);
            // If we successfully grant the bonus, then we're good to go, and everything is grand, so return a
            // confirmation
            return true;
        }
        catch (Exception e) {
            System.out.println("Failed to approve bonus for " + workerID + ":" + assignmentID + " of $" + amountOwed);
            e.printStackTrace();
            // If anything went wrong, we're not done here yet, so put this back on the queue to get payed later.
            return false;
        }
    }

    /**
     * Finds the combined sum of the workers currently active and the workers we've put up requests for on MTurk.
     *
     * @return the number of workers
     */
    public int numWorkersPaidFor() {
        // TODO
        return 0;
    }
}
