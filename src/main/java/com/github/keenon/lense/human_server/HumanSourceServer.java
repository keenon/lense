package com.github.keenon.lense.human_server;

import com.github.keenon.lense.human_server.server.JettyServer;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by keenon on 10/7/15.
 *
 * This is a class, which boots an actual Jetty instance and then manages marshalling humans and communicating with them
 * across a network call interface, as well as communicating with (potentially many) game-players over a network
 * interface.
 */
public class HumanSourceServer implements Runnable {
    public static HumanSourceServer currentInstance = null;

    public static void main(String[] args) {
        new Thread(new HumanSourceServer()).start();
        new Thread(new JettyServer()).start();
    }

    // A list of humans
    public final Set<HumanWorker> humans = new HashSet<>();
    // A list of all the API sockets, each of which will have its own namespace for IDs of jobs
    public final Map<Integer, APISocket> apiSockets = new HashMap<>();

    private AtomicInteger apiIDCounter = new AtomicInteger();

    /**
     * This create a specific thread to manage this worker, and make sure that the appropriate callbacks on the worker
     * are set at the appropriate moments.
     *
     * @param worker the human worker object to register
     */
    public synchronized void registerHuman(HumanWorker worker) {
        humans.add(worker);
        worker.userQuit = () -> {
            humanCrashed(worker);
        };
        updateHumanJobAssignments();
    }

    /**
     * This cuts the human out of our current lists, sending failure messages as necessary.
     *
     * @param worker the worker to cut out
     */
    public synchronized void humanCrashed(HumanWorker worker) {
        if (worker.currentJobID > -1) {
            assert (worker.currentSocketID > -1);

            APISocket apiSocket = apiSockets.get(worker.currentSocketID);

            if (worker.currentQueryID > -1) {
                apiSocket.queryFailure(worker.currentJobID, worker.currentQueryID);
            }
            apiSocket.jobAbandoned(worker.currentJobID);
        }
        humans.remove(worker);
    }

    /**
     * This wakes up and assigns all available humans to jobs. By doing it in an explicit function, and not just relying
     * on thread ordering, we can have logic for matching humans to jobs.
     *
     * This is synchronized so is safe to call from threads or callbacks
     */
    public synchronized void updateHumanJobAssignments() {

        // If there are jobs, and people to do them, then begin doing those jobs

        // Just match the humans in an ad-hoc order.
        // This is a super stupid algorithm, but ok.
        for (HumanWorker human : humans) {
            // If this person is unemployed, match them
            if (human.working && human.currentJobID == -1) {
                for (int i : apiSockets.keySet()) {
                    APISocket apiSocket = apiSockets.get(i);
                    if (apiSocket.jobPostings.size() > 0) {
                        // Pick a job that's compatible with the current state of onlyOnceIDs
                        JobPosting selectJP = null;
                        for (JobPosting jp : apiSocket.jobPostings) {
                            if (apiSocket.canUseOnlyOnceID(human, jp.onlyOnceID)) {
                                selectJP = jp;
                                break;
                            }
                        }

                        // If we don't find anything, move on
                        if (selectJP == null) continue;

                        apiSocket.jobPostings.remove(selectJP);
                        apiSocket.useOnlyOnceID(human, selectJP.onlyOnceID);

                        // Call out to actually launch the code on the person's browser
                        final JobPosting finalJP = selectJP;
                        new Thread(() -> {
                            human.startNewJob(finalJP.json);
                        }).start();
                        // Make sure we reset the current query ID
                        human.currentJobID = selectJP.jobID;
                        human.currentQueryID = -1;
                        human.currentSocketID = i;
                        // Return the message that we found a worker for this job
                        new Thread(() -> {
                            apiSocket.jobAccepted(finalJP.jobID);
                        }).start();
                        break;
                    }
                }
            }
        }

        // If there are people doing jobs, and the jobs have unread queries, launch one of the queries

        for (HumanWorker human : humans) {
            if (human.currentJobID > -1 && human.currentQueryID == -1) {
                assert(human.currentSocketID > -1);
                APISocket apiSocket = apiSockets.get(human.currentSocketID);

                if (apiSocket.jobIDToPosting.get(human.currentJobID).queries.size() > 0) {
                    Query q = apiSocket.jobIDToPosting.get(human.currentJobID).queries.poll();

                    int jobID = human.currentJobID;
                    int queryID = q.queryID;

                    // The callback for once we've answered a query

                    new Thread(() -> {
                        human.launchQuery(q.json, (i) -> {
                            // Set the human to look like they're not answering any queries
                            human.currentQueryID = -1;
                            apiSockets.get(human.currentSocketID).queryResponse(jobID, queryID, i);
                            // Re-run this code
                            updateHumanJobAssignments();
                        });
                    }).start();
                }
            }
        }
    }

    ////////////////////////////////////////////////////////////////////////
    // Server related stuff
    ////////////////////////////////////////////////////////////////////////

