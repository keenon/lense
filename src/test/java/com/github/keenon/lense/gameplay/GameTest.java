package com.github.keenon.lense.gameplay;

import com.github.keenon.loglinear.inference.CliqueTree;
import com.github.keenon.loglinear.model.ConcatVector;
import com.github.keenon.loglinear.model.ConcatVectorTable;
import com.github.keenon.loglinear.model.GraphicalModel;
import com.pholser.junit.quickcheck.ForAll;
import com.pholser.junit.quickcheck.From;
import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.generator.InRange;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import com.github.keenon.lense.gameplay.distributions.ContinuousDistribution;
import com.github.keenon.lense.gameplay.distributions.DiscreteSetDistribution;
import com.github.keenon.lense.gameplay.players.GamePlayer;
import com.github.keenon.lense.gameplay.players.GamePlayerRandom;
import org.junit.contrib.theories.Theories;
import org.junit.contrib.theories.Theory;
import org.junit.runner.RunWith;

import java.util.*;

import static org.junit.Assert.*;

/**
 * Created by keenon on 9/23/15.
 *
 * Testing all the different functions of Game. This means primarily testing that the stack conforms to expected behavior,
 * that serialization works properly, and that the marginal calculation conforms to expected behavior.
 */
@RunWith(Theories.class)
public class GameTest {
    @Theory
    public void testGetClones(@ForAll(sampleSize = 10) @From(GameGenerator.class) Game game) throws Exception {
        Random r = new Random(42);
        GamePlayer gp = new GamePlayerRandom(r);

        while (!game.isTerminated()) {
            Game[] copies = game.getClones(5);
            for (int i = 0; i < copies.length; i++) {
                assertTrue(copies[i] != game);
                assertTrue(copies[i].stack.size() == game.stack.size());
                for (int j = 0; j < copies[i].stack.size(); j++) {
                    assertTrue(copies[i].stack.get(j) != game.stack.get(j));
                    if (!copies[i].stack.get(j).equals(game.stack.get(j))) {
                        Game.Event e1 = copies[i].stack.get(j);
                        Game.Event e2 = game.stack.get(j);
                        boolean eq = e1.equals(e2);
                        System.err.println("blah");
                    }
                    assertTrue(copies[i].stack.get(j).equals(game.stack.get(j)));
                }
                assertTrue(copies[i].model != game.model);
                assertTrue(copies[i].model.valueEquals(game.model, 1.0e-7));
            }

            Game.Event nextMove;
            if (game.isGameplayerTurn()) {
                nextMove = gp.getNextMove(game, null);
            }
            else {
                nextMove = game.sampleNextEvent(r);
            }
            nextMove.push(game);
        }
    }

    @Theory
    public void testMultiquery(@ForAll(sampleSize = 10) @From(GameGenerator.class) Game game,
                               @ForAll(sampleSize = 5) @InRange(minInt = 1, maxInt = 10) int numQueries) throws Exception {
        int initialNumVariable = numVariables(game.model);

        List<Integer> vars = new ArrayList<>();
        vars.addAll(game.availableAnnotators.keySet());

        Random r = new Random();

        for (int i = 0; i < numQueries; i++) {
            //Recruit another human
            Game.HumanJobPosting jp = new Game.HumanJobPosting();
            jp.push(game);
            Game.HumanArrival human = game.humanProvider.getArtificialHuman(game, jp);
            human.push(game);

            // Do the query
            int variable = vars.get(r.nextInt(vars.size()));
            Game.QueryLaunch ql = new Game.QueryLaunch(variable, human);
            ql.push(game);
            Game.QueryResponse qr = new Game.QueryResponse(ql, 0);
            qr.push(game);

            assertEquals(initialNumVariable + i + 1, numVariables(game.model));
        }

        for (int i = 0; i < numQueries; i++) {
            // Pop the answer
            game.stack.peek().pop(game);
            // request
            game.stack.peek().pop(game);
            // humanArrival
            game.stack.peek().pop(game);
            // job posting
            game.stack.peek().pop(game);

            assertEquals(initialNumVariable + numQueries - i - 1, numVariables(game.model));
        }
    }

