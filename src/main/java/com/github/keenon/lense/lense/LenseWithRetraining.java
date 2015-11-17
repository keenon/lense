package com.github.keenon.lense.lense;

import com.github.keenon.lense.gameplay.Game;
import com.github.keenon.lense.gameplay.players.GamePlayer;
import com.github.keenon.loglinear.learning.AbstractBatchOptimizer;
import com.github.keenon.loglinear.learning.BacktrackingAdaGradOptimizer;
import com.github.keenon.loglinear.learning.LogLikelihoodDifferentiableFunction;
import com.github.keenon.loglinear.model.ConcatVector;
import com.github.keenon.loglinear.model.ConcatVectorNamespace;
import com.github.keenon.loglinear.model.GraphicalModel;
import com.github.keenon.lense.human_source.HumanSource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Created by keenon on 10/7/15.
 *
 * Handles running Lense, and a thread that learns new weights from Lense output whenever new classifications finish.
 * This is pretty basic stuff, but it's a very common case for Lense use.
 */
public class LenseWithRetraining {
    Lense lense;

    public ConcatVector weights;
    final List<GraphicalModel> trainingSet = new ArrayList<>();
    public ConcatVectorNamespace namespace;
    boolean running = true;
    boolean finished = false;

    public double l2Reg = 0.001;

    public Consumer<GraphicalModel> overrideSetTrainingLabels = null;

    public LenseWithRetraining(HumanSource humans,
                               GamePlayer gamePlayer,
                               Function<Game, Double> utility,
                               ConcatVector initialWeights,
                               ConcatVectorNamespace namespace) {
        weights = initialWeights;
        this.namespace = namespace;
        lense = new Lense(humans, gamePlayer, utility, initialWeights);

        new Thread(new Trainer()).start();
    }

    /**
     * Training thread, that runs in parallel to the inference code, and retrains the model whenever changes happen.
     */
    private class Trainer implements Runnable {
        int lastTrainingSetSize = 0;

        @Override
        public void run() {
            while (running) {
                // Wait for more data to become available
                if (trainingSet.size() == lastTrainingSetSize || trainingSet.size() < 5) {
                    synchronized (trainingSet) {
                        try {
                            trainingSet.wait();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }
                // Run training if more data is available
                else {
                    lastTrainingSetSize = trainingSet.size();

                    GraphicalModel[] trainingData = trainingSet.toArray(new GraphicalModel[trainingSet.size()]);
                    AbstractBatchOptimizer opt = new BacktrackingAdaGradOptimizer();

                    weights = opt.optimize(trainingData, new LogLikelihoodDifferentiableFunction(), weights, l2Reg, 5.0e-3, true);

                    namespace.setDenseFeature(weights, "BIAS", new double[]{1.0});
                }
            }
            finished = true;
        }
    }

    /**
     * Kills the training thread, so that the program can terminate.
     */
    public void shutdown() {
        running = false;
        // Let the training thread wake up, so it can terminate
        synchronized (trainingSet) {
            trainingSet.notifyAll();
        }
    }

    /**
     * @return true when the main learning thread has completely exited. False otherwise.
     */
    public boolean isFinished() {
        return finished;
    }

    /**
     * To the outside world, LENSE is just a better version of CliqueTree, and will attempt to get MAP estimates for
     * models by using humans to deal with ambiguity.
     *
     * @param model the model to run LENSE on
     * @return a best guess for the true labels of the model
     */
    public int[] getMAP(GraphicalModel model, Object moveMonitor) {
        Game game = new Game(model, lense.weights, lense.humans.getSimulatedProvider());
        return playGame(game, moveMonitor).getMAP();
    }

    /**
     * If for diagnosis or debugging or other reasons you want to be able to introspect into the game after it was
     * played, you should use this call, so you can get the whole game entity back from LENSE.
     *
     * @param game the game to be played out
     * @return the game object after gameplay is complete
     */
    public Game playGame(Game game, Object moveMonitor) {
        GraphicalModel modelClone = game.model.cloneModel();

        lense.playGame(game, moveMonitor);

        if (overrideSetTrainingLabels != null) {
            overrideSetTrainingLabels.accept(modelClone);
        }
        else {
            int[] map = game.getMAP();
            for (int i = 0; i < map.length; i++) {
                if (map[i] > -1) {
                    modelClone.getVariableMetaDataByReference(i).put(LogLikelihoodDifferentiableFunction.VARIABLE_TRAINING_VALUE, "" + map[i]);
                }
            }
        }
        trainingSet.add(modelClone);

        // Wake up the training thread, if it was sleeping
        synchronized (trainingSet) {
            trainingSet.notifyAll();
        }

        return game;
    }
}
