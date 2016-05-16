package com.github.keenon.lense.gameplay.players;

import com.github.keenon.loglinear.model.ConcatVector;
import com.github.keenon.loglinear.model.GraphicalModel;
import com.github.keenon.lense.gameplay.Game;
import com.github.keenon.lense.gameplay.distributions.DiscreteSetDistribution;
import com.github.keenon.lense.gameplay.utilities.UncertaintyUtility;
import com.github.keenon.lense.gameplay.utilities.UncertaintyUtilityWithoutTime;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Created by keenon on 10/2/15.
 *
 * This runs a number of toy examples, then debugs the output so that we can have pretty printed examples for our paper.
 */
public class ToyExamples {
    public static void main(String[] args) {
        double humanErrorRate = 0.2;

        GraphicalModel m = new GraphicalModel();

        m.addFactor(new int[]{0,1}, new int[]{2,2}, (assn) -> {
            if (assn[0] == 0 && assn[1] == 0) {
                return vec(1.7);
            }
            else {
                return vec(1.0);
            }
        });

        // Run tests

        Map<Integer,ConcatVector> disagreementVectors = new HashMap<>();
        for (int i = 2; i < 30; i++) {
            disagreementVectors.put(i, vec(Math.log(humanErrorRate)));
        }

        Random r;
        Game g = new Game(m, vec(1.0), new Game.ArtificialHumanAgreementDisagrementProvider(vec(Math.log(1.0 - humanErrorRate)), disagreementVectors, new DiscreteSetDistribution(new long[]{2000L})), 2);

        g.resetEvents();
        r = new Random(42);
        System.out.println("WITH time penalty");
        debugResults(GamePlayerTestBed.playSimulation(r, g, new GamePlayerExhaustiveSearch(), new UncertaintyUtility()));

        g.resetEvents();
        r = new Random(42);
        System.out.println("WITHOUT time penalty");
        debugResults(GamePlayerTestBed.playSimulation(r, g, new GamePlayerExhaustiveSearch(), new UncertaintyUtilityWithoutTime()));
    }

    private static ConcatVector vec(double v) {
        ConcatVector vec = new ConcatVector(1);
        vec.setDenseComponent(0, new double[]{v});
        return vec;
    }

    private static void debugResults(GamePlayerTestBed.GameRecord record) {
        System.out.println("********");
        System.out.println("Final utility: " + record.endUtility);
        for (Game.Event event : record.game.stack) {
            System.out.println(event.timeSinceGameStart+"ms: "+event);
        }
        System.out.println("********");
    }
}
