package com.github.keenon.lense.gameplay.players;

import com.github.keenon.lense.gameplay.Game;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.function.Function;

/**
 * Created by keenon on 9/27/15.
 *
 * This provides an upper bound on gameplayer performance.
 *
 * Manages playing a game by searching the entire game tree. This doesn't work in real life, because in order to do this
 * we must make some dramatically simplifying (and hugely incorrect) assumptions about time. That being said, it does
 * make a nice gold-standard to compare toy gameplaying examples against during testing (for discrete games this will
 * yield the optimal answer every time).
 */
public class GamePlayerExhaustiveSearch extends GamePlayer {
    /**
     * An SLF4J Logger for this class.
     */
    private static final Logger log = LoggerFactory.getLogger(GamePlayerExhaustiveSearch.class);
    public int numNodes = 0;

    final static int NODE_SIZE_CAP = 1000000;

    @Override
    public Game.Event getNextMove(Game game, Function<Game, Double> utility) {
        TreeNode root = new TreeNode(game, null);

        Stack<TreeNode> visitedNodeOrder = new Stack<>();

        numNodes = 0;
        recursivelyEnumerate(root, visitedNodeOrder);
        if (numNodes >= NODE_SIZE_CAP) {
            return null;
        }

        while (!visitedNodeOrder.empty()) {
            TreeNode node = visitedNodeOrder.pop();

            // Return to the frozen stack from this frame

            while (!game.stack.empty()) {
                game.stack.peek().pop(game);
            }
            for (Game.Event e : node.stackFreeze) {
                e.push(node.game);
            }

            node.push();

            // Check for game termination

            if (node.event instanceof Game.TurnIn) {
                node.utilityAtNode = utility.apply(game);
                // log.info(node.utilityAtNode);
            }
            // If we waited, take a weighted sum of subsequent events
            else if (node.event instanceof Game.Wait) {
                double sumWeights = 0.0;
                double weightedSum = 0.0;

                double[][] marginals = node.game.getMarginals();
                for (TreeNode child : node.children) {
                    if (child.event instanceof Game.QueryResponse) {
                        Game.QueryResponse qr = (Game.QueryResponse)child.event;
                        double prob = marginals[qr.request.variable][qr.response];
                        sumWeights += prob;
                        weightedSum += prob*child.utilityAtNode;
                    }
                    else if (child.event instanceof Game.HumanArrival) {
                        sumWeights += 1.0;
                        weightedSum += child.utilityAtNode;
                    }
                    else if (child.event instanceof Game.QueryFailure) {
                        sumWeights += 1.0;
                        weightedSum += child.utilityAtNode;
                    }
                    else {
                        throw new IllegalStateException("Not the right switches: "+child.event.getClass());
                    }
                }

                node.utilityAtNode = weightedSum / sumWeights;
            }
            // Otherwise just take the best event, assuming we're intelligent
            else {
                node.utilityAtNode = Double.NEGATIVE_INFINITY;
                for (TreeNode child : node.children) {
                    if (child.utilityAtNode > node.utilityAtNode) {
                        node.utilityAtNode = child.utilityAtNode;
                    }
                }
            }
        }

        while (!game.stack.empty()) {
            game.stack.peek().pop(game);
        }
        for (Game.Event e : root.stackFreeze) {
            e.push(root.game);
        }

        // log.info("Util: "+root.utilityAtNode);

        TreeNode cursor = root;
        List<TreeNode> bestPath = new ArrayList<>();

        /*
        log.info("Brute force results:");
        for (TreeNode child : root.children) {
            log.info(child.event+": "+child.utilityAtNode);
        }
        */

        while (cursor.children.size() > 0) {
            TreeNode bestChild = null;
            for (TreeNode child : cursor.children) {
                if (bestChild == null || child.utilityAtNode > bestChild.utilityAtNode) {
                    bestChild = child;
                }
            }
            bestPath.add(bestChild);
            cursor = bestChild;
        }

        // Code for introspecting on alternate choices for the algorithm
        for (TreeNode step : bestPath) {
            // log.info(step.event.getClass()+": "+step.utilityAtNode);
            if (step.event instanceof Game.QueryLaunch) {
                log.info("Digging in on the things after query launch: ");
                for (TreeNode child : step.children) {
                    log.info("\t"+child.event.getClass()+": "+child.utilityAtNode);
                }
            }
        }

        return bestPath.get(0).event;
    }

    /**
     * This attempts to construct an exhaustive game tree, as long as that game tree is smaller than 10000 nodes, so
     * that the player can avoid exhaustive computations for large trees, which are hard to avoid accidentally generating.
     *
     * @param node the node on which to recursively construct children
     */
    private void recursivelyEnumerate(TreeNode node, Stack<TreeNode> visitedNodeOrder) {
        visitedNodeOrder.push(node);
        node.stackFreeze.addAll(node.game.stack);

        node.push();

        if (node.event != null) {
            assert (node.game.stack.size() == node.stackFreeze.size() + 1);
        }

        Game.Event[] events;
        if (node.game.isTerminated()) {
            events = new Game.Event[0];
        }
        else if (node.game.isGameplayerTurn()) {
            events = node.game.getLegalMoves();
        }
        else {
            events = node.game.sampleAllPossibleEventsAssumingDeterministicTime();
        }

        for (Game.Event e : events) {
            node.children.add(new TreeNode(node.game, e));
        }

        for (TreeNode child : node.children) {
            if (numNodes < NODE_SIZE_CAP) {
                numNodes++;
                recursivelyEnumerate(child, visitedNodeOrder);
            }
        }

        node.pop();
    }

    private static class TreeNode {
        Game game;
        Game.Event event;

        List<TreeNode> children = new ArrayList<>();

        List<Game.Event> stackFreeze = new ArrayList<>();

        double utilityAtNode = 0.0;

        public TreeNode(Game game, Game.Event event) {
            this.game = game;
            this.event = event;
        }

        public void push() {
            if (event == null) return;
            event.push(game);
        }

        public void pop() {
            if (event == null) return;
            event.pop(game);
        }
    }
}
