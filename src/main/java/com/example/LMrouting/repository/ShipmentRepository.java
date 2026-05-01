package com.example.LMrouting.repository;

import com.example.LMrouting.model.Shipment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    List<Shipment> findByAllocationDate(String allocationDate);

    List<Shipment> findByAssignedSr(String assignedSr);

    List<Shipment> findByAssignedSrOrderByRouteSequence(String assignedSr);

    @Query("SELECT DISTINCT s.allocationDate FROM Shipment s ORDER BY s.allocationDate")
    List<String> findDistinctAllocationDates();

    @Query("SELECT DISTINCT s.dropPincode FROM Shipment s WHERE s.allocationDate = ?1")
    List<String> findDistinctPincodesByDate(String date);

    @Query("SELECT DISTINCT s.assignedSr FROM Shipment s WHERE s.assignedSr IS NOT NULL")
    List<String> findDistinctAssignedSrs();

    @Query("SELECT DISTINCT s.srName FROM Shipment s WHERE s.srName IS NOT NULL AND s.srName != ''")
    List<String> findDistinctSrNames();

    long countByAllocationDate(String allocationDate);
}
