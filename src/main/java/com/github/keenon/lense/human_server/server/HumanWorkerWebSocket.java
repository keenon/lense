package com.github.keenon.lense.human_server.server;

import com.github.keenon.lense.human_server.HumanSourceServer;
import com.github.keenon.lense.human_server.HumanWorker;
import com.github.keenon.lense.human_server.payments.HistoricalDatabase;
import com.github.keenon.lense.human_server.payments.PaymentsThread;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.*;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.function.Consumer;

/**
 * Created by keenon on 10/7/15.
 *
 * This is a single socket for handling a worker through a web browser.
 */
@WebSocket
public class HumanWorkerWebSocket extends HumanWorker {
    /**
     * An SLF4J Logger for this class.
     */
    private static final Logger log = LoggerFactory.getLogger(HumanWorkerWebSocket.class);

    Consumer<String> sendResponse;

    // Mechanical Turk identifiers

    String assignmentID;
    String hitID;
    String workerID;

    String jobAfterInitialization = null;

    Queue<JSONObject> queriesQueue = new ArrayDeque<>();
    Queue<Consumer<Integer>> queriesCallbackQueue = new ArrayDeque<>();
    Consumer<Integer> currentQueryCallback = null;

    public HumanWorkerWebSocket() {
        log.info("New Socket Object Created!");
    }

    @Override
    public void startNewJob(String JSON) {
        synchronized (HumanSourceServer.currentInstance) {
            // Wrap the query in ID values, so we can map the response correctly

            JSONObject queryPayload = (JSONObject) JSONValue.parse(JSON);
            JSONObject query = new JSONObject();
            query.put("type", "job");
            query.put("payload", queryPayload);
            query.put("total-queries-answered", HistoricalDatabase.numberOfTasksThisWorkUnit(workerID));

            if (currentState != SocketState.CLIENT_READY) {
                jobAfterInitialization = query.toJSONString();
            }
            else {
                setCurrentState(SocketState.CLIENT_ON_JOB);
                sendResponse.accept(query.toJSONString());
            }

            // If we are in the appropriate state to launch a query, launch one immediately
            checkAndLaunchQuery();
        }
    }

    @Override
    public void launchQuery(String JSON, Consumer<Integer> callback) {
        synchronized (HumanSourceServer.currentInstance) {
            // Wrap the query in ID values, so we can map the response correctly

            JSONObject queryPayload = (JSONObject)JSONValue.parse(JSON);
            JSONObject query = new JSONObject();
            query.put("type", "query");
            query.put("payload", queryPayload);

            queriesQueue.add(query);
            queriesCallbackQueue.add(callback);

            // If we are in the appropriate state to launch a query, launch one immediately
            checkAndLaunchQuery();
        }
    }

    @Override
    public void endCurrentJob() {
        synchronized (HumanSourceServer.currentInstance) {
            // Sometimes, if a job finishes before we got the start message, weird stuff will happen, so make sure it
            // doesn't by blocking based on states
            if (currentState == SocketState.CLIENT_ON_JOB || currentState == SocketState.CLIENT_WORKING) {
                JSONObject query = new JSONObject();
                query.put("type", "job-cancelled");
                query.put("total-queries-answered", HistoricalDatabase.numberOfTasksThisWorkUnit(workerID));

                sendResponse.accept(query.toJSONString());

                queriesQueue.clear();
                for (Consumer<Integer> callback : queriesCallbackQueue) callback.accept(-1);
                queriesCallbackQueue.clear();

                if (currentState == SocketState.CLIENT_WORKING) {
                    currentQueryCallback.accept(-1);
                }

                currentQueryCallback = null;

                setCurrentState(SocketState.CLIENT_READY);
            }
        }
    }

    @Override
    public void sayGoodbye() {
        synchronized (HumanSourceServer.currentInstance) {
            if (currentState != SocketState.CLIENT_CLOSED) {
                JSONObject termination = new JSONObject();
                termination.put("type", "early-termination");
                sendResponse.accept(termination.toJSONString());

                setCurrentState(SocketState.CLIENT_CLOSED);
            }
        }
    }

    /**
     * We treat the HumanWorkerWebSocket as a finite state machine, which lets us sprinkle asserts all over the place, and cut out
     * a bunch of possible error cases if synchronization stuff doesn't work as planned.
     *
     * The ascii art for the state diagram is as follows:
     *
     * UNREADY -> INITIALIZED -> CLIENT_READY -> CLIENT_ON_JOB <-> CLIENT_WORKING
     *                 |               |                |                |
     *                 +-------> CLIENT_CLOSED <--------+----------------+
     */
    enum SocketState {
        UNREADY,
        INITIALIZED,
        CLIENT_READY,
        CLIENT_ON_JOB,
        CLIENT_WORKING,
        CLIENT_CLOSED,
    }
    private SocketState currentState = SocketState.UNREADY;

    /**
     * Sets the current state, and runs a gauntlet of asserts to verify that no state change is happening unexpectedly.
     * @param state the next state to take.
     */
    private void setCurrentState(SocketState state) {
        switch (state) {
            case UNREADY:
                break;
            case INITIALIZED:
                assert currentState == SocketState.UNREADY;
                break;
            case CLIENT_READY:
                assert (currentState == SocketState.INITIALIZED ||
                        currentState == SocketState.CLIENT_ON_JOB);
                break;
            case CLIENT_ON_JOB:
                assert (currentState == SocketState.CLIENT_READY ||
                        currentState == SocketState.CLIENT_WORKING);
                break;
            case CLIENT_WORKING:
                assert currentState == SocketState.CLIENT_ON_JOB;
                break;
            case CLIENT_CLOSED:
                assert (currentState == SocketState.INITIALIZED ||
                        currentState == SocketState.CLIENT_READY ||
                        currentState == SocketState.CLIENT_ON_JOB ||
                        currentState == SocketState.CLIENT_WORKING ||
                        currentState == SocketState.CLIENT_CLOSED);
                HistoricalDatabase.endedWork(workerID);
                break;
        }
        currentState = state;
    }