    boolean running = true;
    ServerSocket server = null;

    /**
     * Run the main server thread on port 2109
     */
    @Override
    public void run() {
        // Put our handle into static space so that WebSocket handlers can find us
        currentInstance = this;

        try {
            server = new ServerSocket(2109);
        } catch (IOException e) {
            e.printStackTrace();
            // This probably means the socket is taken, and we're already running a HumanSourceServer
            return;
        }

        while (running) {
            try {
                int socketID = apiIDCounter.getAndIncrement();
                APISocket apiSocket = new APISocket(server.accept(), socketID);
                apiSockets.put(socketID, apiSocket);
                new Thread(apiSocket).start();
            } catch (SocketException e) {
                // This means we were interrupted, which is actually fine, so do nothing
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Stop and interrupt the main server thread
     */
    public void stop() {
        running = false;
        // Close the socket
        if (server != null) {
            try {
                server.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // Terminate all existing jobs, and send all workers home

        for (HumanWorker worker : humans) {
            worker.sayGoodbye();
        }
    }

    /**
     * The socket for handling a single client for the server. This accepts and handles API requests in the form or
     * HumanAPIProto.APIRequest, and sends responses of the form HumanAPIProto.APIResponse.
     */
    private class APISocket implements Runnable {
        final Socket s;
        int socketID;

        // A list of so far unsatisfied job postings
        public final Queue<JobPosting> jobPostings = new ArrayDeque<>();
        private Map<Integer, JobPosting> jobIDToPosting = new HashMap<>();

        public Map<HumanWorker, Set<Integer>> humansUsedOnlyOnceIDs = new HashMap<>();

        public APISocket(Socket s, int socketID) throws IOException {
            this.s = s;
            this.socketID = socketID;
        }

        /**
         * Parse the incoming messages
         */
        @Override
        public void run() {
            while (running) {
                try {
                    // NOTE: This is a blocking operation
                    HumanAPIProto.APIRequest req = HumanAPIProto.APIRequest.parseDelimitedFrom(s.getInputStream());

                    if (req == null) {
                        release();
                        break;
                    }

                    // Most of these changes might conceivably mess with the workings of updateJobAssignment(), so we
                    // just lock the whole bunch
                    synchronized (currentInstance) {
                        if (req.getType() == HumanAPIProto.APIRequest.MessageType.JobPosting) {
                            JobPosting jp = new JobPosting();
                            jp.jobID = req.getJobID();
                            jp.onlyOnceID = req.getOnlyOnceID();
                            jp.json = req.getJSON();

                            jobIDToPosting.put(req.getJobID(), jp);
                            jobPostings.add(jp);
                        } else if (req.getType() == HumanAPIProto.APIRequest.MessageType.Query) {
                            Query q = new Query();
                            q.json = req.getJSON();
                            q.queryID = req.getQueryID();

                            // Add to the appropriate job
                            jobIDToPosting.get(req.getJobID()).queries.add(q);
                        } else if (req.getType() == HumanAPIProto.APIRequest.MessageType.JobRelease) {
                            // Check if the job is in the current list of humans, and if so, release the human
                            for (HumanWorker worker : humans) {
                                if (worker.currentJobID == req.getJobID() && worker.currentSocketID == socketID) {
                                    worker.endCurrentJob();

                                    // We don't send failure messages for the current job or query, because those are expected
                                    worker.currentJobID = -1;
                                    worker.currentQueryID = -1;
                                }
                            }
                            // Check if the job is in the current queue (cancelling a job before it begins), and if so, destroy it
                            JobPosting postingToDestroy = null;
                            for (JobPosting jp : jobPostings) {
                                if (jp.jobID == req.getJobID()) {
                                    postingToDestroy = jp;
                                    break;
                                }
                            }
                            if (postingToDestroy != null) jobPostings.remove(postingToDestroy);
                        } else if (req.getType() == HumanAPIProto.APIRequest.MessageType.NumAvailableQuery) {
                            int onlyOnceID = req.getOnlyOnceID();

                            // Immediately send a response

                            HumanAPIProto.APIResponse.Builder b = HumanAPIProto.APIResponse.newBuilder();
                            b.setType(HumanAPIProto.APIResponse.MessageType.NumAvailableQuery);
                            b.setJobID(onlyOnceID);

                            int numAvailable = 0;

                            // For each human that's currently connected, check if they've already used this onlyOnceID

                            for (HumanWorker human : humans) {
                                if (!humansUsedOnlyOnceIDs.containsKey(human) || !humansUsedOnlyOnceIDs.get(human).contains(onlyOnceID)) {
                                    numAvailable++;
                                }
                            }

                            System.err.println("Num available: "+numAvailable);

                            b.setQueryAnswer(numAvailable);

                            synchronized (s) {
                                try {
                                    b.build().writeDelimitedTo(s.getOutputStream());
                                    s.getOutputStream().flush();
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }
                        } else {
                            throw new IllegalStateException("Unrecognized request type: " + req.getType());
                        }
                    }
                }
                catch(Exception e) {
                    System.err.println("Closing socket "+socketID);
                    e.printStackTrace();
                    release();
                    try {
                        s.close();
                    } catch (IOException e1) {
                        e1.printStackTrace();
                    }
                    break;
                }

                // Always update after parsing a new message
                updateHumanJobAssignments();
            }
        }

        /**
         * This gets called when the socket is closed, so that we can free any resource holdings we may currently
         * have.
         */
        private void release() {
            System.err.println("Releasing socket "+socketID);
            
            synchronized (currentInstance) {
                for (HumanWorker human : humans) {
                    if (human.currentJobID != -1 && human.currentSocketID == socketID) {
                        human.endCurrentJob();

                        human.currentJobID = -1;
                        human.currentQueryID = -1;
                        human.currentSocketID = -1;
                    }
                }

                apiSockets.remove(socketID);

                updateHumanJobAssignments();
            }
        }

        /**
         * This means we got a response from a human about one of the queries that was launched our way.
         *
         * @param jobID the id of the job this query was from
         * @param queryID the id of the query, so we can accurately respond
         * @param i the response variable
         */
        public void queryResponse(int jobID, int queryID, int i) {
            HumanAPIProto.APIResponse.Builder b = HumanAPIProto.APIResponse.newBuilder();
            b.setJobID(jobID);
            b.setType(HumanAPIProto.APIResponse.MessageType.QueryAnswer);
            b.setQueryAnswer(i);
            b.setQueryID(queryID);

            synchronized (s) {
                try {
                    b.build().writeDelimitedTo(s.getOutputStream());
                    s.getOutputStream().flush();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        /**
         * This means a query failed, and we need to message back to the owner.
         *
         * @param jobID the id of the job this query was from
         * @param queryID the id of the query, so we can accurately respond
         */
        public void queryFailure(int jobID, int queryID) {
            HumanAPIProto.APIResponse.Builder b = HumanAPIProto.APIResponse.newBuilder();
            b.setJobID(jobID);
            b.setType(HumanAPIProto.APIResponse.MessageType.QueryFailure);
            b.setQueryID(queryID);

            synchronized (s) {
                try {
                    b.build().writeDelimitedTo(s.getOutputStream());
                    s.getOutputStream().flush();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        /**
         * This means a person entered a job
         *
         * @param jobID the id of the job that was entered
         */
        public void jobAccepted(int jobID) {
            HumanAPIProto.APIResponse.Builder b = HumanAPIProto.APIResponse.newBuilder();
            b.setJobID(jobID);
            b.setType(HumanAPIProto.APIResponse.MessageType.HumanArrival);

            synchronized (s) {
                try {
                    b.build().writeDelimitedTo(s.getOutputStream());
                    s.getOutputStream().flush();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        /**
         * This means a person left a job in the middle, without the system explicitly dismissing them.
         *
         * @param jobID the id of the job that was abandoned
         */
        public void jobAbandoned(int jobID) {
            HumanAPIProto.APIResponse.Builder b = HumanAPIProto.APIResponse.newBuilder();
            b.setJobID(jobID);
            b.setType(HumanAPIProto.APIResponse.MessageType.HumanExit);

            synchronized (s) {
                try {
                    b.build().writeDelimitedTo(s.getOutputStream());
                    s.getOutputStream().flush();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        /**
         * This will check, without altering the structures, what the state of the onlyOnceID is.
         *
         * @param human the human trying to do a job
         * @param onlyOnceID the onlyOnceID of the job
         * @return whether this human has already used up this onlyOnceID
         */
        public boolean canUseOnlyOnceID(HumanWorker human, int onlyOnceID) {
            if (!humansUsedOnlyOnceIDs.containsKey(human)) return true;
            else return !humansUsedOnlyOnceIDs.get(human).contains(onlyOnceID);
        }

        /**
         * This will alter the structures on this APIRequest to use an onlyOnceID on this job for this human.
         *
         * @param human the human acquiring the job
         * @param onlyOnceID the onlyOnceID of the job being acquired
         */
        public void useOnlyOnceID(HumanWorker human, int onlyOnceID) {
            if (!humansUsedOnlyOnceIDs.containsKey(human)) humansUsedOnlyOnceIDs.put(human, new HashSet<>());
            humansUsedOnlyOnceIDs.get(human).add(onlyOnceID);
        }
    }

    /**
     * The baby class for holding a job that's currently in progress
     */
    private static class JobPosting {
        public int jobID;
        public int onlyOnceID;
        public String json;
        final public Queue<Query> queries = new ArrayDeque<>();
    }

    /**
     * The baby class for holding a query that's currently in progress
     */
    private static class Query {
        public String json;
        public int queryID;
    }
}
