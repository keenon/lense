package com.github.keenon.lense.convenience;

import com.github.keenon.lense.gameplay.Game;
import com.github.keenon.lense.gameplay.distributions.ContinuousDistribution;
import com.github.keenon.lense.gameplay.distributions.DiscreteSetDistribution;
import com.github.keenon.lense.gameplay.players.GamePlayer;
import com.github.keenon.lense.lense.Lense;
import com.github.keenon.lense.lense.LenseWithRetraining;
import com.github.keenon.lense.storage.ModelQueryRecord;
import com.github.keenon.loglinear.model.ConcatVector;
import com.github.keenon.loglinear.model.ConcatVectorNamespace;
import com.github.keenon.loglinear.model.GraphicalModel;
import com.github.keenon.loglinear.storage.ModelBatch;
import com.github.keenon.lense.human_source.HumanSource;
import com.github.keenon.lense.human_source.ModelTagsHumanSource;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

/**
 * Created by keenon on 10/17/15.
 *
 * This handles the basic structure of running LENSE in batches, while storing intermediate results.
 */
public abstract class StaticBatchLense {
    protected ConcatVectorNamespace namespace = new ConcatVectorNamespace();
    protected ContinuousDistribution observedHumanDelays = null;

    public abstract GamePlayer getGamePlayer();
    public abstract HumanSource getHumanSource();

    /**
     * This returns the path to the file that we're using to store the ModelBatch for this particular experiment.
     * @return the path to the batch serialization file, or where it will be created
     */
    public abstract String getBatchFileLocation();

    /**
     * This returns the path to where we will store the models with their annotation, in human readable format
     */
    public abstract String getModelDumpFileLocation();

    /**
     * This returns the path to where we will store the results and analysis folders for each run
     */
    public abstract String getPerformanceReportFolder();

    String subfolderPath;
    /**
     * This returns the subfolder that is specific to this run
     */
    public String getThisRunPerformanceReportSubFolder() {
        return subfolderPath;
    }

    /**
     * This gets called when the models batch file doesn't exist yet, and needs to be created.
     * @return a ModelBatch with all the meta-data we need to do featurizing
     */
    public abstract ModelBatch createInitialModelBatch();

    /**
     * This featurizes a model, and is broken out as its own function because it can be used to test new ways to
     * featurize old ModelBatch-es, without throwing out the human labels on those ModelBatches.
     * @param model the model that needs featurizing (in the form of factors)
     */
    public abstract void featurize(GraphicalModel model);

    /**
     * This is the utility that the gameplayer will use in estimating the next move to take.
     * @param game the game we want utility for
     * @return the utility of the game at that moment
     */
    public abstract double utility(Game game);

    /**
     * This is a useful flag to set to true if you want humans to be able to progress past games that haven't finished
     * yet. You can also set this to false if you want to enable learning from past games before launching new ones.
     */
    public abstract boolean parallelBatchIgnoreRetraining();

    /**
     * If parallelBatchIgnoreRetraining() is false, then we'll be retraining a model, and being able to specify the L2
     * regularization parameter is important for some domains. That happens here.
     *
     * @return the l2 regularization coefficient
     */
    public abstract double getL2Regularization();

    /**
     * An opportunity to swap in an alternate set of gold labels for training instead of the LENSE guesses.
     */
    public Consumer<GraphicalModel> overrideSetTrainingLabels = null;

    /**
     * Configuration to allow a batch to not dump weights at every checkpoint, which can really increase performance.
     * @return whether or not to dump weights
     */
    public boolean dumpWeights() {
        return false;
    }

    /**
     * This gets you the initial weight vector, which is the only weight vector used if parallelBatchIgnoreRetraining is
     * turned on.
     * @return a weight vector.
     */
    public ConcatVector initialWeights() {
        ConcatVector weights = new ConcatVector(0);
        namespace.setDenseFeature(weights, "BIAS", new double[]{1.0});
        return weights;
    }

    /**
     * This gets called whenever we finish another game, so that the system can checkpoint
     */
    public abstract void checkpoint(List<GameRecord> gamesFinished);

    /**
     * Gets the string for the appropriate model.
     * @param model the model we'd like to dump annotations for
     * @return a string representing, in some human readable way, the annotations
     */
    public abstract String dumpModel(GraphicalModel model);

