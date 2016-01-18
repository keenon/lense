package com.github.keenon.lense.convenience;

import com.github.keenon.loglinear.simple.DurableMulticlassPredictor;
import com.github.keenon.loglinear.simple.DurableSequencePredictor;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import org.junit.Test;

import java.util.Arrays;
import java.util.Properties;

/**
 * Created by keenon on 1/17/16.
 *
 *
 */
public class DurableMulticlassLenseTest {
    @Test
    public void testDurableMulticlassLense() throws Exception {
        String[] tags = new String[]{"happy", "sad"};

        Properties props = new Properties();
        props.setProperty("annotators", "tokenize, ssplit");
        StanfordCoreNLP pipeline = new StanfordCoreNLP(props);

        DurableMulticlassPredictor predictor = new DurableMulticlassPredictor("src/test/resources/multiclass", tags, pipeline);
        predictor.addStringFeature("first-token", ((annotation) -> annotation.get(CoreAnnotations.TokensAnnotation.class).get(0).word()));

        DurableMulticlassLense lense = new DurableMulticlassLense(predictor, "localhost");

        Annotation annotation = new Annotation("puppy");
        pipeline.annotate(annotation);
        System.err.println("----\n" + lense.labelSequenceHumanAssisted(annotation));

        predictor.blockForRetraining();

        Annotation annotation2 = new Annotation("puppy");
        pipeline.annotate(annotation2);
        System.err.println("----\n" + lense.labelSequenceHumanAssisted(annotation2));
    }
}