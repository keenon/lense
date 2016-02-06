package com.github.keenon.lense.gameplay.players;

import com.github.keenon.lense.gameplay.Game;

import java.util.function.Function;

/**
 * Created by keenon on 10/3/15.
 *
 * Manages a simple approximation to more complex methods with the use of thresholds and simple discounting for
 * in-flight queries. Is much much faster than search based methods, although is unable to exploit binary factors.
 */
public class GamePlayerThreshold extends GamePlayer {
    public double humanUncertaintyMultiple = 0.3;
    public double queryThreshold = 0.005;

    @Override
    public Game.Event getNextMove(Game game, Function<Game, Double> utility) {
        Game.Event[] legalMoves = game.getLegalMoves();

        double[][] marginals = game.getMarginals();

        double[] uncertainty = new double[marginals.length];
        double worstUncertainty = 0.0;

        for (int i = 0; i < marginals.length; i++) {
            if (marginals[i] == null) continue;

            double max = 0;
            for (double d : marginals[i]) if (d > max) max = d;

            double u = 1.0 - max;

            for (Game.QueryLaunch ql : game.inFlightRequests) {
                if (ql.variable == i) {
                    u *= humanUncertaintyMultiple;
                }
            }

            uncertainty[i] = u;
            if (u > worstUncertainty) worstUncertainty = u;
        }

        int needJobPostings = 0;
        assert(humanUncertaintyMultiple > 0);
        assert(humanUncertaintyMultiple < 1);
        while (worstUncertainty > queryThreshold) {
            worstUncertainty *= humanUncertaintyMultiple;
            needJobPostings ++;
        }
        for (Game.Event e : game.stack) {
            if (e instanceof Game.HumanJobPosting) needJobPostings--;
            if (e instanceof Game.HumanExit) needJobPostings++;
        }

        if (needJobPostings > 0) {
            return new Game.HumanJobPosting();
        }

        for (Game.Event e : legalMoves) {
            if (e instanceof Game.QueryLaunch) {
                Game.QueryLaunch ql = (Game.QueryLaunch)e;
                if (uncertainty[ql.variable] > queryThreshold) {
                    return ql;
                }
            }
        }
        if (game.inFlightRequests.size() > 0 || game.jobPostings.size() > 0) {
            return new Game.Wait();
        }
        else {
            return new Game.TurnIn();
        }
    }
}