    /**
     * Prints the record of a game to a file buffered writer
     * @param gameRecord the game to write
     * @param bw the file writer
     */
    public abstract void dumpGame(GameRecord gameRecord, BufferedWriter bw) throws IOException;

    /**
     * Writes the record for a single game to the given folder.
     *
     * @param index the game number, in sequence, used for making a unique filename
     * @param gameRecord the game to write out
     * @throws IOException
     */
    public void dumpGame(int index, GameRecord gameRecord) throws IOException {
        String gamesFolder = getThisRunPerformanceReportSubFolder()+"/games";
        File gamesFolderFile = new File(gamesFolder);
        if (!gamesFolderFile.exists()) gamesFolderFile.mkdirs();

        String thisGameFile = gamesFolder+"/game_"+index+".txt";
        BufferedWriter bw = new BufferedWriter(new FileWriter(thisGameFile));
        dumpGame(gameRecord, bw);
        bw.close();
    }

    /**
     * Dumps the batch to the file in a human readable format.
     * @param batch the batch to write out
     */
    public void dumpModelBatch(ModelBatch batch) throws IOException {
        File f = new File(getModelDumpFileLocation());
        if (f.exists()) {
            f.delete();
        }
        f.createNewFile();

        BufferedWriter bw = new BufferedWriter(new FileWriter(f));

        for (GraphicalModel model : batch) {
            bw.write(dumpModel(model));
            bw.write("\n");
            bw.write("********");
            bw.write("\n");
            bw.write("\n");
        }

        bw.close();
    }

