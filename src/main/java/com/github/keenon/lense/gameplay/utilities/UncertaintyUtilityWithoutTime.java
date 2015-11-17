package com.github.keenon.lense.gameplay.utilities;

import com.github.keenon.lense.gameplay.Game;

import java.util.function.Function;

/**
 * Created by keenon on 9/27/15.
 *
 * This is a utility function for a gameplayer, that ignores the time required to get to a solution, and simply returns
 * 'uncertainty' (1 - max class prob) summed over each variable in the marginals.
 */
public class UncertaintyUtilityWithoutTime implements Function<Game, Double> {
    public static double humanRecruitmentCost = 0.0;
    public static double humanQueryCost = 0.01;

    @Override
    public Double apply(Game game) {
        double[][] marginals = game.getMarginals();
        double uncertaintySum = 0.0;
        for (double[] dist : marginals) {
            if (dist == null) continue;
            double max = 0.0;
            for (double d : dist) max = Math.max(max, d);
            uncertaintySum += 1.0 - max;
        }
        double cost = uncertaintySum;

        for (Game.Event e : game.stack) {
            if (e instanceof Game.HumanJobPosting) {
                cost += humanRecruitmentCost;
            }
            else if (e instanceof Game.QueryLaunch) {
                cost += humanQueryCost;
            }
        }

        return -cost;
    }
}