    private int numVariables(GraphicalModel model) {
        int maxVar = model.variableMetaData.size();
        for (GraphicalModel.Factor f : model.factors) {
            for (int i : f.neigborIndices) if (i > maxVar) maxVar = i;
        }
        return maxVar;
    }

    @Theory
    public void testIsFinished(@ForAll(sampleSize = 10) @From(GameGenerator.class) Game game) throws Exception {
        Game.TurnIn t = new Game.TurnIn();
        t.push(game);
        assertTrue(game.isTerminated());
        t.pop(game);
        assertFalse(game.isTerminated());
    }

    @Theory
    public void testIsGameplayerTurn(@ForAll(sampleSize = 10) @From(GameGenerator.class) Game game) throws Exception {
        Game.Wait w = new Game.Wait();
        assertTrue(game.isGameplayerTurn());
        w.push(game);
        assertFalse(game.isGameplayerTurn());
        w.pop(game);
        assertTrue(game.isGameplayerTurn());
    }

    @Theory
    public void testLegalMoves(@ForAll(sampleSize = 10) @From(GameGenerator.class) Game game) throws Exception {
        Game.Event[] moves = game.getLegalMoves();

        boolean containsHire = false;
        boolean containsWait = false;
        boolean containsObserve = false;
        boolean containsTurnIn = false;

        for (Game.Event e : moves) {
            if (e instanceof Game.HumanJobPosting) containsHire = true;
            if (e instanceof Game.Wait) containsWait = true;
            if (e instanceof Game.QueryLaunch) containsObserve = true;
            if (e instanceof Game.TurnIn) containsTurnIn = true;
        }

        assertTrue(containsHire);
        assertFalse(containsWait);
        assertFalse(containsObserve);
        assertTrue(containsTurnIn);
    }

    @Theory
    public void testQueries(@ForAll(sampleSize = 100) @From(GameGenerator.class) Game game) throws Exception {
        Game.HumanJobPosting job = new Game.HumanJobPosting();
        job.push(game);

        Game.HumanArrival human = game.humanProvider.getArtificialHuman(game, job);
        human.push(game);

        assertEquals(1, game.availableHumans.size());
        assertTrue(human == game.availableHumans.iterator().next());

        Random r = new Random();

        for (int i = 0; i < game.variableSizes.length; i++) {
            if (game.variableSizes[i] != -1) {
                assertEquals(1, game.availableAnnotators.get(i).size());

                Game.QueryLaunch q = new Game.QueryLaunch(i, human);
                q.push(game);

                assertEquals(0, game.availableAnnotators.get(i).size());

                q.pop(game);

                assertEquals(1, game.availableAnnotators.get(i).size());

                q.push(game);

                assertEquals(0, game.availableAnnotators.get(i).size());

                if (r.nextBoolean()) {
                    q.pop(game);
                }
            }
        }

        int numQueries = 0;
        List<Game.QueryLaunch> queries = new ArrayList<>();
        for (Game.Event e : game.stack) {
            if (e instanceof Game.QueryLaunch) {
                numQueries++;
                assertTrue(game.inFlightRequests.contains(e));
                queries.add((Game.QueryLaunch)e);
            }
        }

        assertEquals(numQueries, game.inFlightRequests.size());

        for (Game.QueryLaunch q : queries) {
            // Just to check this doesn't throw an error
            double[][] marginals = game.getMarginals();
            if (r.nextBoolean()) {
                new Game.QueryFailure(q).push(game);
                double[][] newMarginals = game.getMarginals();

                // Check that nothing has changed

                double difference = 0.0;

                for (int i = 0; i < marginals.length; i++) {
                    if (marginals[i] == null) continue;
                    for (int j = 0; j < marginals[i].length; j++) {
                        difference += Math.abs(marginals[i][j] - newMarginals[i][j]);
                    }
                }

                assertTrue(difference == 0.0);
            }
            else {
                int responseValue = r.nextInt(game.variableSizes[q.variable]);
                new Game.QueryResponse(q, responseValue).push(game);
                double[][] newMarginals = game.getMarginals();

                double difference = 0.0;

                for (int i = 0; i < marginals.length; i++) {
                    if (marginals[i] == null) continue;
                    for (int j = 0; j < marginals[i].length; j++) {
                        difference += Math.abs(marginals[i][j] - newMarginals[i][j]);
                    }
                }

                // Check that something has changed

                if (game.variableSizes[q.variable] > 1) {
                    assertTrue(difference >= 0.0);
                }
                // Check that nothing has changed
                else {
                    assertTrue(difference <= 1.0e-7);
                }
            }
        }

        assertEquals(0, game.inFlightRequests.size());

        // Just to check this doesn't throw an error
        game.getMarginals();
    }

