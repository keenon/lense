package com.github.keenon.lense.gameplay.players;

import com.github.keenon.lense.gameplay.GameTest;
import com.pholser.junit.quickcheck.ForAll;
import com.pholser.junit.quickcheck.From;
import com.github.keenon.lense.gameplay.Game;
import com.github.keenon.lense.gameplay.utilities.UncertaintyUtility;
import org.junit.contrib.theories.Theories;
import org.junit.contrib.theories.Theory;
import org.junit.runner.RunWith;

import java.util.Random;

import static org.junit.Assert.*;

/**
 * Created by keenon on 9/27/15.
 *
 * This runs exhaustive search, and runs a random move selection gameplayer, and asserts that after 1000 runs exhaustive
 * search is always doing better of the same as a random player, never worse. This should be true almost all of the time
 * assuming that exhaustive search is implemented correctly.
 */
@RunWith(Theories.class)
public class GamePlayerExhaustiveSearchTest {
    @Theory
    public void testQueries(@ForAll(sampleSize = 3) @From(GameTest.GameGenerator.class) Game game) throws Exception {
        Random r = new Random(23);
        game.maxAllowedJobPostings = 2;

        GamePlayerExhaustiveSearch bruteForce = new GamePlayerExhaustiveSearch();

        System.err.println("Model: "+game.model.toString());

        // This means that the game tree is too large, so we quit
        Game.Event firstMove = bruteForce.getNextMove(game, new UncertaintyUtility());
        if (firstMove == null || firstMove instanceof Game.TurnIn) {
            System.err.println("Skip... "+firstMove);
            return;
        }

        GamePlayerRandom random = new GamePlayerRandom(r);

        double bruteForceUtil = 0;
        double randomUtil = 0;

        for (int i = 0; i < 10; i++) {
            game.resetEvents();
            bruteForceUtil += GamePlayerTestBed.playSimulation(r, game, bruteForce, new UncertaintyUtility()).endUtility;
            game.resetEvents();
            randomUtil += GamePlayerTestBed.playSimulation(r, game, random, new UncertaintyUtility()).endUtility;

            System.err.println("Brute force: " + bruteForceUtil);
            System.err.println("random: "+randomUtil);
        }

        // Over 10 runs, brute force will almost always do better than random, to the point where failure probability
        // is near 0
        assertTrue(bruteForceUtil >= randomUtil);
    }
}