package com.github.keenon.lense.storage;

import com.github.keenon.lense.gameplay.GameTest;
import com.github.keenon.loglinear.model.GraphicalModel;
import com.pholser.junit.quickcheck.ForAll;
import com.pholser.junit.quickcheck.From;
import org.junit.contrib.theories.Theories;
import org.junit.contrib.theories.Theory;
import org.junit.runner.RunWith;

import java.util.Random;

import static org.junit.Assert.*;

/**
 * Created by keenon on 10/17/15.
 *
 * We want to test recording, modifying, and writing to an outputstream.
 */
@RunWith(Theories.class)
public class ModelQueryRecordTest {
    @Theory
    public void testJSON(@ForAll(sampleSize = 100) @From(GameTest.GraphicalModelGenerator.class) GraphicalModel model) throws Exception {
        Random r = new Random();

        ModelQueryRecord qr = ModelQueryRecord.getQueryRecordFor(model);
        int variable = r.nextInt(model.getVariableSizes().length);
        int answer = r.nextInt();
        long delay = r.nextInt(1000);
        // Should write to the model
        qr.recordResponse(variable, answer, delay);

        int variable2 = r.nextInt(model.getVariableSizes().length);
        int answer2 = r.nextInt();
        long delay2 = r.nextInt(1000);
        // Should write to the model
        qr.recordResponse(variable2, answer2, delay2);

        qr.writeBack();

        // Should read from the model
        ModelQueryRecord qr2 = new ModelQueryRecord(model);

        if (variable2 != variable) {
            assertEquals(1, qr2.getResponses(variable).size());
            assertEquals(answer, qr2.getResponses(variable).get(0).response);
            assertEquals(delay, qr2.getResponses(variable).get(0).delay);

            assertEquals(1, qr2.getResponses(variable2).size());
            assertEquals(answer2, qr2.getResponses(variable2).get(0).response);
            assertEquals(delay2, qr2.getResponses(variable2).get(0).delay);
        }
        else {
            assertEquals(2, qr2.getResponses(variable).size());

            assertEquals(answer, qr2.getResponses(variable).get(0).response);
            assertEquals(delay, qr2.getResponses(variable).get(0).delay);

            assertEquals(answer2, qr2.getResponses(variable).get(1).response);
            assertEquals(delay2, qr2.getResponses(variable).get(1).delay);
        }
    }
}