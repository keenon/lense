package com.github.keenon.lense.gameplay.players;

import com.github.keenon.lense.gameplay.Game;

import java.util.Random;
import java.util.function.Function;

/**
 * Created by keenon on 9/30/15.
 *
 * Creates a fully simulated environment, that can take the interface from a GamePlayer as given and run entire
 * simulations end-to-end. This is useful for randomized testing between GamePlayer approaches, and for writing toy
 * cases that can then be analyzed for writing papers.
 *
 * This is *not* the real world, and draws outcomes directly from the distribution that the gameplayer believes outcomes
 * are drawn from. As a general rule, that means that optimal search will result in truly optimal play in expectation,
 * which is not the case in real life.
 */
public class GamePlayerTestBed {

    public static class GameRecord {
        public double endUtility = 0.0;
        public Game game;

        public GameRecord(Game game) {
            this.game = game;
        }
    }

    public static GameRecord playSimulation(Random r, Game game, GamePlayer player, Function<Game, Double> utility) {
        GameRecord record = new GameRecord(game);

        while (game.stack.empty() || !(game.stack.peek() instanceof Game.TurnIn)) {
            // This means it's the gameplayer's turn
            if (game.isGameplayerTurn()) {
                Game.Event move = player.getNextMove(game, utility);
                assert(move != null);
                move.push(game);
            }
            // This means it's the turn of the environment
            else {
                Game.Event randomEvent = game.sampleNextEvent(r);
                assert(randomEvent != null);
                randomEvent.push(game);
            }
        }

        record.endUtility = utility.apply(game);
        return record;
    }

}
