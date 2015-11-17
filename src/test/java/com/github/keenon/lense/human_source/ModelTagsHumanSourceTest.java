package com.github.keenon.lense.human_source;

import com.github.keenon.lense.human_source.ModelTagsHumanSource;
import com.github.keenon.loglinear.model.ConcatVectorNamespace;
import com.github.keenon.loglinear.model.GraphicalModel;
import com.pholser.junit.quickcheck.ForAll;
import com.pholser.junit.quickcheck.From;
import com.github.keenon.lense.gameplay.GameTest;
import com.github.keenon.lense.storage.ModelQueryRecord;
import org.junit.contrib.theories.Theories;
import org.junit.contrib.theories.Theory;
import org.junit.runner.RunWith;

import java.util.Random;

import static org.junit.Assert.*;

/**
 * Created by keenon on 10/17/15.
 *
 * Tests that the ModelTagsHumanSource is returning past results in a deterministic way from the model, and that it is
 * in fact correct.
 */
@RunWith(Theories.class)
public class ModelTagsHumanSourceTest {
    @Theory
    public void testRetrieval(@ForAll(sampleSize = 5) @From(GameTest.GraphicalModelGenerator.class) GraphicalModel model) throws Exception {
        Random r = new Random();

        ModelQueryRecord qr = ModelQueryRecord.getQueryRecordFor(model);
        int variable = r.nextInt(model.getVariableSizes().length);
        int answer = r.nextInt();
        long delay = r.nextInt(1000);
        // Should write to the model
        qr.recordResponse(variable, answer, delay);

        qr.writeBack();

        ConcatVectorNamespace namespace = new ConcatVectorNamespace();

        ModelTagsHumanSource humanSource = new ModelTagsHumanSource(namespace, null);

        final int[] numReturned = {0};
        Object returnBarrier = new Object();

        // Check that the job posting happens

        int[] sizes = model.getVariableSizes();
        for (int i : sizes) if (i <= 0) return;

        humanSource.makeJobPosting(model, (humanHandle) -> {

            // Launch a query we expect to be correct

            long firstQueryLaunched = System.currentTimeMillis();
            humanHandle.makeQuery(variable, (response) -> {
                assertEquals(answer, (int)response);
                long firstQueryLanded = System.currentTimeMillis();
                long queryDelay = firstQueryLanded - firstQueryLaunched;
                // Should be within a few MS of the correct delay
                assertTrue(Math.abs(delay - (queryDelay*10)) < 150);

                numReturned[0]++;
                synchronized (returnBarrier) {
                    returnBarrier.notifyAll();
                }
            }, () -> {
                // failed
                assertTrue(false);
            });

            // Launch a query we expect to fail

            humanHandle.makeQuery(variable + 1, (response) -> {
                // This should not return
                assertTrue(false);
            }, () -> {
                // This is expected behavior
                numReturned[0]++;
                synchronized (returnBarrier) {
                    returnBarrier.notifyAll();
                }
            });
        });

        while (numReturned[0] < 2) {
            synchronized (returnBarrier) {
                returnBarrier.wait();
            }
        }

        System.err.println("Correct behavior");
    }
}