package com.github.keenon.lense.human_server;

import com.google.protobuf.InvalidProtocolBufferException;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Created by keenon on 10/12/15.
 *
 * This manages all the communication complexity of talking to the server over proto, and exposes a nice simple
 * interface of callbacks for the client to use.
 */
public class HumanSourceClient {
    public Socket socket;

    /**
     * This constructs a client for the HumanSourceServer that will connect by socket.
     *
     * @param host the host address of the HumanSourceServer
     * @param port the port of the HumanSourceServer
     * @throws IOException in case the socket fails to connect as expected
     */
    public HumanSourceClient(String host, int port) throws IOException {
        socket = new Socket(host, port);

        new Thread(() -> {
            while (true) {
                try {
                    HumanAPIProto.APIResponse response = HumanAPIProto.APIResponse.parseDelimitedFrom(socket.getInputStream());
                    if (response == null) break;
                    // This actually does all the work
                    parseReceivedMessage(response);
                }
                catch (InvalidProtocolBufferException e) {
                    // This will trip if we closed the socket, and were trying to read from it. This is actually
                    // totally fine, since this is how we're supposed to exit this loop.
                    break;
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    List<JobHandle> jobHandles = new ArrayList<>();

    /**
     * This lists a new job with the server.
     *
     * @param onlyOnceID this is for preventing multiple jobs referring to the same entity from being taken by the same
     *                   worker. We want two *independent* labels for the same token, not the same person's opinion
     *                   twice. To accomplish this, a worker can only do a single job for each onlyOnceID.
     * @return a handle to the created job
     */
    public synchronized JobHandle createJob(String JSON, int onlyOnceID, Runnable jobAccepted, Runnable jobAbandoned) {
        JobHandle handle = new JobHandle(socket, onlyOnceID, jobAccepted, jobAbandoned);
        handle.jobID = jobHandles.size();
        jobHandles.add(handle);

        HumanAPIProto.APIRequest.Builder b = HumanAPIProto.APIRequest.newBuilder();
        b.setType(HumanAPIProto.APIRequest.MessageType.JobPosting);
        b.setJobID(handle.jobID);
        b.setOnlyOnceID(onlyOnceID);
        b.setJSON(JSON);

        try {
            synchronized (socket) {
                b.build().writeDelimitedTo(socket.getOutputStream());
                socket.getOutputStream().flush();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return handle;
    }

    /**
     * This is the handle that's passed back to users who have asked for a job
     */
    public static class JobHandle {
        int jobID;
        int onlyOnceID;

        Runnable jobAccepted;
        Runnable jobAbandoned;

        List<Consumer<Integer>> querySuccessCallbacks = new ArrayList<>();
        List<Runnable> queryFailedCallbacks = new ArrayList<>();

        Socket socket;

        public JobHandle(Socket socket, int onlyOnceID, Runnable jobAccepted, Runnable jobAbandoned) {
            this.socket = socket;
            this.onlyOnceID = onlyOnceID;
            this.jobAccepted = jobAccepted;
            this.jobAbandoned = jobAbandoned;
        }

        /**
         * This is the meat of why you keep around a JobHandle at all. It allows you to launch queries on that job!
         *
         * @param JSON the json slug to send to the client's browser with this query
         * @param returnValue the callback that will be used if we get a value
         * @param queryFailed the callback that will be used if the query fails for any reason
         */
        public synchronized void launchQuery(String JSON, Consumer<Integer> returnValue, Runnable queryFailed) {
            assert(queryFailedCallbacks.size() == querySuccessCallbacks.size());

            int queryID = querySuccessCallbacks.size();
            querySuccessCallbacks.add(returnValue);
            queryFailedCallbacks.add(queryFailed);

            HumanAPIProto.APIRequest.Builder b = HumanAPIProto.APIRequest.newBuilder();
            b.setType(HumanAPIProto.APIRequest.MessageType.Query);
            b.setJobID(jobID);
            b.setQueryID(queryID);
            b.setJSON(JSON);

            try {
                synchronized (socket) {
                    b.build().writeDelimitedTo(socket.getOutputStream());
                    socket.getOutputStream().flush();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        /**
         * This closes the job on command, ending the work of any humans involved in the job and letting them move down
         * the queue
         */
        public void closeJob() {
            HumanAPIProto.APIRequest.Builder b = HumanAPIProto.APIRequest.newBuilder();
            b.setType(HumanAPIProto.APIRequest.MessageType.JobRelease);
            b.setJobID(jobID);

            try {
                synchronized (socket) {
                    b.build().writeDelimitedTo(socket.getOutputStream());
                    socket.getOutputStream().flush();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * This shuts down the client
     */
    public void close() {
        try {
            // This will interrupt the main client with an exception, so we'll stop reading incoming responses
            socket.close();
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * This is our handle for dealing with incoming messages
     * @param response the parsed proto from the server
     */
    private void parseReceivedMessage(HumanAPIProto.APIResponse response) {
        int jobID = response.getJobID();

        synchronized (this) {
            switch (response.getType()) {
                case HumanArrival:
                    jobHandles.get(jobID).jobAccepted.run();
                    break;
                case HumanExit:
                    jobHandles.get(jobID).jobAbandoned.run();
                    break;
                case QueryAnswer:
                    int queryID = response.getQueryID();
                    jobHandles.get(jobID).querySuccessCallbacks.get(queryID).accept(response.getQueryAnswer());
                    break;
                case QueryFailure:
                    queryID = response.getQueryID();
                    jobHandles.get(jobID).queryFailedCallbacks.get(queryID).run();
                    break;
                default:
                    System.err.println("Unrecognized message type: " + response.getType());
                    break;
            }
        }
    }
}