    /**
     * Checks if we can and should be launching a query, and then launches if the answer is yes
     */
    private void checkAndLaunchQuery() {
        if (currentState == SocketState.CLIENT_ON_JOB && !queriesQueue.isEmpty()) {
            assert(queriesQueue.size() == queriesCallbackQueue.size());
            setCurrentState(SocketState.CLIENT_WORKING);

            JSONObject query = queriesQueue.poll();
            query.put("total-queries-answered", HistoricalDatabase.numberOfTasksThisWorkUnit(workerID));

            sendResponse.accept(query.toJSONString());
            currentQueryCallback = queriesCallbackQueue.poll();
        }
    }

    /**
     * This gets called when a new WebSocket connects to the web server. It then initializes all the lifecycle stuff.
     *
     * @param session the Jetty session used by the websocket
     */
    @OnWebSocketConnect
    public void onReady(Session session) {
        synchronized (HumanSourceServer.currentInstance) {
            log.info("Browser connected.");

            setCurrentState(SocketState.UNREADY);
            initialize((String msg) -> {
                // r.getResponse().write(msg);
                // r.getBroadcaster().broadcast(msg);
                try {
                    session.getRemote().sendString(msg);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });

            // Register ourselves to the server

            HumanSourceServer.currentInstance.registerHuman(this);
        }
    }

    /**
     * This initializes all the asynchronous logic for getting jobs, and can be called from within a test without having
     * to mock the whole AtmosphereResource set.
     *
     * @param sendResponse the response function, which can be set to something besides writing to a socket during tests
     */
    public void initialize(Consumer<String> sendResponse) {
        this.sendResponse = sendResponse;
        setCurrentState(SocketState.INITIALIZED);
    }

    /**
     * Called when a socket terminates. Can get called multiple times by the same socket, annoyingly.
     */
    @OnWebSocketClose
    public void onDisconnect(int statusCode, String reason) {
        synchronized (HumanSourceServer.currentInstance) {
            if (currentState != SocketState.CLIENT_CLOSED) {

                // Run the callback that removes us from external lists

                userQuit.run();

                setCurrentState(SocketState.CLIENT_CLOSED);
            }
        }
    }

    /**
     * Called when a new message arrives. Never called before initialize();
     *
     * @param message the text of the message
     * @throws IOException
     */
    @OnWebSocketMessage
    public void onMessage(String message) throws IOException {
        synchronized (HumanSourceServer.currentInstance) {
            try {
                JSONObject obj = (JSONObject) JSONValue.parse(message);
                if (obj.containsKey("type")) {

                    // Handle initialization

                    if (obj.get("type").equals("ready-message")) {
                        assignmentID = (String) obj.get("assignment-id");
                        hitID = (String) obj.get("hit-id");
                        workerID = (String) obj.get("worker-id");

                        log.info("Worker "+workerID+" connected!");

                        // This should only ever arrive when we're in the initialized state
                        if (currentState != SocketState.INITIALIZED) {
                            log.warn("Received erroneous ready message");
                            return;
                        }

                        setCurrentState(SocketState.CLIENT_READY);

                        if (jobAfterInitialization != null) {
                            log.info("Job after initialization: "+jobAfterInitialization);
                            sendResponse.accept(jobAfterInitialization);
                            setCurrentState(SocketState.CLIENT_ON_JOB);
                            jobAfterInitialization = null;
                        }

                        HistoricalDatabase.startedWork(workerID, assignmentID);

                        // Send a message back with the number of queries this person has completed for us

                        JSONObject initializingMessage = new JSONObject();
                        initializingMessage.put("total-queries-answered", HistoricalDatabase.numberOfTasksThisWorkUnit(workerID));
                        initializingMessage.put("on-call-duration", PaymentsThread.minimumStayingTime);
                        sendResponse.accept(initializingMessage.toJSONString());

                        // If we have queries waiting, launch one
                        checkAndLaunchQuery();
                    }

                    // Handle query responses

                    else if (obj.get("type").equals("query-response")) {
                        if (currentState != SocketState.CLIENT_WORKING) {
                            log.warn("Ignoring erroneous query-response message");
                            return;
                        }

                        // Record that we answered another query
                        HistoricalDatabase.didTask(workerID);

                        assert (currentQueryCallback != null);
                        int queryResponse = ((Long) obj.get("query-response")).intValue();
                        currentQueryCallback.accept(queryResponse);

                        setCurrentState(SocketState.CLIENT_ON_JOB);

                        // If we have queries waiting, launch one
                        checkAndLaunchQuery();
                    }

                    // Receiving a keep-alive message

                    else if (obj.get("type").equals("keep-alive")) {
                        // Do nothing
                    }
                    else {
                        throw new IllegalStateException("Unrecognized message type: " + obj.toJSONString());
                    }
                }
            } catch (Exception e) {
                log.warn("Malformed JSON: " + message);
                e.printStackTrace();
                // Temporary, to prevent infinite loops
                System.exit(1);
            }
        }
    }

    /**
     * Called if the WebSocket experiences an error.
     * @param error the error we got
     */
    @OnWebSocketError
    public void handleError(Throwable error) {
        error.printStackTrace();
    }
}
