package com.example.LMrouting.model;

/**
 * Shipment priority tier.
 *
 * <p>Priority determines how aggressively the allocation engine retains a shipment
 * when capacity is exceeded. The factor weights the effective payout:
 * <ul>
 *   <li>P0 (Critical) — factor 1.00 — always retained first</li>
 *   <li>P1 (High)     — factor 0.75 — retained preferentially</li>
 *   <li>P2 (Normal)   — factor 0.50 — shed first when capacity is exceeded</li>
 * </ul>
 *
 * <p>Parsed from the CSV "Priority" column. Defaults to P2 when absent or unparseable.
 */
public enum Priority {
    P0(1.00),
    P1(0.75),
    P2(0.50);

    private final double factor;

    Priority(double factor) {
        this.factor = factor;
    }

    public double getFactor() {
        return factor;
    }

    /**
     * Parse a priority value from a string. Accepts:
     * <ul>
     *   <li>"P0", "p0", "0", "HIGH", "CRITICAL"</li>
     *   <li>"P1", "p1", "1", "MEDIUM"</li>
     *   <li>"P2", "p2", "2", "LOW", "NORMAL"</li>
     * </ul>
     *
     * @param value the string to parse (may be null or blank)
     * @return the parsed Priority, or P2 if unparseable
     */
    public static Priority parse(String value) {
        if (value == null || value.isBlank()) return P2;
        String normalized = value.trim().toUpperCase();
        try {
            return Priority.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            return switch (normalized) {
                case "0", "HIGH", "CRITICAL" -> P0;
                case "1", "MEDIUM" -> P1;
                case "2", "LOW", "NORMAL" -> P2;
                default -> P2;
            };
        }
    }
}
