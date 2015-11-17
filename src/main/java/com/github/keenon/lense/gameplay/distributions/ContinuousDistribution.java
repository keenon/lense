package com.github.keenon.lense.gameplay.distributions;

import java.util.Random;

/**
 * Created by keenon on 9/25/15.
 *
 * This is an interface needed by WorldSampler in order to handle generating samples of time delays.
 */
public abstract class ContinuousDistribution {
    public abstract long drawSample(Random r);
}
