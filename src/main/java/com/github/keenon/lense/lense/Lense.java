package com.github.keenon.lense.lense;

import com.github.keenon.lense.gameplay.Game;
import com.github.keenon.lense.gameplay.players.GamePlayerMCTS;
import com.github.keenon.lense.gameplay.utilities.UncertaintyUtilityWithoutTime;
import com.github.keenon.lense.storage.ModelQueryRecord;
import com.github.keenon.loglinear.model.ConcatVector;
import com.github.keenon.loglinear.model.GraphicalModel;
import com.github.keenon.lense.gameplay.players.GamePlayer;
import com.github.keenon.lense.human_source.HumanHandle;
import com.github.keenon.lense.human_source.HumanSource;
import com.github.keenon.lense.human_source.ModelTagsHumanSource;

import java.util.*;
import java.util.function.Function;

/**
 * Created by keenon on 10/5/15.
 *
 * Manages the interactions between the game, the humans, and learning in the background.
 * In point of fact, these interactions are not very complex, and actually might not need their own class to handle
 * them.
 *
 * Mostly, we just need to relay commands from the gameplayer to the humans.
 */
public class Lense {
    HumanSource humans;
    GamePlayer gamePlayer;
    Function<Game, Double> utility;

    public ConcatVector weights;

    boolean recordQueries;

    public Lense(HumanSource humans) {
        this(humans, new GamePlayerMCTS(), new UncertaintyUtilityWithoutTime(), new ConcatVector(0));
    }

    public Lense(HumanSource humans, GamePlayer gamePlayer, Function<Game, Double> utility, ConcatVector weights) {
        this.humans = humans;
        this.gamePlayer = gamePlayer;
        this.utility = utility;
        this.weights = weights;
        recordQueries = !(humans instanceof ModelTagsHumanSource);
    }

    /**
     * To the outside world, LENSE is just a better version of CliqueTree, and will attempt to get MAP estimates for
     * models by using humans to deal with ambiguity.
     *
     * @param model the model to run LENSE on
     * @return a best guess for the true labels of the model
     */
    public int[] getMAP(GraphicalModel model, Object moveMonitor) {
        Game game = new Game(model, weights, humans.getSimulatedProvider());
        return playGame(game, moveMonitor).getMAP();
    }

