package com.example.LMrouting.repository;

import com.example.LMrouting.model.SrAttendance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface SrAttendanceRepository extends JpaRepository<SrAttendance, Long> {

    Optional<SrAttendance> findBySrNameAndAttendanceDate(String srName, LocalDate attendanceDate);

    List<SrAttendance> findByAttendanceDate(LocalDate attendanceDate);

    List<SrAttendance> findBySrNameAndAttendanceDateAndIsPresent(String srName, LocalDate attendanceDate, boolean isPresent);
}
