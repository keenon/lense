package com.github.keenon.lense.human_server.mturk;

import com.github.keenon.lense.human_server.MTurkAPIProto;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Created by keenon on 10/15/15.
 *
 * This manages intercepting requests from the client, and getting the answers to send back to the client. Its
 * constructor makes this trivial to mock, so it's easy to test.
 */
public class MTurkServer implements Runnable {
    Function<Integer,String> makeJobPosting;
    Supplier<Integer> getNumberOfWorkers;

    ServerSocket serverSocket;
    boolean running = true;

    public MTurkServer(Function<Integer,String> makeJobPosting, Supplier<Integer> getNumberOfWorkers) {
        this.makeJobPosting = makeJobPosting;
        this.getNumberOfWorkers = getNumberOfWorkers;
    }

    /**
     * This will run the basic server thread, that allows people to connect and request additional human workers
     */
    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(2110);
        } catch (IOException e) {
            e.printStackTrace();
        }

        while (running) {
            try {
                Socket s = serverSocket.accept();
                new Thread(new APIThread(s)).start();
            } catch (IOException e) {
                // This means we were probably interrupted, and 'running' is now false
            }
        }
    }

    /**
     * This holds a single API consumer, who is able to make requests about hiring and firing.
     */
    private class APIThread implements Runnable {
        Socket s;
        public APIThread(Socket s) {
            this.s = s;
        }

        @Override
        public void run() {
            while (running) {
                try {
                    MTurkAPIProto.MTurkAPIRequest request = MTurkAPIProto.MTurkAPIRequest.parseDelimitedFrom(s.getInputStream());
                    if (request == null) continue;

                    // Launch a thread to handle writing the response, because we expect the calls we're making to be
                    // blocking

                    new Thread(() -> {
                        MTurkAPIProto.MTurkAPIResponse.Builder builder = MTurkAPIProto.MTurkAPIResponse.newBuilder();
                        builder.setReqID(request.getReqID());
                        builder.setType(request.getType());

                        switch (request.getType()) {
                            case HireWorkers:
                                int numToHire = request.getNumToHire();
                                String url = makeJobPosting.apply(numToHire);
                                builder.setPostURL(url);
                                break;
                            case GetNumberOfWorkers:
                                int numWorkers = getNumberOfWorkers.get();
                                builder.setNumWorkers(numWorkers);
                                break;
                        }

                        synchronized (s) {
                            try {
                                builder.build().writeDelimitedTo(s.getOutputStream());
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }).start();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * This kills the API server
     */
    public void stop() {
        running = false;
        try {
            serverSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
