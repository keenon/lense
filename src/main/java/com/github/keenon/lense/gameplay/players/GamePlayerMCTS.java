package com.github.keenon.lense.gameplay.players;

import com.github.keenon.lense.gameplay.Game;
import org.omg.PortableServer.ThreadPolicy;
import sun.nio.ch.ThreadPool;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * Created by keenon on 9/27/15.
 *
 * A sampling based gameplayer that can handle time as a part of the game.
 */
public class GamePlayerMCTS extends GamePlayer {
    double explorationConstant = 0.25;
    boolean multithreaded = true;
    ThreadPoolExecutor executor = null;

    public GamePlayerMCTS() {
        executor = (ThreadPoolExecutor)Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    }

    public GamePlayerMCTS(ThreadPoolExecutor executor) {
        this.executor = executor;
    }

    @Override
    public Game.Event getNextMove(Game game, Function<Game, Double> utility) {
        Random r = new Random(42);

        assert(game.isGameplayerTurn());

        // No need to waste computation here
        Game.Event[] legalMoves = game.getLegalMoves();
        if (legalMoves.length == 1) return legalMoves[0];
        // This isn't part of single-game playing, so just assume all global resources available
        // This interferes with the test against the exhaustive game player, so we need to be careful
        if (!assertsEnabled()) {
            // If we can, make a job posting
            for (Game.Event e : legalMoves) {
                if (e instanceof Game.HumanJobPosting) {
                    return new Game.HumanJobPosting();
                }
            }
        }

        GameTreeNode root = new GameTreeNode(game, null);

        if (multithreaded) {
            // Use the number of inactive threads, but no fewer than 1 thread
            int numThreads = Math.max(1, executor.getPoolSize() - executor.getActiveCount());

            Future<Void>[] threads = (Future<Void>[])new Future[numThreads];
            Game[] gameClones = game.getClones(threads.length);

            for (int i = 0; i < threads.length; i++) {
                int iFinal = i;
                Callable<Void> runnable = () -> {
                    for (int j = 0; j < Math.max(5,legalMoves.length * 2.0 / threads.length); j++) {
                        playOut(root, r, gameClones[iFinal], utility);
                    }
                    return null;
                };
                threads[i] = executor.submit(runnable);
            }
            for (int i = 0; i < threads.length; i++) {
                try {
                    threads[i].get();
                } catch (Exception e) {
                    System.err.println("Had exception while running child");
                    e.printStackTrace();
                }
            }
        }
        else {
            for (int i = 0; i < legalMoves.length * 1.5; i++) {
                playOut(root, r, game, utility);
            }
        }

        System.err.println("MCTS results:");
        for (GameTreeNode node : root.children) {
            double avgUtil = node.observedUtility / node.timesVisited;
            double uct = (node.observedUtility / node.timesVisited) + explorationConstant*Math.sqrt(Math.log(root.timesVisited)/node.timesVisited);
            System.err.println("\t"+node.originalEvent+": "+node.timesVisited+", avg util: "+avgUtil+", UCT: "+uct);
        }

        GameTreeNode choiceNode = root.maxChoiceBy((node) -> (node.observedUtility / node.timesVisited));
        assert(choiceNode != null);
        choiceNode.ensureEventFor(game);
        return choiceNode.gameEventMap.get(game);
    }

    public void playOut(GameTreeNode head, Random r, Game game, Function<Game, Double> utility) {
        Stack<GameTreeNode> visited = new Stack<>();

        // Run one time through a game

        GameTreeNode cursor = head;
        while (true) {
            visited.add(cursor);
            cursor.push(game);
            if (game.isTerminated()) break;
            if (game.isGameplayerTurn()) {
                cursor = pickOrCreateGameplayerChoice(cursor, game, r);
            }
            else {
                cursor = pickOrCreateEnvironmentEvent(cursor, game, r);
            }
        }

        double observedUtility = utility.apply(game);

        // Backprop through the visited set

        while (!visited.empty()) {
            GameTreeNode node = visited.pop();
            node.pop(game);
            node.observeUtility(observedUtility);
        }
    }

