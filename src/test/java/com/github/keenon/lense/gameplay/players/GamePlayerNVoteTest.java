package com.github.keenon.lense.gameplay.players;

import com.github.keenon.lense.gameplay.Game;
import com.github.keenon.lense.gameplay.GameTest;
import com.github.keenon.lense.gameplay.distributions.ContinuousDistribution;
import com.github.keenon.lense.lense.Lense;
import com.github.keenon.loglinear.model.ConcatVector;
import com.github.keenon.loglinear.model.ConcatVectorTable;
import com.github.keenon.loglinear.model.GraphicalModel;
import com.pholser.junit.quickcheck.ForAll;
import com.pholser.junit.quickcheck.From;
import com.pholser.junit.quickcheck.generator.InRange;
import com.github.keenon.lense.human_source.HumanHandle;
import com.github.keenon.lense.human_source.HumanSource;
import org.junit.contrib.theories.Theories;
import org.junit.contrib.theories.Theory;
import org.junit.runner.RunWith;

import java.util.*;
import java.util.function.Consumer;

import static org.junit.Assert.*;

/**
 * Created by keenon on 10/17/15.
 *
 * This makes an effort to test that the N-vote gameplayer always ends up in games with exactly N votes on each
 * variable.
 */
@RunWith(Theories.class)
public class GamePlayerNVoteTest {
    @Theory
    public void testNVote(@ForAll(sampleSize = 2) @From(GameTest.GraphicalModelGenerator.class) GraphicalModel model,
                          @ForAll(sampleSize = 1) @InRange(minInt = 1, maxInt = 5) int n) throws Exception {
        GamePlayer nVoter = new GamePlayerNVote(n, false);
        Lense lense = new Lense(new BasicHumanSource(), nVoter, (game) -> 0.0, new ConcatVector(0));
        Game game = new Game(model, new ConcatVector(0), null);
        lense.playGame(game, null);

        Map<Integer,Integer> responseCount = new HashMap<>();
        for (Game.Event e : game.stack) {
            if (e instanceof Game.QueryResponse) {
                Game.QueryResponse qr = (Game.QueryResponse)e;
                responseCount.put(qr.request.variable, responseCount.getOrDefault(qr.request.variable,0) + 1);
            }
        }

        for (int i : responseCount.keySet()) {
            assertTrue(i < game.variableSizes.length);
            assertEquals(n, (int)responseCount.get(i));
        }

        System.err.println("*****");
    }

    public static class BasicHumanSource extends HumanSource {
        Random r = new Random();

        @Override
        public Game.ArtificialHumanProvider getSimulatedProvider() {
            return null;
        }

        @Override
        public void makeJobPosting(GraphicalModel model, Consumer<HumanHandle> jobAnsweredCallback) {
            new Thread(() -> {
                try {
                    Thread.sleep(r.nextInt(300));
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                jobAnsweredCallback.accept(new BasicHumanHandle(model));
            }).start();
        }

        @Override
        public int getAvailableHumans(GraphicalModel model) {
            return 1;
        }

        @Override
        public void close() {
            // do nothing
        }

        public static class BasicHumanHandle extends HumanHandle {
            Random r = new Random();
            GraphicalModel model;
            int[] variableSizes;

            boolean running = true;
            final Queue<Consumer<Integer>> responseQueue = new ArrayDeque<>();
            final Queue<Runnable> failureQueue = new ArrayDeque<>();

            public BasicHumanHandle(GraphicalModel model) {
                this.model = model;
                this.variableSizes = model.getVariableSizes();

                new Thread(() -> {
                    while (running) {
                        Consumer<Integer> response;
                        Runnable failure;
                        synchronized (responseQueue) {
                            while (responseQueue.isEmpty()) {
                                try {
                                    responseQueue.wait();
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                            }

                            response = responseQueue.poll();
                            failure = failureQueue.poll();
                        }

                        try {
                            Thread.sleep(r.nextInt(100));
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }

                        if (r.nextBoolean()) {
                            response.accept(0);
                        }
                        else {
                            failure.run();
                        }
                    }
                }).start();
            }

            @Override
            public void makeQuery(int variable, Consumer<Integer> response, Runnable failed) {
                synchronized (responseQueue) {
                    responseQueue.add(response);
                    failureQueue.add(failed);
                    responseQueue.notifyAll();
                }
            }

            @Override
            public ConcatVectorTable[] getErrorModel() {
                ConcatVectorTable[] errors = new ConcatVectorTable[variableSizes.length];
                for (int i = 0; i < errors.length; i++) {
                    int size = variableSizes[i];
                    if (size > 0) {
                        errors[i] = new ConcatVectorTable(new int[]{size, size});
                        for (int[] assn : errors[i]) {
                            errors[i].setAssignmentValue(assn, () -> new ConcatVector(0));
                        }
                    }
                }
                return errors;
            }

            @Override
            public ContinuousDistribution getDelayModel() {
                return null;
            }

            @Override
            public void release() {
                // do nothing
            }
        }
    }
}