    public static class GameGenerator extends Generator<Game> {
        GraphicalModelGenerator modelGenerator = new GraphicalModelGenerator(GraphicalModel.class);
        WeightsGenerator weightsGenerator = new WeightsGenerator(ConcatVector.class);

        public GameGenerator(Class<Game> type) {
            super(type);
        }

        @Override
        public Game generate(SourceOfRandomness sourceOfRandomness, GenerationStatus generationStatus) {
            ConcatVector weights = weightsGenerator.generate(sourceOfRandomness, generationStatus);

            ConcatVector agreementVector = weightsGenerator.generate(sourceOfRandomness, generationStatus);
            ConcatVector disagreementVector = weightsGenerator.generate(sourceOfRandomness, generationStatus);

            ContinuousDistribution humanDelay = new DiscreteSetDistribution(new long[]{ 2000L }); // assume each query deterministically takes 2 seconds

            Game.ArtificialHumanProvider humanSampler = new Game.ArtificialHumanAgreementDisagrementProvider(agreementVector, disagreementVector, humanDelay);
            return new Game(modelGenerator.generate(sourceOfRandomness, generationStatus), weights, humanSampler, 2);
        }
    }

    public static final int CONCAT_VEC_COMPONENTS = 2;
    public static final int CONCAT_VEC_COMPONENT_LENGTH = 3;
    public static final int MAX_NUM_VARIABLES = 3;
    public static final int MAX_VARIABLE_SIZE = 2;

    public static class WeightsGenerator extends Generator<ConcatVector> {
        public WeightsGenerator(Class<ConcatVector> type) {
            super(type);
        }

        @Override
        public ConcatVector generate(SourceOfRandomness sourceOfRandomness, GenerationStatus generationStatus) {
            ConcatVector v = new ConcatVector(CONCAT_VEC_COMPONENTS);
            for (int x = 0; x < CONCAT_VEC_COMPONENTS; x++) {
                if (sourceOfRandomness.nextBoolean()) {
                    v.setSparseComponent(x, sourceOfRandomness.nextInt(CONCAT_VEC_COMPONENT_LENGTH), sourceOfRandomness.nextDouble());
                }
                else {
                    double[] val = new double[sourceOfRandomness.nextInt(CONCAT_VEC_COMPONENT_LENGTH)];
                    for (int y = 0; y < val.length; y++) {
                        val[y] = sourceOfRandomness.nextDouble();
                    }
                    v.setDenseComponent(x, val);
                }
            }
            return v;
        }
    }

    public static class GraphicalModelGenerator extends Generator<GraphicalModel> {
        public GraphicalModelGenerator(Class<GraphicalModel> type) {
            super(type);
        }

        private Map<String,String> generateMetaData(SourceOfRandomness sourceOfRandomness, Map<String,String> metaData) {
            int numPairs = sourceOfRandomness.nextInt(9);
            for (int i = 0; i < numPairs; i++) {
                int key = sourceOfRandomness.nextInt();
                int value = sourceOfRandomness.nextInt();
                metaData.put("key:"+key, "value:"+value);
            }
            return metaData;
        }

