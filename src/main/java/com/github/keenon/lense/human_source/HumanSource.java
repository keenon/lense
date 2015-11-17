package com.github.keenon.lense.human_source;

import com.github.keenon.loglinear.model.GraphicalModel;
import com.github.keenon.lense.gameplay.Game;

import java.util.function.Consumer;

/**
 * Created by keenon on 10/3/15.
 *
 * Manages the creation, pooling, and assignment of humans to tasks.
 */
public abstract class HumanSource {
    public abstract Game.ArtificialHumanProvider getSimulatedProvider();

    // Need to handle job postings, then querying humans once job postings have been answered
    // Job postings are handled with the HumanSource

    /**
     * This registers a job posting
     * @param model the model to which we will be assigning the human
     * @param jobAnsweredCallback the callback that is triggered once we have a human to do the job
     */
    public abstract void makeJobPosting(GraphicalModel model, Consumer<HumanHandle> jobAnsweredCallback);

    /**
     * Closes the system
     */
    public abstract void close();
}