    /**
     * This uses UCT to pick an exploration candidate.
     */
    public GameTreeNode pickOrCreateGameplayerChoice(GameTreeNode node, Game game, Random r) {
        Game.Event[] choices = game.getLegalMoves();

        synchronized (node) {

            // UCT requires that we visit everything once before branching out

            for (Game.Event e : choices) {
                boolean containsEquivalent = false;
                for (GameTreeNode child : node.children) {
                    if (child.originalEvent.equals(e)) {
                        containsEquivalent = true;
                        break;
                    }
                }
                if (!containsEquivalent) {
                    GameTreeNode next = new GameTreeNode(game, e);
                    node.children.add(next);
                    return next;
                }
            }

            // If we've already visited everything, we need to be clever about exploitation vs exploration

            GameTreeNode choice = node.maxChoiceBy((child) -> (child.observedUtility / child.timesVisited) + explorationConstant * Math.sqrt(Math.log(node.timesVisited) / child.timesVisited));
            assert(choice != null);
            return choice;
        }
    }

    /**
     * This uses progressive widening and biased random selection to pick a child of the environment.
     */
    public GameTreeNode pickOrCreateEnvironmentEvent(GameTreeNode node, Game game, Random r) {
        synchronized (node) {
            int progressiveWidening = (int) Math.max(Math.ceil(Math.sqrt(node.timesVisited)), 1);

            if ((game.isNextSampleEventDeterministic() && node.children.size() == 0) ||
                    (!game.isNextSampleEventDeterministic() && node.children.size() < progressiveWidening)) {
                Game.Event e = game.sampleNextEvent(r);
                GameTreeNode next = new GameTreeNode(game, e);
                node.children.add(next);
                return next;
            } else if (node.children.size() == 1) {
                return node.children.iterator().next();
            } else {

                // Choose event according to its probability under our model relative to the others,
                // currently by ignoring time.

                double[][] currentMarginals = node.getMarginals(game);

                double totalScore = 0.0;

                for (GameTreeNode child : node.children) {
                    assert (child.originalEvent instanceof Game.QueryResponse);
                    Game.QueryResponse qr = (Game.QueryResponse) child.originalEvent;
                    totalScore += currentMarginals[qr.request.variable][qr.response];
                }

                double randomSelection = r.nextDouble() * totalScore;
                for (GameTreeNode child : node.children) {
                    Game.QueryResponse qr = (Game.QueryResponse) child.originalEvent;
                    double score = currentMarginals[qr.request.variable][qr.response];
                    randomSelection -= score;
                    if (randomSelection <= 0) return child;
                }

                throw new IllegalStateException("Should never reach this point");
            }
        }
    }

    public static class GameTreeNode {
        Game.Event originalEvent = null;
        Map<Game, Game.Event> gameEventMap = new IdentityHashMap<>();
        int reconstructionPointer = 0;

        List<GameTreeNode> children = new ArrayList<>();

        double observedUtility = 0.0;
        int timesVisited = 0;

        double[][] marginalsCache = null;

        public GameTreeNode(Game game, Game.Event e) {
            originalEvent = e;
            if (e != null) {
                gameEventMap.put(game, e);
                if (e instanceof Game.HumanArrival) {
                    Game.HumanArrival ha = (Game.HumanArrival)e;
                    reconstructionPointer = referenceEqualityStackIndexOf(game, ha.respondingTo);
                }
                else if (e instanceof Game.QueryLaunch) {
                    Game.QueryLaunch ql = (Game.QueryLaunch)e;
                    reconstructionPointer = referenceEqualityStackIndexOf(game, ql.human);
                }
                else if (e instanceof Game.QueryResponse) {
                    Game.QueryResponse ql = (Game.QueryResponse)e;
                    reconstructionPointer = referenceEqualityStackIndexOf(game, ql.request);
                }
                else if (e instanceof Game.QueryFailure) {
                    Game.QueryFailure qf = (Game.QueryFailure)e;
                    reconstructionPointer = referenceEqualityStackIndexOf(game, qf.request);
                }
            }
        }

