package com.example.LMrouting.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "service_representatives")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ServiceRepresentative {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sr_name", unique = true)
    private String srName;

    @Column(name = "shipment_count")
    private int shipmentCount;
}
