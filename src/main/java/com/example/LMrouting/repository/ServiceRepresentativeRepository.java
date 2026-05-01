package com.example.LMrouting.repository;

import com.example.LMrouting.model.ServiceRepresentative;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ServiceRepresentativeRepository extends JpaRepository<ServiceRepresentative, Long> {
    Optional<ServiceRepresentative> findBySrName(String srName);
}
