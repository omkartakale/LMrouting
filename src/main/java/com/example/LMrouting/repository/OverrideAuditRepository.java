package com.example.LMrouting.repository;

import com.example.LMrouting.model.OverrideAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface OverrideAuditRepository extends JpaRepository<OverrideAudit, Long> {

    List<OverrideAudit> findByAllocationDateOrderByOverriddenAtDesc(LocalDate allocationDate);

    List<OverrideAudit> findByAllocationDateAndUndoneOrderByOverriddenAtDesc(LocalDate allocationDate, boolean undone);
}
