package com.github.keenon.lense.gameplay.players;

import com.github.keenon.lense.gameplay.Game;
import com.github.keenon.lense.storage.ModelQueryRecord;

import java.util.function.Function;

/**
 * Created by keenon on 10/17/15.
 *
 * Makes sure that it gets N votes on every variable. This is a useful game player for collecting data, when paired with
 * ModelBatch for saving the meta-data associated with observations.
 */
public class GamePlayerNVote extends GamePlayer {
    int n;
    boolean usePastLabelsAsVotes = false;

    public GamePlayerNVote(int n, boolean usePastLabelsAsVotes) {
        this.n = n;
        this.usePastLabelsAsVotes = usePastLabelsAsVotes;
    }

    @Override
    public Game.Event getNextMove(Game game, Function<Game, Double> utility) {

        boolean wantedToLaunchQuery = false;

        for (int i = 0; i < game.variableSizes.length; i++) {
            // Skip examples with missing variables
            if (game.variableSizes[i] == -1) continue;
            // Check the number of in flight queries we have for this variable
            int inFlight = 0;
            for (Game.QueryLaunch ql : game.inFlightRequests) {
                if (ql.variable == i) inFlight++;
            }
            // Check the number of observations we have for this variable
            int observations = 0;
            if (usePastLabelsAsVotes) {
                observations += ModelQueryRecord.getQueryRecordFor(game.model).getResponses(i).size();
            }
            else {
                for (Game.Event event : game.stack) {
                    if (event instanceof Game.QueryResponse) {
                        Game.QueryResponse qr = (Game.QueryResponse) event;
                        if (qr.request.variable == i) observations++;
                    }
                }
            }
            // Check the number of humans we have available to annotate this variable
            int availableHumans = game.availableAnnotators.get(i).size();
            int jobRequestsInFlight = game.jobPostings.size();

            // This means we need to launch queries
            if (observations + inFlight < n) {
                int neededQueries = n - (observations + inFlight);
                // If we can't launch more queries, we need to hire more people
                if (availableHumans == 0) {
                    if (jobRequestsInFlight >= neededQueries) {
                        // This means that we wanted to launch a query, but were waiting on job postings
                        wantedToLaunchQuery = true;
                    }
                    else {
                        return new Game.HumanJobPosting();
                    }
                }
                // Don't query unless that human is already done with the last task, to avoid query overload
                else {
                    outer: for (Game.HumanArrival human : game.availableAnnotators.get(i)) {
                        for (Game.QueryLaunch ql : game.inFlightRequests) {
                            if (ql.human == human) continue outer;
                        }
                        return new Game.QueryLaunch(i, human);
                    }
                }
                // If we reach this point, we want to launch queries but our human is busy
                wantedToLaunchQuery = true;
            }
            else {
                // This means we're satisfied with this variable for now
            }
        }

        // If we reached this point, and there are any humans who don't have queries in flight, it means they're not
        // needed.

        outer: for (Game.HumanArrival human : game.availableHumans) {
            for (Game.QueryLaunch ql : game.inFlightRequests) {
                if (ql.human == human) continue outer;
            }
            return new Game.HumanRelease(human);
        }

        if (wantedToLaunchQuery || game.inFlightRequests.size() > 0 || game.jobPostings.size() > 0) {
            return new Game.Wait();
        }

        return new Game.TurnIn();
    }
}
