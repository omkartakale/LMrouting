package com.example.LMrouting.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(
    name = "sr_attendance",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_sr_attendance_sr_name_date",
        columnNames = {"sr_name", "attendance_date"}
    )
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SrAttendance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sr_name", nullable = false)
    private String srName;

    @Column(name = "attendance_date", nullable = false)
    private LocalDate attendanceDate;

    @Column(name = "is_present", nullable = false)
    private boolean isPresent;
}