        private int referenceEqualityStackIndexOf(Game game, Game.Event e) {
            for (int i = 0; i < game.stack.size(); i++) {
                if (game.stack.get(i) == e) return i;
            }
            throw new IllegalStateException("Should never call this function if e isn't on the stack of game");
        }

        public void observeUtility(double utility) {
            observedUtility += utility;
            timesVisited ++;
        }

        public double[][] getMarginals(Game game) {
            if (marginalsCache == null) {
                marginalsCache = game.getMarginals();
            }
            return marginalsCache;
        }

        public GameTreeNode maxChoiceBy(Function<GameTreeNode, Double> scoring) {
            double bestValue = Double.NEGATIVE_INFINITY;
            GameTreeNode bestChoice = null;

            for (GameTreeNode child : children) {
                double score = scoring.apply(child);
                if (score > bestValue || bestChoice == null) {
                    bestValue = score;
                    bestChoice = child;
                }
            }

            return bestChoice;
        }

        /**
         * In order to be thread-safe, each game needs its own actions, with its own pointers, to apply to its own games.
         * @param game the game object for this thread
         */
        public void ensureEventFor(Game game) {
            if (!gameEventMap.containsKey(game)) {
                if (originalEvent instanceof Game.HumanArrival) {
                    Game.HumanArrival ha = (Game.HumanArrival)originalEvent;
                    Game.HumanArrival clone = new Game.HumanArrival(ha.humanErrorModel,
                            ha.delayModel,
                            (Game.HumanJobPosting)game.stack.get(reconstructionPointer),
                            ha.metaData);
                    clone.timeSinceGameStart = originalEvent.timeSinceGameStart;
                    gameEventMap.put(game, clone);
                }
                else if (originalEvent instanceof Game.QueryLaunch) {
                    Game.QueryLaunch ql = (Game.QueryLaunch)originalEvent;
                    Game.QueryLaunch clone = new Game.QueryLaunch(ql.variable, (Game.HumanArrival)game.stack.get(reconstructionPointer));
                    clone.timeSinceGameStart = originalEvent.timeSinceGameStart;
                    gameEventMap.put(game, clone);
                }
                else if (originalEvent instanceof Game.QueryResponse) {
                    Game.QueryResponse qr = (Game.QueryResponse)originalEvent;
                    Game.QueryResponse clone = new Game.QueryResponse((Game.QueryLaunch)game.stack.get(reconstructionPointer), qr.response);
                    clone.timeSinceGameStart = originalEvent.timeSinceGameStart;
                    gameEventMap.put(game, clone);
                }
                else if (originalEvent instanceof Game.QueryFailure) {
                    Game.QueryFailure qf = (Game.QueryFailure)originalEvent;
                    Game.QueryFailure clone = new Game.QueryFailure((Game.QueryLaunch)game.stack.get(reconstructionPointer));
                    clone.timeSinceGameStart = originalEvent.timeSinceGameStart;
                    gameEventMap.put(game, clone);
                }
                else {
                    // The non-pointer event types shouldn't matter if they get reused by multiple threads
                    gameEventMap.put(game, originalEvent);
                }
            }
        }

        public void push(Game game) {
            if (originalEvent == null) return;
            ensureEventFor(game);
            gameEventMap.get(game).push(game);
        }

        public void pop(Game game) {
            if (originalEvent == null) return;
            gameEventMap.get(game).pop(game);
        }
    }

    public static boolean assertsEnabled() {
        boolean enabled = false;
        // This is an intentional side-effect
        assert(enabled = true);
        return enabled;
    }
}
