package com.github.keenon.lense.lense;

import com.github.keenon.lense.gameplay.Game;
import com.github.keenon.lense.gameplay.GameTest;
import com.github.keenon.lense.gameplay.distributions.ContinuousDistribution;
import com.github.keenon.lense.gameplay.players.GamePlayerRandom;
import com.github.keenon.lense.gameplay.utilities.UncertaintyUtility;
import com.github.keenon.lense.storage.ModelQueryRecord;
import com.github.keenon.loglinear.model.ConcatVector;
import com.github.keenon.loglinear.model.ConcatVectorTable;
import com.github.keenon.loglinear.model.GraphicalModel;
import com.pholser.junit.quickcheck.ForAll;
import com.pholser.junit.quickcheck.From;
import com.github.keenon.lense.gameplay.distributions.DiscreteSetDistribution;
import com.github.keenon.lense.human_source.HumanHandle;
import com.github.keenon.lense.human_source.HumanSource;
import org.junit.contrib.theories.Theories;
import org.junit.contrib.theories.Theory;
import org.junit.runner.RunWith;

import java.util.*;
import java.util.function.Consumer;

import static org.junit.Assert.*;

/**
 * Created by keenon on 10/7/15.
 *
 * This tests that Lense actually does work correctly in terms of handling asynchrony correctly.
 */
@RunWith(Theories.class)
public class LenseTest {
    Random r = new Random();

    @Theory
    public void testLense(@ForAll(sampleSize = 10) @From(GameTest.GraphicalModelGenerator.class) GraphicalModel model,
                          @ForAll(sampleSize = 2) @From(GameTest.WeightsGenerator.class) ConcatVector weights) throws Exception {
        DelayHumanSource humanSource = new DelayHumanSource();

        Lense l = new Lense(humanSource, new GamePlayerRandom(r), new UncertaintyUtility(), weights);

        // Check that the system actually returns and doesn't deadlock on some strange edge case

        Game g = new Game(model, weights, l.humans.getSimulatedProvider(), l.humans.getAvailableHumans(model));
        g.humansAvailableServerSide = 1;

        // Clear the previous record, so that we can check our results against previous recorded results

        for (int i = 0; i < model.getVariableSizes().length; i++) {
            ModelQueryRecord.getQueryRecordFor(model).getResponses(i).clear();
        }

        l.playGame(g, null);

        assert(!g.stack.isEmpty());
        assert(g.stack.peek() instanceof Game.TurnIn);

        List<Game.Event> stack = new ArrayList<>();
        stack.addAll(g.stack);

        g.resetEvents();

        // Check that the sequence of moves we recorded was considered legal by the game player

        for (Game.Event e : stack) {
            if (g.isGameplayerTurn()) {
                if (e instanceof Game.QueryLaunch) {
                    Game.QueryLaunch ql = (Game.QueryLaunch)e;
                    Game.Event[] events = g.getLegalMoves();

                    boolean containsEquivalent = false;
                    for (Game.Event e2 : events) {
                        if (e2 instanceof Game.QueryLaunch) {
                            Game.QueryLaunch ql2 = (Game.QueryLaunch)e2;
                            if (ql.variable == ql2.variable && ql.human == ql2.human) {
                                containsEquivalent = true;
                                break;
                            }
                        }
                    }

                    assertTrue(containsEquivalent);
                }
            }
            else {
                Game.Event[] events = g.sampleAllPossibleEventsAssumingDeterministicTime();
            }

            e.push(g);
        }

        humanSource.close();

        // Make sure all the handles shut down
        Thread.sleep(300);

        // Double check that we are in fact recording the responses of our simulated humans

        Map<Integer,List<DelayHumanHandle.QueryStub>> usedStubs = new HashMap<>();
        for (DelayHumanHandle handle : humanSource.handles) {
            for (DelayHumanHandle.QueryStub stub : handle.usedStubs) {
                if (!usedStubs.containsKey(stub.variable)) {
                    usedStubs.put(stub.variable, new ArrayList<>());
                }
                usedStubs.get(stub.variable).add(stub);
            }
        }

        for (int i : usedStubs.keySet()) {
            // Sort each sub-list
            usedStubs.get(i).sort((o1, o2) -> (int)(o1.returnTime - o2.returnTime));

            // Check that the order of the recorded responses on the model matches our stubs
            List<ModelQueryRecord.QueryRecord> queries = ModelQueryRecord.getQueryRecordFor(model).getResponses(i);

            assertEquals(usedStubs.get(i).size(), queries.size());

            for (int j = 0; j < queries.size(); j++) {
                assertEquals(usedStubs.get(i).get(j).returnValue, queries.get(j).response);
                assertTrue(Math.abs(usedStubs.get(i).get(j).delay - queries.get(j).delay) < 50);
            }
        }

    }