    /**
     * This handles setting everything up, and running a batch of LENSE (with training)
     */
    public void run() throws IOException {
        File reportsFolder = new File(getPerformanceReportFolder());
        if (!reportsFolder.exists()) reportsFolder.mkdirs();
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
        Date date = new Date();
        subfolderPath = getPerformanceReportFolder()+"/run_started_on_"+dateFormat.format(date);
        File subfolder = new File(subfolderPath);
        subfolder.mkdir();

        File f = new File(getBatchFileLocation());

        ModelBatch batch;

        // Create a new batch if the file doesn't exist
        if (!f.exists()) {
            batch = createInitialModelBatch();
            batch.writeToFileWithoutFactors(getBatchFileLocation());
        }
        // Read the batch from the existing file
        else {
            batch = new ModelBatch(getBatchFileLocation(), this::featurize);
        }

        // Load a subset of recorded human delays as the distribution that the computer will sample from

        List<Long> delays = new ArrayList<>();
        outer: for (GraphicalModel model : batch) {
            for (int i = 0; i < model.getVariableSizes().length; i++) {
                for (ModelQueryRecord.QueryRecord qr : ModelQueryRecord.getQueryRecordFor(model).getResponses(i)) {
                    delays.add(qr.delay);
                    if (delays.size() > 10000) break outer;
                }
            }
        }
        long[] observed = new long[delays.size()];
        for (int i = 0; i < delays.size(); i++) {
            observed[i] = delays.get(i);
        }
        observedHumanDelays = new DiscreteSetDistribution(observed);

        // Get the handles to both the game player and the human source

        GamePlayer gamePlayer = getGamePlayer();
        HumanSource humanSource = getHumanSource();

        Lense lense = null;
        LenseWithRetraining lenseWithRetraining = null;
        if (parallelBatchIgnoreRetraining()) {
            lense = new Lense(humanSource, gamePlayer, this::utility, new ConcatVector(0));
        }
        else {
            lenseWithRetraining = new LenseWithRetraining(humanSource, gamePlayer, this::utility, initialWeights(), namespace);
            lenseWithRetraining.overrideSetTrainingLabels = overrideSetTrainingLabels;
            lenseWithRetraining.l2Reg = getL2Regularization();
        }

        final Lense lenseFinal = lense;

        boolean[] running = new boolean[]{true};
        final Object writeSystemBlock = new Object();

        new Thread(() -> {
            while (running[0]) {
                synchronized (writeSystemBlock) {
                    try {
                        writeSystemBlock.wait(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                if (!running[0]) break;

                if (!(humanSource instanceof ModelTagsHumanSource)) {
                    synchronized (batch) {
                        try {
                            batch.writeToFileWithoutFactors(getBatchFileLocation());
                            dumpModelBatch(batch);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }).start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println("Waiting for writes to complete");
            synchronized (batch) {
                System.err.println("Writes completed");
            }
        }));

        List<GameRecord> games = new ArrayList<>();

        Thread[] threads = new Thread[batch.size()];

        for (int i = 0; i < batch.size(); i++) {
            GraphicalModel model = batch.get(i);

            if (model.getVariableSizes().length > 50) continue;

            ConcatVector weights;
            if (parallelBatchIgnoreRetraining()) weights = initialWeights();
            else weights = lenseWithRetraining.weights;
            Game game = new Game(model.cloneModel(), weights, humanSource.getSimulatedProvider(), humanSource.getAvailableHumans(model));

            // Set max allowed job postings to the minimum number of tags on any variable in the model
            if (humanSource instanceof ModelTagsHumanSource) {
                game.humansAvailableServerSide = 100;
                ModelQueryRecord mqr = ModelQueryRecord.getQueryRecordFor(model);
                for (int j = 0; j < model.getVariableSizes().length; j++) {
                    if (mqr.getResponses(j).size() < game.humansAvailableServerSide)
                        game.humansAvailableServerSide = mqr.getResponses(j).size();
                }
                System.err.println("Job postings allowed: "+game.humansAvailableServerSide);
            }

            if (parallelBatchIgnoreRetraining()) {
                int iFinal = i;
                threads[i] = new Thread(() -> {
                    assert(lenseFinal != null);

                    System.err.println("Starting game " + iFinal);

                    lenseFinal.playGame(game, batch);
                    synchronized (games) {
                        GameRecord gr = new GameRecord(game, initialWeights());
                        games.add(gr);
                        try {
                            dumpGame(games.size(), gr);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    checkpoint(games);

                    // transfer annotations back to the original model

                    for (int j = 0; j < model.getVariableSizes().length; j++) {
                        model.getVariableMetaDataByReference(j).putAll(game.model.getVariableMetaDataByReference(j));
                    }

                    if (!(humanSource instanceof ModelTagsHumanSource)) {
                        synchronized (batch) {
                            try {
                                batch.writeToFileWithoutFactors(getBatchFileLocation());
                                dumpModelBatch(batch);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                });
                threads[i].start();
                // Give this game a chance to make resource requests, and get in line ahead of the next one
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            else {
                assert(lenseWithRetraining != null);
                ConcatVector weightsBeforeStart = lenseWithRetraining.weights;
                lenseWithRetraining.playGame(game, batch);
                synchronized (games) {
                    GameRecord gr = new GameRecord(game, weightsBeforeStart);
                    games.add(gr);
                    try {
                        dumpGame(games.size(), gr);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                checkpoint(games);

                if (dumpWeights()) {
                    System.err.println("Dumping weights");
                    BufferedWriter bw = new BufferedWriter(new FileWriter(getThisRunPerformanceReportSubFolder() + "/weights.txt"));
                    try {
                        namespace.debugVector(lenseWithRetraining.weights, bw);
                    } catch (Exception e) {

                    }
                    bw.close();
                    System.err.println("Finished dumping weights");
                }

                // transfer annotations back to the original model

                for (int j = 0; j < model.getVariableSizes().length; j++) {
                    model.getVariableMetaDataByReference(j).putAll(game.model.getVariableMetaDataByReference(j));
                }

                synchronized (batch) {
                    if (!(humanSource instanceof ModelTagsHumanSource)) {
                        try {
                            batch.writeToFileWithoutFactors(getBatchFileLocation());
                            dumpModelBatch(batch);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }

        if (parallelBatchIgnoreRetraining()) {
            for (int i = 0; i < threads.length; i++)
                try {
                    threads[i].join();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
        }
        else {
            lenseWithRetraining.shutdown();
        }

        running[0] = false;
        synchronized (writeSystemBlock) {
            writeSystemBlock.notifyAll();
        }
    }

    public static class GameRecord {
        public Game game;
        public ConcatVector weights;
        public int[] result;

        public GameRecord(Game game, ConcatVector weights) {
            this.game = game;
            this.weights = weights;
            this.result = game.getMAP();
        }
    }
}