        @Override
        public GraphicalModel generate(SourceOfRandomness sourceOfRandomness, GenerationStatus generationStatus) {
            GraphicalModel model = new GraphicalModel();

            // Create the variables and factors. These are deliberately tiny so that the brute force approach is tractable

            int[] variableSizes = new int[sourceOfRandomness.nextInt(1, MAX_NUM_VARIABLES)];
            for (int i = 0; i < variableSizes.length; i++) {
                variableSizes[i] = sourceOfRandomness.nextInt(1,MAX_VARIABLE_SIZE);
            }

            // Traverse in a randomized BFS to ensure the generated graph is a tree

            if (sourceOfRandomness.nextBoolean()) {
                generateCliques(variableSizes, new ArrayList<>(), new HashSet<>(), model, sourceOfRandomness);
            }

            // Or generate a linear chain CRF, because our random BFS doesn't generate these very often, and they're very
            // common in practice, so worth testing densely

            else {
                for (int i = 0; i < variableSizes.length; i++) {

                    // Add unary factor

                    GraphicalModel.Factor unary = model.addFactor(new int[]{i}, new int[]{variableSizes[i]}, (assignment) -> {
                        ConcatVector features = new ConcatVector(CONCAT_VEC_COMPONENTS);
                        for (int j = 0; j < CONCAT_VEC_COMPONENTS; j++) {
                            if (sourceOfRandomness.nextBoolean()) {
                                features.setSparseComponent(j, sourceOfRandomness.nextInt(CONCAT_VEC_COMPONENT_LENGTH), sourceOfRandomness.nextDouble());
                            } else {
                                double[] dense = new double[sourceOfRandomness.nextInt(CONCAT_VEC_COMPONENT_LENGTH)];
                                for (int k = 0; k < dense.length; k++) {
                                    dense[k] = sourceOfRandomness.nextDouble();
                                }
                                features.setDenseComponent(j, dense);
                            }
                        }
                        return features;
                    });

                    // "Cook" the randomly generated feature vector thunks, so they don't change as we run the system

                    for (int[] assignment : unary.featuresTable) {
                        ConcatVector randomlyGenerated = unary.featuresTable.getAssignmentValue(assignment).get();
                        unary.featuresTable.setAssignmentValue(assignment, () -> randomlyGenerated);
                    }

                    // Add binary factor

                    if (i < variableSizes.length-1) {
                        GraphicalModel.Factor binary = model.addFactor(new int[]{i, i + 1}, new int[]{variableSizes[i], variableSizes[i + 1]}, (assignment) -> {
                            ConcatVector features = new ConcatVector(CONCAT_VEC_COMPONENTS);
                            for (int j = 0; j < CONCAT_VEC_COMPONENTS; j++) {
                                if (sourceOfRandomness.nextBoolean()) {
                                    features.setSparseComponent(j, sourceOfRandomness.nextInt(CONCAT_VEC_COMPONENT_LENGTH), sourceOfRandomness.nextDouble());
                                } else {
                                    double[] dense = new double[sourceOfRandomness.nextInt(CONCAT_VEC_COMPONENT_LENGTH)];
                                    for (int k = 0; k < dense.length; k++) {
                                        dense[k] = sourceOfRandomness.nextDouble();
                                    }
                                    features.setDenseComponent(j, dense);
                                }
                            }
                            return features;
                        });

                        // "Cook" the randomly generated feature vector thunks, so they don't change as we run the system

                        for (int[] assignment : binary.featuresTable) {
                            ConcatVector randomlyGenerated = binary.featuresTable.getAssignmentValue(assignment).get();
                            binary.featuresTable.setAssignmentValue(assignment, () -> randomlyGenerated);
                        }
                    }
                }
            }

            // Add metadata to the variables, factors, and model

            generateMetaData(sourceOfRandomness, model.getModelMetaDataByReference());
            for (int i = 0; i < variableSizes.length; i++) {
                generateMetaData(sourceOfRandomness, model.getVariableMetaDataByReference(i));
            }
            for (GraphicalModel.Factor factor : model.factors) {
                generateMetaData(sourceOfRandomness, factor.getMetaDataByReference());
            }

            // Observe a few of the variables

            for (GraphicalModel.Factor f : model.factors) {
                for (int i = 0; i < f.neigborIndices.length; i++) {
                    if (sourceOfRandomness.nextDouble() > 0.8) {
                        int obs = sourceOfRandomness.nextInt(f.featuresTable.getDimensions()[i]);
                        model.getVariableMetaDataByReference(f.neigborIndices[i]).put(CliqueTree.VARIABLE_OBSERVED_VALUE, "" + obs);
                    }
                }
            }

            return model;
        }

