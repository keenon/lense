package com.github.keenon.lense.convenience;

import com.github.keenon.lense.gameplay.distributions.ContinuousDistribution;
import com.github.keenon.lense.gameplay.distributions.DiscreteSetDistribution;
import com.github.keenon.lense.gameplay.players.GamePlayerThreshold;
import com.github.keenon.lense.gameplay.utilities.UncertaintyUtilityWithoutTime;
import com.github.keenon.lense.human_source.MTurkHumanSource;
import com.github.keenon.lense.lense.Lense;
import com.github.keenon.loglinear.model.ConcatVector;
import com.github.keenon.loglinear.model.GraphicalModel;
import com.github.keenon.loglinear.simple.DurableMulticlassPredictor;
import com.github.keenon.loglinear.simple.DurableSequencePredictor;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.pipeline.Annotation;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.io.IOException;

/**
 * Created by keenon on 1/17/16.
 *
 * This wraps DurableMulticlassPredictor, and adds the default no-fuss human interface elements to it.
 */
public class DurableMulticlassLense {
    protected DurableMulticlassPredictor predictor;
    protected String humanInterfaceHost;
    protected Lense lense;

    private Object lenseMultithreadMonitorLock = new Object();

    private MTurkHumanSource humanSource;
    private GamePlayerThreshold gamePlayer;

    public DurableMulticlassLense(DurableMulticlassPredictor predictor, String humanInterfaceHost) {
        this.predictor = predictor;
        this.humanInterfaceHost = humanInterfaceHost;

        try {
            ContinuousDistribution humanDelaysExpected = new DiscreteSetDistribution(new long[]{1L});
            humanSource = new MTurkHumanSource(humanInterfaceHost, predictor.namespace, humanDelaysExpected);
            gamePlayer = new GamePlayerThreshold();

            setHumanErrorProbability(0.01);
            setQueryThreshold(0.02);

            lense = new Lense(humanSource, gamePlayer, new UncertaintyUtilityWithoutTime(), predictor.weights);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void setQueryThreshold(double threshold) {
        gamePlayer.queryThreshold = threshold;
    }

    public void setHumanErrorProbability(double errorProbability) {
        gamePlayer.humanUncertaintyMultiple = errorProbability;
        humanSource.humanCorrectnessProb = 1.0 - gamePlayer.humanUncertaintyMultiple;
    }

    public String labelSequenceHumanAssisted(Annotation annotation) {

        // Create the raw model, with features

        GraphicalModel model = predictor.createModel(annotation);

        // Set up text for rendering to a human

        for (int i = 0; i < annotation.get(CoreAnnotations.TokensAnnotation.class).size(); i++) {
            // Add the query data
            JSONObject queryData = new JSONObject();

            String html = "What kind of thing is this text?<br>";
            html +="<span class=\"content\">";
            for (int j = 0; j < annotation.get(CoreAnnotations.TokensAnnotation.class).size(); j++) {
                html += " "+annotation.get(CoreAnnotations.TokensAnnotation.class).get(j).word();
            }
            html +="</span>";
            queryData.put("html", html);

            JSONArray choices = new JSONArray();
            for (String tag : predictor.tags) {
                choices.add(tag);
            }
            queryData.put("choices", choices);

            model.getVariableMetaDataByReference(i).put(MTurkHumanSource.QUERY_JSON, queryData.toJSONString());
        }

        // Run lense

        ConcatVector weights = predictor.weights;
        predictor.namespace.setDenseFeature(weights, "BIAS", new double[]{1.0});
        lense.weights = weights;
        String prediction = predictor.tags[lense.getMAP(model, lenseMultithreadMonitorLock)[0]];
        predictor.addTrainingExample(annotation, prediction);

        return prediction;
    }
}
