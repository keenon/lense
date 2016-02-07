package com.github.keenon.lense.human_source;

import com.github.keenon.loglinear.model.ConcatVector;
import com.github.keenon.loglinear.model.ConcatVectorNamespace;
import com.github.keenon.loglinear.model.ConcatVectorTable;
import com.github.keenon.loglinear.model.GraphicalModel;
import com.github.keenon.lense.gameplay.Game;
import com.github.keenon.lense.gameplay.distributions.ContinuousDistribution;
import com.github.keenon.lense.storage.ModelQueryRecord;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Created by keenon on 10/16/15.
 *
 * This implements a "Human Source" that actually just runs against tags on the model variables as a supplier of query
 * responses.
 */
public class ModelTagsHumanSource extends HumanSource {
    // This is used to count the number of jobs that we can give out on a single GraphicalModel
    public Map<GraphicalModel, AtomicInteger> jobsGivenOut = new IdentityHashMap<>();
    public ConcatVectorNamespace namespace;
    public ContinuousDistribution humanDelay;
    public Game.ArtificialHumanProvider artificialHumanProvider;

    static final double humanCorrectnessProb = 0.7;

    ConcatVector agreement = new ConcatVector(0);
    ConcatVector disagreement = new ConcatVector(0);

    /**
     * Takes a namespace that will be used in establishing human error matrices.
     *
     * @param namespace the namespace to use
     */
    public ModelTagsHumanSource(ConcatVectorNamespace namespace, ContinuousDistribution humanDelay) {
        this.namespace = namespace;
        this.humanDelay = humanDelay;

        namespace.setDenseFeature(agreement, "BIAS", new double[]{Math.log(humanCorrectnessProb)});
        // Give a uniform chance of selecting any of the other options
        namespace.setDenseFeature(disagreement, "BIAS", new double[]{Math.log((1-humanCorrectnessProb)/(4-1))});

        artificialHumanProvider = new Game.ArtificialHumanAgreementDisagrementProvider(agreement, disagreement, humanDelay);
    }

    /**
     * This is critical for simulation, as it's used by gameplayers to decide how informative an average human would be
     * during hiring decisions.
     *
     * @return a model of the humans we can hire
     */
    @Override
    public Game.ArtificialHumanProvider getSimulatedProvider() {
        return artificialHumanProvider;
    }

    @Override
    public void makeJobPosting(GraphicalModel model, Consumer<HumanHandle> jobAnsweredCallback) {
        if (!jobsGivenOut.containsKey(model)) {
            jobsGivenOut.put(model, new AtomicInteger());
        }
        int modelJobID = jobsGivenOut.get(model).getAndIncrement();
        ModelTagsHumanHandle handle = new ModelTagsHumanHandle(model, modelJobID, namespace, humanDelay);
        jobAnsweredCallback.accept(handle);
    }

    @Override
    public int getAvailableHumans(GraphicalModel model) {
        ModelQueryRecord qr = ModelQueryRecord.getQueryRecordFor(model);
        int max = Integer.MAX_VALUE;
        for (int i = 0; i < model.getVariableSizes().length; i++) {
            max = Math.min(max, qr.getResponses(i).size());
        }
        return max - jobsGivenOut.get(model).get();
    }

    @Override
    public void close() {
        // This does nothing, since we're not using network here
    }

    Map<int[], ConcatVectorTable[]> errorModelCache = new HashMap<>();
    Map<Integer, ConcatVectorTable> errorModelFactorCache = new HashMap<>();

    private class ModelTagsHumanHandle extends HumanHandle {
        int jobID;
        GraphicalModel model;
        ModelQueryRecord qr;
        ConcatVectorTable[] errorModel;
        ConcatVectorNamespace namespace;
        ContinuousDistribution delay;

        final Queue<Query> queries = new ArrayDeque<>();
        boolean running = true;

        public ModelTagsHumanHandle(GraphicalModel model, int jobID, ConcatVectorNamespace namespace, ContinuousDistribution delay) {
            this.model = model;
            this.jobID = jobID;
            qr = ModelQueryRecord.getQueryRecordFor(model);
            this.namespace = namespace;
            this.delay = delay;

            int[] sizes = model.getVariableSizes();

            if (!errorModelCache.containsKey(sizes)) {
                errorModel = new ConcatVectorTable[sizes.length];
                for (int i = 0; i < errorModel.length; i++) {
                    if (!errorModelFactorCache.containsKey(sizes[i])) {
                        errorModel[i] = new ConcatVectorTable(new int[]{sizes[i],sizes[i]});
                        for (int[] assn : errorModel[i]) {
                            if (assn[0] == assn[1]) errorModel[i].setAssignmentValue(assn, () -> agreement);
                            else errorModel[i].setAssignmentValue(assn, () -> disagreement);
                        }
                        errorModelFactorCache.put(sizes[i], errorModel[i]);
                    }
                    else errorModel[i] = errorModelFactorCache.get(sizes[i]);
                }
                errorModelCache.put(sizes, errorModel);
            }
            else errorModel = errorModelCache.get(sizes);

            new Thread(() -> {
                while (running) {
                    synchronized (queries) {
                        if (queries.isEmpty()) {
                            try {
                                queries.wait();
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                        if (!queries.isEmpty()) {
                            Query q = queries.poll();
                            q.run();
                        }
                    }
                }
            }).start();
        }

        @Override
        public void makeQuery(int variable, Consumer<Integer> response, Runnable failed) {
            synchronized (queries) {
                queries.add(new Query(variable, response, failed));
                queries.notifyAll();
            }
        }

        private class Query {
            int variable;
            Consumer<Integer> response;
            Runnable failed;

            public Query(int variable, Consumer<Integer> response, Runnable failed) {
                this.variable = variable;
                this.response = response;
                this.failed = failed;
            }

            public void run() {
                if (qr.getResponses(variable).size() > jobID) {
                    ModelQueryRecord.QueryRecord record = qr.getResponses(variable).get(jobID);
                    try {
                        // Replay at 100x speed, to keep things reasonable during replays
                        Thread.sleep(record.delay / 10);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    response.accept(record.response);
                }
                else {
                    failed.run();
                }
            }
        }

        @Override
        public ConcatVectorTable[] getErrorModel() {
            return errorModel;
        }

        @Override
        public ContinuousDistribution getDelayModel() {
            return delay;
        }

        @Override
        public void release() {
            // kill the running thread
            running = false;
            synchronized (queries) {
                queries.notifyAll();
            }
        }
    }
}
