package com.github.keenon.lense.human_server;

import java.util.function.Consumer;

/**
 * Created by keenon on 10/11/15.
 *
 * This is the abstract interface for a single query answering person. This is a slightly different interface from the
 * one that the gameplayer sees, since this object lives as long as the connection to the person we're trying to reach.
 */
public abstract class HumanWorker {

    // The callback for if the worker quits before they are sent home
    public Runnable userQuit = () -> {};

    // For checking the state of the worker
    boolean working = true;
    public int currentSocketID = -1;
    public int currentJobID = -1;
    public int currentQueryID = -1;

    /**
     * This gets called at the beginning of any new job
     *
     * @param JSON the JSON slug that needs to be passed along to the client to configure it for the new job
     */
    public abstract void startNewJob(String JSON);

    /**
     * This launches a query to the user, and should display it immediately
     *
     * @param JSON the JSON slug describing the query
     * @param callback a callback that takes an integer describing which choice the human made
     */
    public abstract void launchQuery(String JSON, Consumer<Integer> callback);

    /**
     * This terminates the current job, regardless of any outstanding queries, and should wipe the screen for the user,
     * readying for the next job.
     */
    public abstract void endCurrentJob();

    /**
     * This sends the current worker home, and pays them if necessary.
     */
    public abstract void sayGoodbye();
}
