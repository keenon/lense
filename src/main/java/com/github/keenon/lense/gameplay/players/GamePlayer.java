package com.github.keenon.lense.gameplay.players;

import com.github.keenon.lense.gameplay.Game;

import java.util.function.Function;

/**
 * Created by keenon on 9/25/15.
 *
 * This is an abstract superclass for all methods for GamePlaying. We can unify testing across different methods this
 * way, and also provide a simple interface for real-life stuff.
 */
public abstract class GamePlayer {
    public abstract Game.Event getNextMove(Game game, Function<Game, Double> utility);
}