    /**
     * If for diagnosis or debugging or other reasons you want to be able to introspect into the game after it was
     * played, you should use this call, so you can get the whole game entity back from LENSE.
     *
     * @param game the game to be played out
     * @param moveMonitor synchronize moves based on this object
     * @return the game object after gameplay is complete
     */
    public Game playGame(Game game, Object moveMonitor) {
        long gameStart = System.currentTimeMillis();
        long computationTime = 0;

        final Queue<Game.Event> externalEvents = new ArrayDeque<>();
        Map<Game.HumanArrival, HumanHandle> humanHandles = new IdentityHashMap<>();
        // This is used for recording
        Map<Game.HumanArrival, Long> humanLastActivityTimestamp = new IdentityHashMap<>();

        while (true) {
            synchronized (externalEvents) {
                while (!externalEvents.isEmpty()) {
                    Game.Event e = externalEvents.poll();
                    if (moveMonitor != null) {
                        synchronized (moveMonitor) {
                            e.timeSinceGameStart = System.currentTimeMillis() - gameStart;
                            e.push(game);
                        }
                    }
                    else {
                        e.timeSinceGameStart = System.currentTimeMillis() - gameStart;
                        e.push(game);
                    }
                }
            }

            long moveComputeStart = System.currentTimeMillis();
            Game.Event event = gamePlayer.getNextMove(game, utility);
            long moveComputeTime = System.currentTimeMillis() - moveComputeStart;
            computationTime += moveComputeTime;

            if (humans instanceof ModelTagsHumanSource) {
                // We subtract out 9/10 of the compute time here because when we're using 10x acceleration on simulate human
                // response times, and we report game time, we're actually multiplying the delay of the game computation by
                // a factor of 10. That can make a 40ms compute look like 400ms, which starts to really skew results.
                event.timeSinceGameStart = System.currentTimeMillis() - (long)Math.ceil(computationTime*0.9) - gameStart;
            }
            else event.timeSinceGameStart = System.currentTimeMillis() - gameStart;
            event.push(game);

            // Turn in finishes the game

            if (event instanceof Game.TurnIn) {

                // Release any remaining humans when we terminate the game

                for (Game.HumanArrival arrival : game.availableHumans) {
                    humanHandles.get(arrival).release();
                }
                break;
            }

            // Wait pauses until external events wake the game up

            else if (event instanceof Game.Wait) {
                System.err.println("Wait");
                try {
                    synchronized (externalEvents) {
                        while (externalEvents.isEmpty()) {
                            externalEvents.wait();
                        }
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            // Query launches issue a query to a human handle, and wait for a response

            else if (event instanceof Game.QueryLaunch) {
                Game.QueryLaunch ql = (Game.QueryLaunch)event;

                boolean[] responseReceived = new boolean[]{false};

                System.err.println("Query launched on "+ql.variable);

                humanHandles.get(ql.human).makeQuery(ql.variable, (response) -> {
                    if (responseReceived[0]) {
                        System.err.println("Getting human response twice! (query success)");
                        System.err.println("TOKEN: "+game.model.getVariableMetaDataByReference(ql.variable).get("TOKEN"));
                        return;
                    }
                    responseReceived[0] = true;

                    System.err.println("Response received on "+ql.variable+"="+response);

                    if (recordQueries) {
                        long delay = System.currentTimeMillis() - humanLastActivityTimestamp.get(ql.human);
                        humanLastActivityTimestamp.put(ql.human, System.currentTimeMillis());

                        ModelQueryRecord.getQueryRecordFor(game.model).recordResponse(ql.variable, response, delay);
                    }

                    Game.QueryResponse qr = new Game.QueryResponse(ql, response);
                    synchronized (externalEvents) {
                        externalEvents.add(qr);
                        externalEvents.notifyAll();
                    }
                }, () -> {
                    if (responseReceived[0]) {
                        System.err.println("Getting human response twice! (query failed)");
                        System.err.println("TOKEN: "+game.model.getVariableMetaDataByReference(ql.variable).get("TOKEN"));
                        return;
                    }
                    responseReceived[0] = true;

                    // This is a failure
                    Game.QueryFailure qf = new Game.QueryFailure(ql);
                    synchronized (externalEvents) {
                        externalEvents.add(qf);
                        externalEvents.notifyAll();
                    }
                });
            }

            // Job postings

            else if (event instanceof Game.HumanJobPosting) {
                System.err.println("Make Job Posting");

                boolean[] responseReceived = new boolean[]{false};

                humans.makeJobPosting(game.model, (humanHandle) -> {
                    if (responseReceived[0]) {
                        System.err.println("Getting human arrival twice!");
                        return;
                    }
                    responseReceived[0] = true;

                    Game.HumanArrival humanArrival = new Game.HumanArrival(
                            humanHandle.getErrorModel(),
                            humanHandle.getDelayModel(),
                            (Game.HumanJobPosting)event,
                            new HashMap<>());

                    System.err.println("Human arrived");

                    boolean[] disconnectReceived = new boolean[]{false};

                    // Setup the callback for if the human experiences an error or closes their browser in the middle of
                    // a game.
                    humanHandle.setDisconnectedCallback(() -> {
                        if (disconnectReceived[0]) {
                            System.err.println("Disconnect received twice!");
                            return;
                        }
                        disconnectReceived[0] = true;

                        System.err.println("Human disconnected");

                        // Insert a human exit
                        Game.HumanExit he = new Game.HumanExit(humanArrival);

                        synchronized (externalEvents) {
                            externalEvents.add(he);
                            // Cancel all in flight queries to this person, if any
                            for (Game.QueryLaunch ql : game.inFlightRequests) {
                                if (ql.human == humanArrival) {
                                    Game.QueryFailure qf = new Game.QueryFailure(ql);
                                    externalEvents.add(qf);
                                }
                            }
                            externalEvents.notifyAll();
                        }
                    });

                    humanHandles.put(humanArrival, humanHandle);
                    humanLastActivityTimestamp.put(humanArrival, System.currentTimeMillis());

                    synchronized (externalEvents) {
                        externalEvents.add(humanArrival);
                        externalEvents.notifyAll();
                    }
                });
            }

            else if (event instanceof Game.HumanRelease) {
                Game.HumanRelease hr = (Game.HumanRelease)event;
                humanHandles.get(hr.human).release();
            }

            else {
                throw new IllegalStateException("Unrecognized move: "+event);
            }
        }

        return game;
    }
}
