package com.github.keenon.lense.storage;

import com.github.keenon.loglinear.model.GraphicalModel;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

import java.util.*;

/**
 * Created by keenon on 10/17/15.
 *
 * This manages recording, storing, and reading previous answers to these queries.
 */
public class ModelQueryRecord {
    // This is used to record human answers that we've seen previously, for convenient replay from the GraphicalModel
    public static final String QUERY_ANSWERS = "io.hybridcrowd.humans.ModelTagsHumanSource.QUERY_ANSWERS";

    private static Map<GraphicalModel, ModelQueryRecord> modelQueryRecords = new IdentityHashMap<>();

    /**
     * This will return the query record for the GraphicalModel in question.
     *
     * @param model the model we want a query record for
     * @return the query record, either from records or created during the call
     */
    public static ModelQueryRecord getQueryRecordFor(GraphicalModel model) {
        if (!modelQueryRecords.containsKey(model)) {
            modelQueryRecords.put(model, new ModelQueryRecord(model));
        }
        return modelQueryRecords.get(model);
    }

    public GraphicalModel model;
    Map<Integer, List<QueryRecord>> queries = new HashMap<>();

    /**
     * Creates a ModelQueryRecord, and reads the query data back from the metadata
     *
     * @param model the model to create the record for
     */
    public ModelQueryRecord(GraphicalModel model) {
        this.model = model;

        // Parse out the values
        for (int variable = 0; variable < Math.max(model.getVariableSizes().length, model.variableMetaData.size()); variable++) {
            JSONArray responsesJSON = (JSONArray) JSONValue.parse(model.getVariableMetaDataByReference(variable).getOrDefault(QUERY_ANSWERS, "[]"));
            queries.put(variable, new ArrayList<>());

            for (int i = 0; i < responsesJSON.size(); i++) {
                JSONObject responseJSON = (JSONObject) responsesJSON.get(i);
                int response = ((Long) responseJSON.get("response")).intValue();
                long delay = (Long) responseJSON.get("delay");
                queries.get(variable).add(new QueryRecord(response, delay));
            }
        }
    }

    /**
     * Adds a response to the data-structure, and writes the new value back out to the model
     *
     * @param variable the name of the variable
     * @param number the response value
     * @param delay the delay the response took
     */
    public synchronized void recordResponse(int variable, int number, long delay) {
        if (!queries.containsKey(variable)) queries.put(variable, new ArrayList<>());
        queries.get(variable).add(new QueryRecord(number, delay));
        // writeBack();
    }

    /**
     * Gets the responses that a single variable has received. This is basically just useful for replay human sources.
     *
     * @param variable the variable
     * @return a list of QueryRecords for queries that have been made on that variable
     */
    public synchronized List<QueryRecord> getResponses(int variable) {
        if (!queries.containsKey(variable)) {
            queries.put(variable, new ArrayList<>());
        }
        return queries.get(variable);
    }

    /**
     * This holds the basic info that represents a single query result.
     */
    public static class QueryRecord {
        public int response;
        public long delay;

        public QueryRecord(int response, long delay) {
            this.response = response;
            this.delay = delay;
        }
    }

    /**
     * Writes the current state back to the GraphicalModel's metadata
     */
    public void writeBack() {
        for (int variable : queries.keySet()) {
            JSONArray responsesJSON = new JSONArray();
            for (QueryRecord qr : queries.get(variable)) {
                JSONObject responseJSON = new JSONObject();
                responseJSON.put("response", qr.response);
                responseJSON.put("delay", qr.delay);
                responsesJSON.add(responseJSON);
            }

            model.getVariableMetaDataByReference(variable).put(QUERY_ANSWERS, responsesJSON.toJSONString());
        }
    }
}
