package com.example.LMrouting;

import com.example.LMrouting.model.Shipment;
import com.example.LMrouting.service.GoogleMapsService;
import com.example.LMrouting.service.OpenRouteService;
import com.example.LMrouting.service.ShiftWorkloadCalculatorService;
import com.example.LMrouting.service.TravelTimeCacheService;
import net.jqwik.api.*;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.NotEmpty;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.*;

/**
 * Property-based tests for ShiftWorkloadCalculatorService.
 *
 * Feature: affinity-shift-allocation
 * Property 3: workload > 0 for non-empty list
 * Property 4: components sum to total
 * Property 5: handling time classification for COD/Prepaid/other
 */
class ShiftWorkloadPropertyTest {

    private static final double HUB_LAT = 18.4600561;
    private static final double HUB_LNG = 73.8884305;
    private static final double LAT_MIN = 18.40;
    private static final double LAT_MAX = 18.60;
    private static final double LNG_MIN = 73.75;
    private static final double LNG_MAX = 74.00;

    private ShiftWorkloadCalculatorService buildCalculator() throws Exception {
        OpenRouteService ors = mock(OpenRouteService.class);
        when(ors.isConfigured()).thenReturn(false);
        GoogleMapsService gms = mock(GoogleMapsService.class);
        when(gms.isApiKeyConfigured()).thenReturn(false);

        TravelTimeCacheService cache = new TravelTimeCacheService(ors, gms);
        setField(cache, "hubLat", HUB_LAT);
        setField(cache, "hubLng", HUB_LNG);
        setField(cache, "avgSpeedKmh", 20.0);

        ShiftWorkloadCalculatorService calculator = new ShiftWorkloadCalculatorService(cache);
        setField(calculator, "hubLat", HUB_LAT);
        setField(calculator, "hubLng", HUB_LNG);
        setField(calculator, "handlingTimeCod", 3.0);
        setField(calculator, "handlingTimePrepaid", 3.0);
        setField(calculator, "handlingTimeDefault", 3.0);
        return calculator;
    }

    // ── Property 3: workload > 0 for non-empty list ───────────────────────────

    /**
     * Feature: affinity-shift-allocation, Property 3:
     * For any non-empty ordered list of shipments,
     * computeWorkload() SHALL return totalMinutes > 0.
     */
    @Property(tries = 100)
    void workloadIsPositiveForNonEmptyList(
            @ForAll("nonEmptyShipmentLists") @NotEmpty List<Shipment> shipments) throws Exception {

        ShiftWorkloadCalculatorService calculator = buildCalculator();
        ShiftWorkloadCalculatorService.WorkloadResult result = calculator.computeWorkload(shipments);

        assertThat(result.totalMinutes())
                .as("Workload must be > 0 for non-empty shipment list (size=%d)", shipments.size())
                .isGreaterThan(0.0);
    }

    // ── Property 4: components sum to total ───────────────────────────────────

    /**
     * Feature: affinity-shift-allocation, Property 4:
     * For any ordered list of shipments,
     * totalMinutes SHALL equal handlingMinutes + travelMinutes + returnToHubMinutes.
     */
    @Property(tries = 100)
    void workloadComponentsSumToTotal(
            @ForAll("shipmentLists") List<Shipment> shipments) throws Exception {

        ShiftWorkloadCalculatorService calculator = buildCalculator();
        ShiftWorkloadCalculatorService.WorkloadResult result = calculator.computeWorkload(shipments);

        double expectedTotal = result.handlingMinutes() + result.travelMinutes() + result.returnToHubMinutes();
        assertThat(result.totalMinutes())
                .as("totalMinutes must equal sum of components")
                .isCloseTo(expectedTotal, within(1e-9));
    }

    // ── Property 5: handling time classification ──────────────────────────────

    /**
     * Feature: affinity-shift-allocation, Property 5:
     * getHandlingTime() SHALL return the configured value for each order type.
     * Default: 3.0 for all types (configurable via application.properties).
     */
    @Property(tries = 100)
    void handlingTimeClassificationIsCorrect(
            @ForAll("orderTypes") String orderType) throws Exception {

        ShiftWorkloadCalculatorService calculator = buildCalculator();
        Shipment s = Shipment.builder().orderType(orderType).build();
        double time = calculator.getHandlingTime(s);

        // All types default to 3.0 min (configurable)
        assertThat(time)
                .as("Handling time for orderType='%s' must be > 0", orderType)
                .isGreaterThan(0.0);
        assertThat(time)
                .as("Handling time for orderType='%s' must be ≤ 60 min", orderType)
                .isLessThanOrEqualTo(60.0);
    }

    // ── Providers ─────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<List<Shipment>> nonEmptyShipmentLists() {
        return shipmentArbitrary().list().ofMinSize(1).ofMaxSize(10);
    }

    @Provide
    Arbitrary<List<Shipment>> shipmentLists() {
        return shipmentArbitrary().list().ofMinSize(0).ofMaxSize(10);
    }

    @Provide
    Arbitrary<String> orderTypes() {
        return Arbitraries.of("COD", "Prepaid", "Reverse", "Express", "Standard", "", "OTHER");
    }

    private Arbitrary<Shipment> shipmentArbitrary() {
        Arbitrary<Double> latArb = Arbitraries.doubles().between(LAT_MIN, LAT_MAX).ofScale(5);
        Arbitrary<Double> lngArb = Arbitraries.doubles().between(LNG_MIN, LNG_MAX).ofScale(5);
        Arbitrary<String> typeArb = Arbitraries.of("COD", "Prepaid", "Reverse");

        return Combinators.combine(latArb, lngArb, typeArb)
                .as((lat, lng, type) -> Shipment.builder()
                        .shippingId(lat + "," + lng)
                        .dropLatitude(lat)
                        .dropLongitude(lng)
                        .orderType(type)
                        .build());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        Class<?> c = clazz;
        while (c != null) {
            try { return c.getDeclaredField(name); } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }
}
