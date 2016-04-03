package com.github.keenon.lense.human_source;

import com.github.keenon.lense.human_server.HumanSourceClient;
import com.github.keenon.loglinear.model.ConcatVector;
import com.github.keenon.loglinear.model.ConcatVectorNamespace;
import com.github.keenon.loglinear.model.ConcatVectorTable;
import com.github.keenon.loglinear.model.GraphicalModel;
import com.github.keenon.lense.gameplay.Game;
import com.github.keenon.lense.gameplay.distributions.ContinuousDistribution;
import com.github.keenon.lense.human_server.mturk.MTurkClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    /**
     * An SLF4J Logger for this class.
     */
    private static final Logger log = LoggerFactory.getLogger(MTurkHumanSource.class);

    MTurkClient mturk;
    HumanSourceClient humans;
    Map<GraphicalModel, Integer> onlyOnceIDs = new IdentityHashMap<>();
    Thread shutdownHook;
    public ConcatVectorNamespace namespace;
    public ContinuousDistribution humanDelay;
    public Game.ArtificialHumanProvider artificialHumanProvider;

    public double humanCorrectnessProb = 0.7;

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
        log.info("Connected to human GUI at http://" + host + "/ or at http://" + host + ":8080/");
        try {
            mturk = new MTurkClient(host, 2110);
        }
        catch (Exception e) {
            log.info("Failed to connect to MTurkClient, hiring Turkers programatically will be disabled.");
        }

        this.namespace = namespace;
        this.humanDelay = humanDelay;

        namespace.setDenseFeature(agreement, "BIAS", new double[]{Math.log(humanCorrectnessProb)});
        // Give a uniform chance of selecting any of the other options
        namespace.setDenseFeature(disagreement, "BIAS", new double[]{Math.log((1-humanCorrectnessProb)/(4-1))});

        artificialHumanProvider = new Game.ArtificialHumanAgreementDisagrementProvider(agreement, disagreement, humanDelay);

        shutdownHook = new Thread()
        {
            @Override
            public void run()
            {
                log.info("Closing connections...");
                humans.close();
                if (mturk != null) mturk.close();
            }
        };
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    /**
     * Closes the system
     */
    @Override
    public void close() {
        log.info("Closing connections...");
        humans.close();
        if (mturk != null) mturk.close();
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
        MTurkHumanHandle humanHandle = new MTurkHumanHandle(model, humans, namespace, humanDelay, agreement, disagreement);
        HumanSourceClient.JobHandle[] jobHandleRef = new HumanSourceClient.JobHandle[1];

        jobHandleRef[0] = humans.createJob(model.getModelMetaDataByReference().getOrDefault(QUERY_JSON, "{}"), onlyOnceIDs.get(model), () -> {
            // Job accepted
            humanHandle.jobHandle = jobHandleRef[0];
            jobAnsweredCallback.accept(humanHandle);
        }, humanHandle::disconnect);
    }

    @Override
    public int getAvailableHumans(GraphicalModel model) {
        if (!onlyOnceIDs.containsKey(model)) {
            onlyOnceIDs.put(model, onlyOnceIDs.size());
        }
        return humans.getGetNumberOfWorkersBlocking(onlyOnceIDs.get(model));
    }

    /**
     * Models a single human doing a single job on MTurk.
     */
    public static class MTurkHumanHandle extends HumanHandle {
        public HumanSourceClient humans;
        public HumanSourceClient.JobHandle jobHandle = null; // This must get set before the handle is returned
        ConcatVectorNamespace namespace;
        ContinuousDistribution delayDistribution;
        ConcatVector agreement;
        ConcatVector disagreement;
        GraphicalModel model;
        int[] sizes;

        public MTurkHumanHandle(GraphicalModel model,
                                HumanSourceClient humans,
                                ConcatVectorNamespace namespace,
                                ContinuousDistribution delayDistribution,
                                ConcatVector agreement,
                                ConcatVector disagreement) {
            this.model = model;
            this.humans = humans;
            this.namespace = namespace;
            this.delayDistribution = delayDistribution;
            this.agreement = agreement;
            this.disagreement = disagreement;
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
                        errorModel[i].setAssignmentValue(assn, assn[0] == assn[1] ? () -> agreement : () -> disagreement);
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
