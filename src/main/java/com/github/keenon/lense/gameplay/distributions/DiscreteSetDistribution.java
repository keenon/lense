package com.github.keenon.lense.gameplay.distributions;

import java.util.Random;

/**
 * Created by keenon on 9/25/15.
 *
 * Draws randomly, with replacement, from a given set of values
 */
public class DiscreteSetDistribution extends ContinuousDistribution {
    long[] potentialValues;

    public DiscreteSetDistribution(long[] potentialValues) {
        this.potentialValues = potentialValues;
    }

    @Override
    public long drawSample(Random r) {
        return potentialValues[r.nextInt(potentialValues.length)];
    }
}