        private void generateCliques(int[] variableSizes,
                                     List<Integer> startSet,
                                     Set<Integer> alreadyRepresented,
                                     GraphicalModel model,
                                     SourceOfRandomness randomness) {
            if (alreadyRepresented.size() == variableSizes.length) return;

            // Generate the clique variable set

            List<Integer> cliqueContents = new ArrayList<>();
            cliqueContents.addAll(startSet);
            alreadyRepresented.addAll(startSet);
            while (true) {
                if (alreadyRepresented.size() == variableSizes.length) break;
                if (cliqueContents.size() == 0 || randomness.nextDouble(0,1) < 0.7) {
                    int gen;
                    do {
                        gen = randomness.nextInt(variableSizes.length);
                    } while (alreadyRepresented.contains(gen));
                    alreadyRepresented.add(gen);
                    cliqueContents.add(gen);
                }
                else break;
            }

            // Create the actual table

            int[] neighbors = new int[cliqueContents.size()];
            int[] neighborSizes = new int[neighbors.length];
            for (int j = 0; j < neighbors.length; j++) {
                neighbors[j] = cliqueContents.get(j);
                neighborSizes[j] = variableSizes[neighbors[j]];
            }
            ConcatVectorTable table = new ConcatVectorTable(neighborSizes);
            for (int[] assignment : table) {
                // Generate a vector
                ConcatVector v = new ConcatVector(CONCAT_VEC_COMPONENTS);
                for (int x = 0; x < CONCAT_VEC_COMPONENTS; x++) {
                    if (randomness.nextBoolean()) {
                        v.setSparseComponent(x, randomness.nextInt(32), randomness.nextDouble());
                    }
                    else {
                        double[] val = new double[randomness.nextInt(12)];
                        for (int y = 0; y < val.length; y++) {
                            val[y] = randomness.nextDouble();
                        }
                        v.setDenseComponent(x, val);
                    }
                }
                // set vec in table
                table.setAssignmentValue(assignment, () -> v);
            }
            model.addFactor(table, neighbors);

            // Pick the number of children

            List<Integer> availableVariables = new ArrayList<>();
            availableVariables.addAll(cliqueContents);
            availableVariables.removeAll(startSet);

            int numChildren = randomness.nextInt(0, availableVariables.size());
            if (numChildren == 0) return;

            List<List<Integer>> children = new ArrayList<>();
            for (int i = 0; i < numChildren; i++) {
                children.add(new ArrayList<>());
            }

            // divide up the shared variables across the children

            int cursor = 0;
            while (true) {
                if (availableVariables.size() == 0) break;
                if (children.get(cursor).size() == 0 || randomness.nextBoolean()) {
                    int gen = randomness.nextInt(availableVariables.size());
                    children.get(cursor).add(availableVariables.get(gen));
                    availableVariables.remove(availableVariables.get(gen));
                }
                else break;

                cursor = (cursor + 1) % numChildren;
            }

            for (List<Integer> shared1 : children) {
                for (int i : shared1) {
                    for (List<Integer> shared2 : children) {
                        assert(shared1 == shared2 || !shared2.contains(i));
                    }
                }
            }

            for (List<Integer> shared : children) {
                if (shared.size() > 0) generateCliques(variableSizes, shared, alreadyRepresented, model, randomness);
            }
        }
    }
}