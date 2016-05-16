package com.github.keenon.lense.gameplay;

import com.github.keenon.lense.human_source.HumanSource;
import com.github.keenon.loglinear.inference.CliqueTree;
import com.github.keenon.loglinear.model.ConcatVector;
import com.github.keenon.loglinear.model.ConcatVectorTable;
import com.github.keenon.loglinear.model.GraphicalModel;
import com.github.keenon.lense.gameplay.distributions.ContinuousDistribution;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by keenon on 9/22/15.
 *
 * This holds a game state, which is represented as a stack of frames, that can be easily modified by pushing and
 * popping frames from the stack.
 *
 * Frames exist for launching a request, receiving a response, receiving an error, adding a human, removing a human...
 *
 * Each frame has a timestamp, so it can be replayed. The Game object comes with its own protobuf serialization, so that
 * we can use Game as both a working object for calculating moves as well as a written record of everything that the
 * game player has done.
 */
public class Game {

    // Basic data

    public GraphicalModel model;
    public ConcatVector weights;
    public CliqueTree tree;
    public int[] variableSizes;

    // Parameters for sampling events

    public int humansAvailableServerSide = 2; // this resource limit keeps the game tree finite
    public ArtificialHumanProvider humanProvider;
    public Optional<HumanSource> humanSource;

    /**
     * This allows the game to configure the kinds of additional io.hybridcrowd.humans it assumes will show up.
     */
    public static abstract class ArtificialHumanProvider {
        public abstract HumanArrival getArtificialHuman(Game game, HumanJobPosting job);
    }

    /**
     * This is a simple sampled human provider, that gives ConcatVectors for agreement and disagreement, which do not
     * change from variable to variable or assignment to assignment. This is clearly a too-naive view, but ok. It's good
     * for testing.
     */
    public static class ArtificialHumanAgreementDisagrementProvider extends ArtificialHumanProvider {
        ConcatVector agreementVector;
        Map<Integer,ConcatVector> disagreementVectors;
        ContinuousDistribution humanDelayDistribution;

        public ArtificialHumanAgreementDisagrementProvider(ConcatVector agreementVector,
                                                           Map<Integer,ConcatVector> disagreementVectors,
                                                           ContinuousDistribution humanDelayDistribution) {
            this.agreementVector = agreementVector;
            this.disagreementVectors = disagreementVectors;
            this.humanDelayDistribution = humanDelayDistribution;
        }

        @Override
        public HumanArrival getArtificialHuman(Game game, HumanJobPosting job) {
            // This is actually tricky, since we need a distribution over the kinds of io.hybridcrowd.humans who can show up.
            ConcatVectorTable[] errorDistribution = new ConcatVectorTable[game.variableSizes.length];
            for (int i = 0; i < game.variableSizes.length; i++) {
                if (game.variableSizes[i] != -1) {
                    int varSize = game.variableSizes[i];
                    errorDistribution[i] = new ConcatVectorTable(new int[]{varSize,varSize});
                    for (int[] assn : errorDistribution[i]) {
                        errorDistribution[i].setAssignmentValue(assn, assn[0] == assn[1] ? ()->agreementVector : ()->disagreementVectors.get(varSize));
                    }
                }
            }

            return new HumanArrival(errorDistribution, humanDelayDistribution, job, new HashMap<>());
        }
    }

    // Game state

    public Stack<Event> stack = new Stack<>();

    // Frame dependant game state

    public Set<QueryLaunch> inFlightRequests = Collections.newSetFromMap(new IdentityHashMap<>());
    public Set<HumanJobPosting> jobPostings = Collections.newSetFromMap(new IdentityHashMap<>());
    public Set<HumanArrival> availableHumans = Collections.newSetFromMap(new IdentityHashMap<>());
    public Map<Integer,Set<HumanArrival>> availableAnnotators = new HashMap<>();
    public long timeSinceGameStart = 0;

    // Correspondence between variables in the GraphicalModel originally, and variable IDs that will be used to register
    // human observations

    AtomicInteger usedObservationVariables = new AtomicInteger();

