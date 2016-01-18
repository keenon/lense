package com.github.keenon.lense.convenience;

import com.github.keenon.lense.gameplay.distributions.ContinuousDistribution;
import com.github.keenon.lense.gameplay.distributions.DiscreteSetDistribution;
import com.github.keenon.lense.gameplay.players.GamePlayer;
import com.github.keenon.lense.gameplay.players.GamePlayerThreshold;
import com.github.keenon.lense.gameplay.utilities.UncertaintyUtilityWithoutTime;
import com.github.keenon.lense.human_source.HumanSource;
import com.github.keenon.lense.human_source.MTurkHumanSource;
import com.github.keenon.lense.lense.Lense;
import com.github.keenon.loglinear.model.GraphicalModel;
import com.github.keenon.loglinear.simple.DurableSequencePredictor;
import com.github.keenon.loglinear.simple.SimpleDurablePredictor;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.pipeline.Annotation;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.io.IOException;
import java.io.Serializable;

/**
 * Created by keenon on 1/17/16.
 *
 * This wraps DurableSequencePredictor, and adds the default no-fuss human interface elements to it.
 */
public class DurableSequenceLense {
    protected DurableSequencePredictor predictor;
    protected String humanInterfaceHost;
    protected Lense lense;

    private Object lenseMultithreadMonitorLock = new Object();

    public DurableSequenceLense(DurableSequencePredictor predictor, String humanInterfaceHost) {
        this.predictor = predictor;
        this.humanInterfaceHost = humanInterfaceHost;

        try {
            ContinuousDistribution humanDelaysExpected = new DiscreteSetDistribution(new long[]{1L});
            HumanSource humanSource = new MTurkHumanSource(humanInterfaceHost, predictor.namespace, humanDelaysExpected);
            GamePlayerThreshold gamePlayer = new GamePlayerThreshold();

            gamePlayer.queryThreshold = 0.02;
            gamePlayer.humanUncertaintyMultiple = 0.01;

            lense = new Lense(humanSource, gamePlayer, new UncertaintyUtilityWithoutTime(), predictor.weights);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String[] labelSequenceHumanAssisted(Annotation annotation) {

        // Create the raw model, with features

        GraphicalModel model = predictor.createModel(annotation);

        // Set up text for rendering to a human

        for (int i = 0; i < annotation.get(CoreAnnotations.TokensAnnotation.class).size(); i++) {
            // Add the query data
            JSONObject queryData = new JSONObject();

            String html = "What kind of thing is the highlighted word?<br>";
            html +="<span class=\"content\">";
            for (int j = 0; j < annotation.get(CoreAnnotations.TokensAnnotation.class).size(); j++) {
                if (j == i) html +="<span class=\"focus\">";
                html += " "+annotation.get(CoreAnnotations.TokensAnnotation.class).get(j).word();
                if (j == i) html +="</span>";
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

        lense.weights = predictor.weights;
        int[] prediction = lense.getMAP(model, lenseMultithreadMonitorLock);

        // Create the labeled result

        String[] labeledSequence = new String[prediction.length];
        for (int i = 0; i < labeledSequence.length; i++) {
            labeledSequence[i] = predictor.tags[prediction[i]];
        }

        predictor.addTrainingExample(annotation, labeledSequence);
        return labeledSequence;
    }
}
