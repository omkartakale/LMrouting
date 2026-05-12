package com.example.LMrouting.service;

import com.example.LMrouting.model.Shipment;

import java.util.List;

/**
 * Strategy interface for route optimization in the time-based allocation pipeline.
 *
 * <p>Implementations are Spring-managed beans that hold their own dependencies
 * (e.g., TravelTimeCacheService, configuration properties) via constructor injection.
 *
 * <p>Available strategies:
 * <ul>
 *   <li><b>cluster-first</b> — Angular Sweep Partitioning + intra-cluster 2-opt (CFRS)</li>
 *   <li><b>or-tools</b> — Google OR-Tools VRP solver (optional, requires native library)</li>
 *   <li><b>legacy</b> — Nearest-neighbour + 2-opt (original behavior)</li>
 * </ul>
 *
 * <p>The active strategy is selected via the {@code allocation.route.optimizer.strategy}
 * configuration property.
 */
public interface RouteOptimizationStrategy {

    /**
     * Optimize the route for a list of shipments departing from the given hub coordinates.
     *
     * @param shipments the shipments to sequence into an optimized route
     * @param hubLat    hub latitude (route start and end point)
     * @param hubLng    hub longitude (route start and end point)
     * @return optimized shipment ordering (new list; input is not mutated)
     */
    List<Shipment> optimize(List<Shipment> shipments, double hubLat, double hubLng);
}