    /**
     * Constructor for a new Game object, takes just a Model to do inference over, and a ConcatVector of weights.
     *
     * @param model the model to do inference over
     * @param weights the weights to use
     * @param humanSampler the system for generating hypothetical io.hybridcrowd.humans to arrive and participate in the game
     */
    public Game(GraphicalModel model, ConcatVector weights, ArtificialHumanProvider humanSampler, int humansAvailableServerSide) {
        this.model = model;
        variableSizes = model.getVariableSizes();
        this.weights = weights;
        tree = new CliqueTree(model, weights);
        this.humanProvider = humanSampler;
        this.humansAvailableServerSide = humansAvailableServerSide;

        // Initialize the list that we'll use for available annotators for each variable

        for (GraphicalModel.Factor f : model.factors) {
            for (int n : f.neigborIndices) {
                assert(variableSizes[n] > 0);
                if (!availableAnnotators.containsKey(n)) {
                    availableAnnotators.put(n, Collections.newSetFromMap(new IdentityHashMap<>()));
                }
            }
        }

        // Initialize the mapping from GraphicalModel variables to human observation variables

        int maxID = model.variableMetaData.size()-1;
        for (GraphicalModel.Factor f : model.factors) {
            for (int i : f.neigborIndices) {
                maxID = Math.max(i,maxID);
            }
        }
        usedObservationVariables.set(maxID);
    }

    /**
     * This returns the marginals for the GraphicalModel, modified by any human observations that may have been added to
     * the model since the base of the stack. We only return the marginals for the original model, excluding any
     * additional marginals that may have been included by CliqueTree because we added human observations.
     *
     * @return marginals, in linear space, for each variables' possible assignments
     */
    public double[][] getMarginals() {
        double[][] marginals = tree.calculateMarginalsJustSingletons();
        double[][] clippedMarginals = new double[variableSizes.length][];
        System.arraycopy(marginals, 0, clippedMarginals, 0, variableSizes.length);
        return clippedMarginals;
    }

    /**
     * Gets the MAP estimate from the current state of the game.
     *
     * @return the assignments to each of the variables with highest probability given observations
     */
    public int[] getMAP() {
        int[] map = tree.calculateMAP();
        int[] clippedMap = new int[variableSizes.length];
        System.arraycopy(map, 0, clippedMap, 0, variableSizes.length);
        return clippedMap;
    }

    /**
     * This is handy shorthand for removing all the events in a game so that it can be used again. Mostly used in
     * simulations, although can come in handy in gameplayers too.
     */
    public void resetEvents() {
        while (!stack.empty()) {
            stack.peek().pop(this);
        }
    }

    /**
     * @return whether it is the turn of the game player to move, or the environment
     */
    public boolean isGameplayerTurn() {
        return stack.empty() || (!(stack.peek() instanceof Wait) && !(stack.peek() instanceof TurnIn));
    }

    /**
     * @return whether the game is complete yet
     */
    public boolean isTerminated() {
        return !stack.empty() && stack.peek() instanceof TurnIn;
    }

    /**
     * This returns a list of all moves that are legal in the current environment.
     */
    public Event[] getLegalMoves() {
        assert(isGameplayerTurn());

        if (isTerminated()) return new Event[0];

        // We keep a tab on the number of job postings allowed, which constrains the game trees from growing infinitely

        int numJobPostings = 0;
        for (Event e : stack) {
            if (e instanceof HumanJobPosting) numJobPostings++;
        }
        boolean jobPostingAllowed = numJobPostings < humansAvailableServerSide;

        /*
        // Don't allow new job postings after the first query has been launched, or else the game trees get unnecessarily
        // large, if we're not in production. This is annoying in production, so we're dropping it.

        for (Event e : stack) {
            if (e instanceof QueryLaunch) jobPostingAllowed = false;
        }
        */

        int numLegalMoves =
                availableAnnotators.values().stream().mapToInt(Set::size).sum() + // Observation requests
                        (inFlightRequests.size() == 0 && jobPostings.size() == 0 ? 1 : 0) + // Turn in
                        (inFlightRequests.size() > 0 || jobPostings.size() > 0 ? 1 : 0) + // Wait
                        (jobPostingAllowed ? 1 : 0); // Make job posting

        Event[] legalMoves = new Event[numLegalMoves];
        int cursor = 0;

        if (inFlightRequests.size() == 0 && jobPostings.size() == 0) {
            legalMoves[cursor++] = new TurnIn();
        }
        if (inFlightRequests.size() > 0 || jobPostings.size() > 0) {
            legalMoves[cursor++] = new Wait();
        }

        if (jobPostingAllowed) {
            legalMoves[cursor++] = new HumanJobPosting();
        }

        for (int i : availableAnnotators.keySet()) {
            for (HumanArrival human : availableAnnotators.get(i)) {
                legalMoves[cursor] = new QueryLaunch(i, human);
                cursor++;
            }
        }

        if (!stack.empty()) {
            for (Event e : legalMoves) {
                e.timeSinceGameStart = stack.peek().timeSinceGameStart;
            }
        }

        return legalMoves;
    }

