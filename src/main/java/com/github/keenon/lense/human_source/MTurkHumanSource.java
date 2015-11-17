package com.github.keenon.lense.human_source;

import com.github.keenon.lense.human_server.HumanSourceClient;
import com.github.keenon.loglinear.model.ConcatVector;
import com.github.keenon.loglinear.model.ConcatVectorNamespace;
import com.github.keenon.loglinear.model.ConcatVectorTable;
import com.github.keenon.loglinear.model.GraphicalModel;
import com.github.keenon.lense.gameplay.Game;
import com.github.keenon.lense.gameplay.distributions.ContinuousDistribution;
import com.github.keenon.lense.human_server.mturk.MTurkClient;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Created by keenon on 10/16/15.
 *
 * This manages the connection to a central set of long lived human management systems by network APIs.
 */
public class MTurkHumanSource extends HumanSource {
    MTurkClient mturk;
    HumanSourceClient humans;
    Map<GraphicalModel, Integer> onlyOnceIDs = new IdentityHashMap<>();
    Thread shutdownHook;
    public ConcatVectorNamespace namespace;
    public ContinuousDistribution humanDelay;
    public Game.ArtificialHumanProvider artificialHumanProvider;

    static final double humanCorrectnessProb = 0.7;

    ConcatVector agreement = new ConcatVector(0);
    ConcatVector disagreement = new ConcatVector(0);

    public static final String QUERY_JSON = "io.hybridcrowd.humans.MTurkHumanSource.QUERY_JSON";

    /**
     * Creates a MTurkHumanSource that connects to the specified remote host
     *
     * @param host the remote host
     * @throws IOException if either MTurk or Human connection fails
     */
    public MTurkHumanSource(String host, ConcatVectorNamespace namespace, ContinuousDistribution humanDelay) throws IOException {
        humans = new HumanSourceClient(host, 2109);
        mturk = new MTurkClient(host, 2110);

        artificialHumanProvider = new Game.ArtificialHumanAgreementDisagrementProvider(agreement, disagreement, humanDelay);

        this.namespace = namespace;
        this.humanDelay = humanDelay;

        namespace.setDenseFeature(agreement, "BIAS", new double[]{Math.log(humanCorrectnessProb)});
        // Give a uniform chance of selecting any of the other options
        namespace.setDenseFeature(disagreement, "BIAS", new double[]{Math.log((1-humanCorrectnessProb)/(4-1))});

        shutdownHook = new Thread()
        {
            @Override
            public void run()
            {
                System.err.println("Closing connections...");
                humans.close();
                mturk.close();
            }
        };
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    /**
     * Closes the system
     */
    @Override
    public void close() {
        System.err.println("Closing connections...");
        humans.close();
        mturk.close();
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
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
        if (!onlyOnceIDs.containsKey(model)) {
            onlyOnceIDs.put(model, onlyOnceIDs.size());
        }
        MTurkHumanHandle humanHandle = new MTurkHumanHandle(model, humans, namespace, humanDelay);
        HumanSourceClient.JobHandle[] jobHandleRef = new HumanSourceClient.JobHandle[1];

        jobHandleRef[0] = humans.createJob(model.getModelMetaDataByReference().getOrDefault(QUERY_JSON, "{}"), onlyOnceIDs.get(model), () -> {
            // Job accepted
            humanHandle.jobHandle = jobHandleRef[0];
            jobAnsweredCallback.accept(humanHandle);
        }, humanHandle::disconnect);
    }

    /**
     * Models a single human doing a single job on MTurk.
     */
    public static class MTurkHumanHandle extends HumanHandle {
        public HumanSourceClient humans;
        public HumanSourceClient.JobHandle jobHandle = null; // This must get set before the handle is returned
        ConcatVectorNamespace namespace;
        ContinuousDistribution delayDistribution;
        GraphicalModel model;
        int[] sizes;

        public MTurkHumanHandle(GraphicalModel model, HumanSourceClient humans, ConcatVectorNamespace namespace, ContinuousDistribution delayDistribution) {
            this.model = model;
            this.humans = humans;
            this.namespace = namespace;
            this.delayDistribution = delayDistribution;
            sizes = model.getVariableSizes();
        }

        @Override
        public void makeQuery(int variable, Consumer<Integer> response, Runnable failed) {
            jobHandle.launchQuery(model.getVariableMetaDataByReference(variable).get(QUERY_JSON), response, failed);
        }

        @Override
        public ConcatVectorTable[] getErrorModel() {
            ConcatVectorTable[] errorModel = new ConcatVectorTable[Math.max(sizes.length, model.variableMetaData.size())];
            for (int i = 0; i < errorModel.length; i++) {
                if (i < sizes.length && sizes[i] > -1) {
                    errorModel[i] = new ConcatVectorTable(new int[]{
                            sizes[i], sizes[i]
                    });
                    for (int[] assn : errorModel[i]) {
                        errorModel[i].setAssignmentValue(assn, () -> new ConcatVector(0));
                    }
                }
            }
            return errorModel;
        }

        @Override
        public ContinuousDistribution getDelayModel() {
            return delayDistribution;
        }

        @Override
        public void release() {
            jobHandle.closeJob();
        }
    }
}