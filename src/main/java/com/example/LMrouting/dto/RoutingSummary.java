package com.example.LMrouting.dto;

import lombok.*;
import java.util.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoutingSummary {
    private String hubName;
    private double hubLatitude;
    private double hubLongitude;
    private String allocationDate;
    private int totalShipments;
    private int totalSRs;
    private List<String> pincodes;
    private List<SRSummary> srSummaries;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SRSummary {
        private String srName;
        private int shipmentCount;
        private List<String> pincodesCovered;
    }
}
