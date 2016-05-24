package com.github.keenon.lense.human_server.mturk;

import com.github.keenon.lense.human_server.MTurkAPIProto;
import com.google.protobuf.InvalidProtocolBufferException;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Created by keenon on 10/15/15.
 *
 * This manages interacting with the MTurkSystem over a network, so that we can hire and fire workers without rebooting
 * the main system which manages payments.
 */
public class MTurkClient {
    Socket socket;
    AtomicInteger requestCounter = new AtomicInteger();
    Map<Integer, Consumer<String>> jobURLCallbacks = new HashMap<>();
    Map<Integer, Consumer<Integer>> numWorkersCallbacks = new HashMap<>();

    /**
     * This creates a client connection to the specified host and port. Throws an exception if the connection fails
     * (almost always because nobody is listening on the other end).
     *
     * @param host the host address
     * @param port the port to connect on
     * @throws IOException of the socket fails to construct
     */
    public MTurkClient(String host, int port) throws IOException {
        socket = new Socket(host, port);

        Thread t = new Thread(() -> {
            while (true) {
                try {
                    if (socket.isClosed()) break;
                    // While there's nothing available, sleep as quietly as possible, and eventually kill the process
                    // on a local timeout
                    if (socket.getInputStream().available() == 0) {
                        Thread.sleep(20);
                        continue;
                    }

                    MTurkAPIProto.MTurkAPIResponse response = MTurkAPIProto.MTurkAPIResponse.parseDelimitedFrom(socket.getInputStream());
                    if (response == null) break;

                    int reqID = response.getReqID();

                    synchronized (this) {
                        switch (response.getType()) {
                            case HireWorkers:
                                assert(jobURLCallbacks.containsKey(reqID));
                                jobURLCallbacks.get(reqID).accept(response.getPostURL());
                                jobURLCallbacks.remove(reqID);
                                break;
                            case GetNumberOfWorkers:
                                assert(numWorkersCallbacks.containsKey(reqID));
                                numWorkersCallbacks.get(reqID).accept(response.getNumWorkers());
                                numWorkersCallbacks.remove(reqID);
                                break;
                        }
                    }
                }
                catch (InvalidProtocolBufferException e) {
                    // This will trip if we closed the socket, and were trying to read from it. This is actually
                    // totally fine, since this is how we're supposed to exit this loop.
                    break;
                }
                catch (SocketException e) {
                    // This may also trip if we closed the socket, and were trying to read from it. This is actually
                    // totally fine, since this is how we're supposed to exit this loop.
                    break;
                }
                catch (IOException e) {
                    // This may also trip if we closed the socket, and were trying to read from it. This is actually
                    // totally fine, since this is how we're supposed to exit this loop.
                    break;
                } catch (InterruptedException e) {
                    // Got interrupted during sleep, just continue
                    e.printStackTrace();
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    /**
     * This creates a job posting on MTurk, and writes the URL where you can find the job to jobURLCallback once complete.
     *
     * @param numToHire the number of turkers to get
     * @param jobURLCallback the URL where you can find the job
     */
    public synchronized void hireWorkers(int numToHire, Consumer<String> jobURLCallback) {
        int reqID = requestCounter.getAndIncrement();
        jobURLCallbacks.put(reqID, jobURLCallback);

        MTurkAPIProto.MTurkAPIRequest.Builder builder = MTurkAPIProto.MTurkAPIRequest.newBuilder();
        builder.setType(MTurkAPIProto.MessageType.HireWorkers);
        builder.setReqID(reqID);
        builder.setNumToHire(numToHire);

        try {
            builder.build().writeDelimitedTo(socket.getOutputStream());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * This retrieves the number of workers currently working as well as all potential workers (job postings) from the
     * server.
     *
     * @param numWorkersCallback the callback which receives the number of workers once the API calls return
     */
    public synchronized void getNumberOfWorkers(Consumer<Integer> numWorkersCallback) {
        int reqID = requestCounter.getAndIncrement();
        numWorkersCallbacks.put(reqID, numWorkersCallback);

        MTurkAPIProto.MTurkAPIRequest.Builder builder = MTurkAPIProto.MTurkAPIRequest.newBuilder();
        builder.setType(MTurkAPIProto.MessageType.GetNumberOfWorkers);
        builder.setReqID(reqID);

        try {
            builder.build().writeDelimitedTo(socket.getOutputStream());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * This is just a blocking wrapper around hireWorkers()
     *
     * @param numToHire the number of workers to hire
     * @return the URL of the workers
     */
    public String hireWorkersBlocking(int numToHire) {
        Object block = new Object();
        final String[] jobURL = {null};
        hireWorkers(numToHire, (url) -> {
            jobURL[0] = url;
            synchronized (block) {
                block.notifyAll();
            }
        });

        while (jobURL[0] == null) {
            synchronized (block) {
                try {
                    block.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        return jobURL[0];
    }

    /**
     * This is just the blocking wrapper around getNumberOfWorkers()
     *
     * @return the current number of workers, including outstanding requests
     */
    public int getNumberOfWorkersBlocking() {
        Object block = new Object();
        final int[] numWorkers = {-1};
        getNumberOfWorkers((num) -> {
            numWorkers[0] = num;
            synchronized (block) {
                block.notifyAll();
            }
        });

        while (numWorkers[0] == -1) {
            synchronized (block) {
                try {
                    block.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        return numWorkers[0];
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
}