    /**
     * Our simulation sometimes returns deterministic values. We don't want useless gameplayer branching on values that
     * we expect to always be the same.
     *
     * @return whether on not the next environmental move is fully determined by the state.
     */
    public boolean isNextSampleEventDeterministic() {

        // If there was a human who left, then inevitably we have a number of query failures to deliver.

        for (Event e : stack) {
            if (e instanceof QueryLaunch) {
                QueryLaunch ql = (QueryLaunch)e;
                if (inFlightRequests.contains(ql) && !availableHumans.contains(ql.human)) {
                    return true;
                }
            }
        }

        // If there are job postings, return an answer to one of those

        if (jobPostings.size() > 0) {
            return true;
        }

        // Otherwise there's a continuous number of events that could happen

        return false;
    }

    /**
     * Gets a random sample of the next event, under several useful simplifying assumptions:
     * - Humans don't leave
     * - Queries don't fail
     * - JobPostings are always answered before other events take place
     *
     * This assumes that query response values and response times are independently distributed, but makes no assumptions
     * about time being deterministic.
     *
     * If this is called and the last Event on the stack is not a Wait, then an assertion will be thrown.
     */
    public Event sampleNextEvent(Random r) {
        assert(!isGameplayerTurn());
        assert(!isTerminated());

        // If there was a human who left, then inevitably we have a number of query failures to deliver.

        for (Event e : stack) {
            if (e instanceof QueryLaunch) {
                QueryLaunch ql = (QueryLaunch)e;
                if (inFlightRequests.contains(ql) && !availableHumans.contains(ql.human)) {
                    QueryFailure qf = new QueryFailure(ql);
                    qf.timeSinceGameStart = timeSinceGameStart;
                    return qf;
                }
            }
        }

        // If there are job postings, return an answer to one of those

        if (jobPostings.size() > 0) {
            HumanArrival ha = humanProvider.getArtificialHuman(this, jobPostings.iterator().next());
            ha.timeSinceGameStart = timeSinceGameStart;
            return ha;
        }

        // If there are queries in flight, we draw a sampled "return time" for each of them from the human's delay model,
        // then pick the thing returning the soonest. Then we can draw a possible value for that response from
        // the marginals of the model.

        // Draw the soonest returning query

        QueryLaunch soonestReturn = null;
        long soonestReturnTime = Long.MAX_VALUE;
        for (QueryLaunch ql : inFlightRequests) {
            long returnTime = ql.timeSinceGameStart + ql.human.delayModel.drawSample(r);
            if (returnTime < soonestReturnTime) {
                soonestReturnTime = returnTime;
                soonestReturn = ql;
            }
        }
        assert(soonestReturn != null);

        // Draw the outcome from the current marginals:

        double[][] marginals = getMarginals();
        // TODO: this could be made smarter by multiplying by the human distribution
        double[] dist = marginals[soonestReturn.variable];

        double draw = r.nextDouble();
        for (int i = 0; i < dist.length; i++) {
            draw -= dist[i];
            if (draw <= 0) {
                QueryResponse qr = new QueryResponse(soonestReturn, i);
                qr.timeSinceGameStart = soonestReturnTime;
                return qr;
            }
        }

        throw new IllegalStateException("We should never reach this point");
    }

