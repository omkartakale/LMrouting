package com.example.LMrouting.dto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configurable weights for the Composite Load Score formula.
 *
 * <pre>
 * Score(sr) = w1 × shipmentCount
 *           + w2 × totalPhyWeight
 *           + w3 × estimatedDistanceKm
 *           + w4 × heavyShipmentCount
 * </pre>
 *
 * Prototype defaults (application.properties):
 *   allocation.weights.w1=1.0
 *   allocation.weights.w2=0.0
 *   allocation.weights.w3=0.0
 *   allocation.weights.w4=0.0
 */
@ConfigurationProperties(prefix = "allocation.weights")
public class ScoreWeights {

    private double w1 = 1.0;
    private double w2 = 0.0;
    private double w3 = 0.0;
    private double w4 = 0.0;

    public ScoreWeights() {}

    public ScoreWeights(double w1, double w2, double w3, double w4) {
        this.w1 = w1;
        this.w2 = w2;
        this.w3 = w3;
        this.w4 = w4;
    }

    public double getW1() { return w1; }
    public void setW1(double w1) { this.w1 = w1; }

    public double getW2() { return w2; }
    public void setW2(double w2) { this.w2 = w2; }

    public double getW3() { return w3; }
    public void setW3(double w3) { this.w3 = w3; }

    public double getW4() { return w4; }
    public void setW4(double w4) { this.w4 = w4; }
}