    public static class DelayHumanSource extends HumanSource {
        final Random r = new Random(42);

        List<DelayHumanHandle> handles = new ArrayList<>();
        ConcatVector agreeWeights = new ConcatVector(1);
        ConcatVector disagreeWeights = new ConcatVector(1);

        @Override
        public Game.ArtificialHumanProvider getSimulatedProvider() {
            agreeWeights = new ConcatVector(1);
            agreeWeights.setSparseComponent(0,0,0.5);

            disagreeWeights = new ConcatVector(1);
            disagreeWeights.setSparseComponent(0, 0, -0.5);

            ContinuousDistribution delay = new DiscreteSetDistribution(new long[]{1L});

            return new Game.ArtificialHumanAgreementDisagrementProvider(agreeWeights, disagreeWeights, delay);
        }

        @Override
        public void makeJobPosting(GraphicalModel model, Consumer<HumanHandle> jobAnsweredCallback) {
            new Thread(() -> {
                try {
                    Thread.sleep(r.nextInt(50));
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                DelayHumanHandle humanHandle = new DelayHumanHandle(model, agreeWeights, disagreeWeights);
                handles.add(humanHandle);
                jobAnsweredCallback.accept(humanHandle);
            }).start();
        }

        @Override
        public int getAvailableHumans(GraphicalModel model) {
            return 1;
        }

        @Override
        public void close() {
            for (DelayHumanHandle handle : handles) handle.release();
        }
    }

    public static class DelayHumanHandle extends HumanHandle {
        final Random r = new Random(42);
        GraphicalModel model;
        int[] sizes;

        boolean running = true;
        final Queue<QueryStub> stubs = new ArrayDeque<>();
        final List<QueryStub> usedStubs = new ArrayList<>();

        ConcatVectorTable[] errorModel;

        public DelayHumanHandle(GraphicalModel model, ConcatVector agreeWeights, ConcatVector disagreeWeights) {
            this.model = model;
            sizes = model.getVariableSizes();

            errorModel = new ConcatVectorTable[sizes.length];
            for (int i = 0; i < errorModel.length; i++) {
                if (sizes[i] > 0) {
                    errorModel[i] = new ConcatVectorTable(new int[]{
                            sizes[i], sizes[i]
                    });
                    for (int[] assn : errorModel[i]) {
                        if (assn[0] == assn[1]) errorModel[i].setAssignmentValue(assn, () -> agreeWeights);
                        else errorModel[i].setAssignmentValue(assn, () -> disagreeWeights);
                    }
                }
            }

            new Thread(() -> {
                while (running) {
                    synchronized (stubs) {
                        if (stubs.isEmpty()) try {
                            stubs.wait();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }

                        QueryStub stub = stubs.poll();
                        if (stub == null) continue;

                        stub.delay = r.nextInt(200);
                        stub.returnValue = r.nextInt(sizes[stub.variable]);
                        try {
                            Thread.sleep(stub.delay);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }

                        if (!running) break;

                        stub.returnTime = System.currentTimeMillis();
                        usedStubs.add(stub);
                        stub.response.accept(stub.returnValue);
                    }
                }
            }).start();
        }

        public static class QueryStub {
            int variable;
            Consumer<Integer> response;

            long delay;

            long returnTime;
            int returnValue;

            public QueryStub(int variable, Consumer<Integer> response) {
                this.variable = variable;
                this.response = response;
            }
        }

        @Override
        public void makeQuery(int variable, Consumer<Integer> response, Runnable failed) {
            stubs.add(new QueryStub(variable, response));
            synchronized (stubs) {
                stubs.notifyAll();
            }
        }

        @Override
        public ConcatVectorTable[] getErrorModel() {
            return errorModel;
        }

        @Override
        public ContinuousDistribution getDelayModel() {
            return new DiscreteSetDistribution(new long[]{1L});
        }

        @Override
        public void release() {
            running = false;
            synchronized (stubs) {
                stubs.notifyAll();
            }
        }
    }
}