    /**
     * Gets events for all moves that are legal in the current setting. Assumes that the human delay distributions only
     * return a single value when queried (aka is deterministic).
     *
     * @return an array of all legal moves in this scenario
     */
    public Event[] sampleAllPossibleEventsAssumingDeterministicTime() {
        assert(!isGameplayerTurn());
        assert(!isTerminated());

        Random r = new Random(42);

        // Special case: If a human has left, and there are still in-flight requests for that human, those must be
        // cancelled one at a time. To make sampling as efficient as possible, we impose a deterministic ordering on the
        // order in which those responses are cancelled: oldest first

        for (Event e : stack) {
            if (e instanceof QueryLaunch) {
                QueryLaunch ql = (QueryLaunch)e;
                if (inFlightRequests.contains(ql) && !availableHumans.contains(ql.human)) {
                    QueryFailure qf = new QueryFailure(ql);
                    qf.timeSinceGameStart = timeSinceGameStart;
                    return new Event[]{qf};
                }
            }
        }

        // Job postings always return before responses, in our sampled world

        if (jobPostings.size() > 0) {
            Event[] sampledResponses = new Event[jobPostings.size()];
            int cursor = 0;
            for (HumanJobPosting job : jobPostings) {
                sampledResponses[cursor] = humanProvider.getArtificialHuman(this, job);
                sampledResponses[cursor].timeSinceGameStart = timeSinceGameStart;
                cursor++;
            }
            return sampledResponses;
        }

        // Assuming there are queries, then we need to return from the set according to deterministic time.

        long minimumReturnTime = Long.MAX_VALUE;
        for (QueryLaunch ql : inFlightRequests) {
            long returnTime = ql.timeSinceGameStart + ql.human.delayModel.drawSample(r);
            if (returnTime < minimumReturnTime) minimumReturnTime = returnTime;
        }

        Set<QueryLaunch> minimumReturnTimeQueries = new HashSet<>();
        for (QueryLaunch ql : inFlightRequests) {
            long returnTime = ql.timeSinceGameStart + ql.human.delayModel.drawSample(r);
            if (returnTime == minimumReturnTime) minimumReturnTimeQueries.add(ql);
        }

        Event[] sampledResponses = new Event[minimumReturnTimeQueries.stream().mapToInt(ql -> variableSizes[ql.variable]).sum()];
        int cursor = 0;
        for (QueryLaunch q : minimumReturnTimeQueries) {
            for (int i = 0; i < variableSizes[q.variable]; i++) {
                sampledResponses[cursor] = new QueryResponse(q, i);
                sampledResponses[cursor].timeSinceGameStart = minimumReturnTime;
                cursor++;
            }
        }
        return sampledResponses;
    }

    /**
     * Gets a set of thread safe copies of the game in its current state.
     *
     * @param numClones the number of copies to get
     * @return an array of thread safe copies of the current game
     */
    public Game[] getClones(int numClones) {
        List<Event> events = new ArrayList<>();
        events.addAll(stack);
        resetEvents();

        Game[] clones = new Game[numClones];
        for (int i = 0; i < numClones; i++) {
            clones[i] = new Game(model.cloneModel(), weights, humanProvider, humansAvailableServerSide);

            Map<Event,Event> oldToNew = new IdentityHashMap<>();
            for (Event e : events) {
                oldToNew.put(e, e.clone(oldToNew));
                oldToNew.get(e).push(clones[i]);
            }
        }

        for (Event e : events) e.push(this);

        return clones;
    }

    ////////////////////////////////////////////////////////////////////////////////////
    // FRAME DEFINITIONS
    ////////////////////////////////////////////////////////////////////////////////////

    /**
     * This represents a single event in the history of a game. Frames can be applied and removed from any given Game,
     * and are intended to stack. This enables two things:
     * - Sampling futures by pushing and popping frames from the stack.
     * - Replaying games by examining the timestamp of each frame on the stack.
     *
     * Each frame must be able to push itself onto a game and pop itself off.
     */
    public abstract static class Event {
        public long timeSinceGameStart = 0;

        public void push(Game game) {
            game.stack.add(this);
            assert(timeSinceGameStart >= game.timeSinceGameStart);
            game.timeSinceGameStart = timeSinceGameStart;
        }
        public void pop(Game game) {
            assert(timeSinceGameStart == game.timeSinceGameStart);
            game.stack.pop();
            assert(game.stack.empty() || game.stack.peek().timeSinceGameStart <= game.timeSinceGameStart);
            if (game.stack.empty()) {
                game.timeSinceGameStart = 0;
            }
            else {
                game.timeSinceGameStart = game.stack.peek().timeSinceGameStart;
            }
        }

