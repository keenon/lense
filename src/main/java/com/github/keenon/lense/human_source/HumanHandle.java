package com.github.keenon.lense.human_source;

import com.github.keenon.loglinear.model.ConcatVectorTable;
import com.github.keenon.lense.gameplay.distributions.ContinuousDistribution;

import java.util.function.Consumer;

/**
 * Created by keenon on 10/6/15.
 *
 * This provides access to a single human within a single game. It is not passed along from game to game, it's just the
 * one game that is used.
 */
public abstract class HumanHandle {
    private Runnable disconnectedCallback;

    /**
     * This makes a query about a single variable.
     *
     * @param variable this is the name of the variable to query, as referenced in the GraphicalModel for the game.
     * @param response this is the callback used when the human comes up with a response.
     * @param failed this is run if the query we're making doesn't succeed
     */
    public abstract void makeQuery(int variable, Consumer<Integer> response, Runnable failed);

    /**
     * Gets the error model that gamplayers will use on each variable to estimate human errors.
     * @return an array of ConcatVectorTable's, one per variable in the model, corresponding to error featurizations
     */
    public abstract ConcatVectorTable[] getErrorModel();

    /**
     * Gets the model that gameplayers will use to estimate human delays.
     * @return a ContinuousDistribution that roughly corresponds to the human delay when queried
     */
    public abstract ContinuousDistribution getDelayModel();

    /**
     * This closes the HumanHandle and releases the human to go work on other stuff.
     */
    public abstract void release();

    /**
     * This is the callback that gets used when the human disconnects early for some reason
     * @param r the callback
     */
    public void setDisconnectedCallback(Runnable r) {
        this.disconnectedCallback = r;
    }

    /**
     * Call the disconnected callback, if one has been set
     */
    protected void disconnect() {
        disconnectedCallback.run();
    }
}
