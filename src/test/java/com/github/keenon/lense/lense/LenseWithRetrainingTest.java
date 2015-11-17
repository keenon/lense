package com.github.keenon.lense.lense;

import com.github.keenon.lense.gameplay.GameTest;
import com.github.keenon.lense.gameplay.players.GamePlayerRandom;
import com.github.keenon.lense.gameplay.utilities.UncertaintyUtility;
import com.github.keenon.loglinear.model.ConcatVectorNamespace;

import static org.junit.Assert.*;

import com.github.keenon.loglinear.model.ConcatVector;
import com.github.keenon.loglinear.model.GraphicalModel;
import com.pholser.junit.quickcheck.ForAll;
import com.pholser.junit.quickcheck.From;
import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import org.junit.contrib.theories.Theories;
import org.junit.contrib.theories.Theory;
import org.junit.runner.RunWith;

import java.util.Random;

/**
 * Created by keenon on 10/7/15.
 *
 * Attempts to test Lense with retraining, which is basically all about testing that the threading is working properly
 * and as intended.
 */
@RunWith(Theories.class)
public class LenseWithRetrainingTest {
    Random r = new Random();

    @Theory
    public void testWeightRetraining(@ForAll(sampleSize = 1) @From(GraphicalModelSetGenerator.class) GraphicalModel[] dataset,
                                     @ForAll(sampleSize = 2) @From(GameTest.WeightsGenerator.class) ConcatVector weights) throws Exception {
        ConcatVectorNamespace namespace = new ConcatVectorNamespace();

        LenseWithRetraining l = new LenseWithRetraining(new LenseTest.DelayHumanSource(), new GamePlayerRandom(r), new UncertaintyUtility(), weights, namespace);
        ConcatVector oldWeights = l.weights;
        assert(oldWeights != null);
        for (int i = 0; i < dataset.length; i++) {
            for (GraphicalModel.Factor f : dataset[i].factors) {
                for (int[] assn : f.featuresTable) {
                    assert(f.featuresTable.getAssignmentValue(assn) != null);
                }
            }
            l.getMAP(dataset[i], null);
            if (i > 4) {
                System.err.println("Waiting 0.5s for retraining");
                Thread.sleep(500);
                System.err.println("Checking for new weights...");
                assertTrue(oldWeights != l.weights);
                oldWeights = l.weights;
            }
            else {
                System.err.println("Too few examples, don't expect retraining yet.");
                assertTrue(oldWeights == l.weights);
            }
        }
        l.shutdown();
        Thread.sleep(2000);
        assertTrue(l.finished);
    }

    public static class GraphicalModelSetGenerator extends Generator<GraphicalModel[]> {
        GameTest.GraphicalModelGenerator generator = new GameTest.GraphicalModelGenerator(GraphicalModel.class);

        public GraphicalModelSetGenerator(Class<GraphicalModel[]> type) {
            super(type);
        }

        @Override
        public GraphicalModel[] generate(SourceOfRandomness sourceOfRandomness, GenerationStatus generationStatus) {
            int datasetSize = sourceOfRandomness.nextInt(1,40);
            GraphicalModel[] dataset = new GraphicalModel[datasetSize];
            for (int i = 0; i < dataset.length; i++) {
                dataset[i] = generator.generate(sourceOfRandomness, generationStatus);
            }
            return dataset;
        }
    }
}