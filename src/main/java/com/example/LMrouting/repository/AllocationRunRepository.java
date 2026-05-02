package com.example.LMrouting.repository;

import com.example.LMrouting.model.AllocationRun;
import com.example.LMrouting.model.AllocationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface AllocationRunRepository extends JpaRepository<AllocationRun, Long> {

    Optional<AllocationRun> findByAllocationDate(LocalDate allocationDate);

    List<AllocationRun> findByAllocationDateAndStatus(LocalDate allocationDate, AllocationStatus status);
}
