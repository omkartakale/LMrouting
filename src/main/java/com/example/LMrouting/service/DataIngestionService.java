package com.example.LMrouting.service;

import com.example.LMrouting.store.InMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;

/**
 * Startup service — seeds SR registry from config.
 * No longer loads hardcoded sample data. Data comes from CSV upload.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DataIngestionService implements CommandLineRunner {

    private final AttendanceManagerService attendanceManagerService;

    @Value("${hub.name:PNQ HDP}")
    private String hubName;

    @Override
    public void run(String... args) {
        // Seed SR registry from application.properties
        attendanceManagerService.ensureSrRegistrySeeded();
        log.info("DataIngestionService: SR registry seeded. Upload a CSV file to load shipment data.");
        log.info("DataIngestionService: POST /api/csv/upload with multipart file field 'file'");
    }
}
