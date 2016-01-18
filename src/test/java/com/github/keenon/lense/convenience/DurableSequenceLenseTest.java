package com.github.keenon.lense.convenience;

import com.github.keenon.loglinear.simple.DurableSequencePredictor;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import org.junit.Test;

import java.util.Arrays;
import java.util.Properties;

import static org.junit.Assert.*;

/**
 * Created by keenon on 1/17/16.
 *
 *
 */
public class DurableSequenceLenseTest {
    @Test
    public void testDurableSequenceLense() throws Exception {
        String[] tags = new String[]{"person", "none"};

        Properties props = new Properties();
        props.setProperty("annotators", "tokenize, ssplit");
        StanfordCoreNLP pipeline = new StanfordCoreNLP(props);

        DurableSequencePredictor predictor = new DurableSequencePredictor("src/test/resources/sequence", tags, pipeline);
        predictor.addUnaryStringFeature("token", ((annotation, index) -> annotation.get(CoreAnnotations.TokensAnnotation.class).get(index).word()));

        DurableSequenceLense lense = new DurableSequenceLense(predictor, "localhost");

        Annotation annotation = new Annotation("hello from Bob");
        pipeline.annotate(annotation);
        System.err.println("----\n" + Arrays.toString(lense.labelSequenceHumanAssisted(annotation)));

        predictor.blockForRetraining();

        Annotation annotation2 = new Annotation("hello from Bob");
        pipeline.annotate(annotation2);
        System.err.println("----\n" + Arrays.toString(lense.labelSequenceHumanAssisted(annotation2)));
    }
}