package com.github.keenon.lense.gameplay.players;

import com.github.keenon.lense.gameplay.Game;

import java.util.Random;
import java.util.function.Function;

/**
 * Created by keenon on 9/30/15.
 *
 * This provides a lower bound on the performance of an intelligent gameplaying algorithm, the same way that exhaustive
 * search provides an upper bound on performance.
 *
 * Picks its next move completely at random.
 */
public class GamePlayerRandom extends GamePlayer {
    Random r;

    public GamePlayerRandom(Random r) {
        this.r = r;
    }

    @Override
    public Game.Event getNextMove(Game game, Function<Game, Double> utility) {
        Game.Event[] possible = game.getLegalMoves();
        int choice = r.nextInt(possible.length);
        return possible[choice];
    }
}