        public abstract boolean isGameplayerInitiated();

        public abstract Event clone(Map<Event,Event> oldToNew);

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Event)) return false;
            Event e = (Event)o;
            return e.timeSinceGameStart == timeSinceGameStart;
        }

        @Override
        public int hashCode() {
            return (int)timeSinceGameStart;
        }
    }

    /**
     * Records the launching of a single request. The request is given to a human, and that is recorded.
     */
    public static class QueryLaunch extends Event {
        public int variable;
        public HumanArrival human;

        public QueryLaunch(int variable, HumanArrival human) {
            this.variable = variable;
            this.human = human;
        }

        @Override
        public void push(Game game) {
            super.push(game);

            assert(!game.inFlightRequests.contains(this));
            game.inFlightRequests.add(this);

            assert(game.availableAnnotators.containsKey(variable));
            assert(game.availableAnnotators.get(variable).contains(human));

            game.availableAnnotators.get(variable).remove(human);
        }

        @Override
        public void pop(Game game) {
            super.pop(game);

            assert(game.inFlightRequests.contains(this));
            game.inFlightRequests.remove(this);

            assert(game.availableAnnotators.containsKey(variable));
            assert(!game.availableAnnotators.get(variable).contains(human));

            game.availableAnnotators.get(variable).add(human);
        }

        @Override
        public boolean isGameplayerInitiated() {
            return true;
        }

        @Override
        public Event clone(Map<Event, Event> oldToNew) {
            assert(oldToNew.containsKey(human));
            QueryLaunch ql = new QueryLaunch(variable, (HumanArrival)oldToNew.get(human));
            ql.timeSinceGameStart = timeSinceGameStart;
            return ql;
        }

        @Override
        public String toString() {
            return "QueryLaunch("+variable+","+human+")";
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof QueryLaunch)) return false;
            QueryLaunch ql = (QueryLaunch)o;
            return ql.variable == variable && ql.human.equals(human) && super.equals(o);
        }

        @Override
        public int hashCode() {
            return variable * human.hashCode() * super.hashCode();
        }
    }

    /**
     * Receive a response for an initial request.
     */
    public static class QueryResponse extends Event {
        public QueryLaunch request;
        public int response;

        GraphicalModel.Factor cachedFactor = null;
        int humanObservationVariable = -1;

        public QueryResponse(QueryLaunch request, int response) {
            this.request = request;
            this.response = response;
        }

        @Override
        public void push(Game game) {
            super.push(game);

            assert(game.inFlightRequests.contains(request));
            game.inFlightRequests.remove(request);

            humanObservationVariable = game.usedObservationVariables.incrementAndGet();

            // Create the factor, or add the cached one

            if (cachedFactor == null) {
                int[] neighborIndices = new int[]{request.variable, humanObservationVariable};
                cachedFactor = game.model.addFactor(request.human.humanErrorModel[request.variable], neighborIndices);
            }
            else {
                assert(!game.model.factors.contains(cachedFactor));
                game.model.factors.add(cachedFactor);
            }

            // Add the variable observation

            assert(game.model.variableMetaData.size() <= humanObservationVariable);
            game.model.getVariableMetaDataByReference(humanObservationVariable).put(CliqueTree.VARIABLE_OBSERVED_VALUE, "" + response);
            assert(game.model.variableMetaData.size() == humanObservationVariable + 1);
        }

        @Override
        public void pop(Game game) {
            super.pop(game);

            game.usedObservationVariables.decrementAndGet();

            assert(!game.inFlightRequests.contains(request));
            game.inFlightRequests.add(request);

            // Remove the cached factor

            assert(cachedFactor != null);
            assert(game.model.factors.contains(cachedFactor));
            game.model.factors.remove(cachedFactor);

            // Remove the variable observation

            assert(game.model.variableMetaData.size() == humanObservationVariable + 1);
            game.model.variableMetaData.remove(humanObservationVariable);
            assert(game.model.variableMetaData.size() == humanObservationVariable); // we clipped off the end
        }

        @Override
        public boolean isGameplayerInitiated() {
            return false;
        }

        @Override
        public Event clone(Map<Event, Event> oldToNew) {
            assert(oldToNew.containsKey(request));
            QueryResponse qr = new QueryResponse((QueryLaunch)oldToNew.get(request), response);
            qr.timeSinceGameStart = timeSinceGameStart;
            return qr;
        }

        @Override
        public String toString() {
            return "QueryResponse("+response+","+request+")";
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof QueryResponse)) return false;
            QueryResponse qr = (QueryResponse)o;
            return qr.response == response && qr.request.equals(request) && super.equals(qr);
        }

        @Override
        public int hashCode() {
            return response * request.hashCode() * super.hashCode();
        }
    }

    /**
     * A request to a human fails for some reason, either they crash or quit before answering the question
     */
    public static class QueryFailure extends Event {
        public QueryLaunch request;

        public QueryFailure(QueryLaunch request) {
            this.request = request;
        }

        @Override
        public void push(Game game) {
            super.push(game);

            assert(game.inFlightRequests.contains(request));
            game.inFlightRequests.remove(request);
        }

        @Override
        public void pop(Game game) {
            super.pop(game);

            assert(!game.inFlightRequests.contains(request));
            game.inFlightRequests.add(request);
        }

        @Override
        public boolean isGameplayerInitiated() {
            return false;
        }

        @Override
        public Event clone(Map<Event, Event> oldToNew) {
            QueryFailure qf = new QueryFailure((QueryLaunch)oldToNew.get(request));
            qf.timeSinceGameStart = timeSinceGameStart;
            return qf;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof QueryFailure)) return false;
            QueryFailure qf = (QueryFailure)o;
            return qf.request.equals(request) && super.equals(qf);
        }

        @Override
        public int hashCode() {
            return request.hashCode() * super.hashCode();
        }
    }

    /**
     * Records that a human has shown up to the system
     */
    public static class HumanArrival extends Event {
        public ConcatVectorTable[] humanErrorModel;
        public ContinuousDistribution delayModel;
        public HumanJobPosting respondingTo;

        // This functions like the metaData section on GraphicalModel, it's mostly so that users can record information
        // that went into featurizing, so that refeaturizing the models is relatively easy in an offline setting.
        public Map<String,String> metaData = new HashMap<>();

        public HumanArrival(ConcatVectorTable[] humanErrorModel, ContinuousDistribution delayModel, HumanJobPosting respondingTo, Map<String,String> metaData) {
            this.humanErrorModel = humanErrorModel;
            this.delayModel = delayModel;
            this.respondingTo = respondingTo;
            this.metaData = metaData;
        }

        @Override
        public void push(Game game) {
            super.push(game);

            assert(!game.availableHumans.contains(this));
            game.availableHumans.add(this);

            assert(game.jobPostings.contains(respondingTo));
            game.jobPostings.remove(respondingTo);

            for (int i = 0; i < humanErrorModel.length; i++) {
                if (humanErrorModel[i] != null && game.availableAnnotators.containsKey(i)) {
                    assert(!game.availableAnnotators.get(i).contains(this));
                    game.availableAnnotators.get(i).add(this);
                }
            }
        }

        @Override
        public void pop(Game game) {
            super.pop(game);

            assert(game.availableHumans.contains(this));
            game.availableHumans.remove(this);

            assert(!game.jobPostings.contains(respondingTo));
            game.jobPostings.add(respondingTo);

            for (int i = 0; i < humanErrorModel.length; i++) {
                if (humanErrorModel[i] != null && game.availableAnnotators.containsKey(i)) {
                    assert(game.availableAnnotators.get(i).contains(this));
                    game.availableAnnotators.get(i).remove(this);
                }
            }
        }

        @Override
        public boolean isGameplayerInitiated() {
            return false;
        }

        @Override
        public Event clone(Map<Event, Event> oldToNew) {
            HumanArrival ha = new HumanArrival(humanErrorModel, delayModel, (HumanJobPosting)oldToNew.get(respondingTo), metaData);
            ha.timeSinceGameStart = timeSinceGameStart;
            return ha;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof HumanArrival)) return false;
            HumanArrival ha = (HumanArrival)o;
            return ha.humanErrorModel == humanErrorModel && ha.delayModel == delayModel && ha.respondingTo.equals(respondingTo) && super.equals(ha);
        }

        @Override
        public int hashCode() {
            return respondingTo.hashCode() * super.hashCode();
        }
    }

    /**
     * Records that a human has left the system
     */
    public static class HumanExit extends Event {
        public HumanArrival human;

        public HumanExit(HumanArrival human) {
            this.human = human;
        }

        @Override
        public void push(Game game) {
            super.push(game);

            assert(game.availableHumans.contains(human));
            game.availableHumans.remove(human);
        }

        @Override
        public void pop(Game game) {
            super.pop(game);

            assert(!game.availableHumans.contains(human));
            game.availableHumans.add(human);
        }

        @Override
        public boolean isGameplayerInitiated() {
            return false;
        }

        @Override
        public Event clone(Map<Event, Event> oldToNew) {
            HumanExit he = new HumanExit((HumanArrival)oldToNew.get(human));
            he.timeSinceGameStart = timeSinceGameStart;
            return he;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof HumanExit)) return false;
            HumanExit he = (HumanExit)o;
            return he.human.equals(human) && super.equals(he);
        }

        @Override
        public int hashCode() {
            return human.hashCode() * super.hashCode();
        }
    }

    /**
     * Records that we've made a JobPosting, which will then trigger the acquisition of a human annotator.
     */
    public static class HumanJobPosting extends Event {
        @Override
        public void push(Game game) {
            super.push(game);

            assert(!game.jobPostings.contains(this));
            game.jobPostings.add(this);
        }

        @Override
        public void pop(Game game) {
            super.pop(game);

            assert(game.jobPostings.contains(this));
            game.jobPostings.remove(this);
        }

        @Override
        public boolean isGameplayerInitiated() {
            return true;
        }

        @Override
        public Event clone(Map<Event, Event> oldToNew) {
            HumanJobPosting jp = new HumanJobPosting();
            jp.timeSinceGameStart = timeSinceGameStart;
            return jp;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof HumanJobPosting && super.equals(o);
        }

        // Just take the hashCode from event()
    }

    /**
     * Records that we've freed our need for this person before the game completed, which allows them to move on to
     * other tasks, which increases our global utility. This is functionally equivalent to HumanExit, except it is an
     * action that's initiated by the GamePlayer.
     */
    public static class HumanRelease extends Event {
        public HumanArrival human;

        public HumanRelease(HumanArrival human) {
            this.human = human;
        }

        @Override
        public void push(Game game) {
            super.push(game);

            assert(game.availableHumans.contains(human));
            game.availableHumans.remove(human);
        }

        @Override
        public void pop(Game game) {
            super.pop(game);

            assert(!game.availableHumans.contains(human));
            game.availableHumans.add(human);
        }

        @Override
        public boolean isGameplayerInitiated() {
            return true;
        }

        @Override
        public Event clone(Map<Event, Event> oldToNew) {
            HumanRelease hr = new HumanRelease((HumanArrival)oldToNew.get(human));
            hr.timeSinceGameStart = timeSinceGameStart;
            return hr;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof HumanRelease)) return false;
            HumanRelease hr = (HumanRelease)o;
            return hr.human.equals(human) && super.equals(hr);
        }

        @Override
        public int hashCode() {
            return human.hashCode() * super.hashCode();
        }
    }

    /**
     * Record that the algorithm decided to wait.
     */
    public static class Wait extends Event {
        @Override
        public boolean isGameplayerInitiated() {
            return true;
        }

        @Override
        public Event clone(Map<Event, Event> oldToNew) {
            Wait w = new Wait();
            w.timeSinceGameStart = timeSinceGameStart;
            return w;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Wait && super.equals(o);
        }

        // Just take the hashCode from event()
    }

    /**
     * Record that the algorithm elected to turn in the results of the Game.
     */
    public static class TurnIn extends Event {
        @Override
        public boolean isGameplayerInitiated() {
            return true;
        }

        @Override
        public Event clone(Map<Event, Event> oldToNew) {
            TurnIn t = new TurnIn();
            t.timeSinceGameStart = timeSinceGameStart;
            return t;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof TurnIn && super.equals(o);
        }

        // Just take the hashCode from event()
    }
}
