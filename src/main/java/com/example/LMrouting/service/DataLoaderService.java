package com.example.LMrouting.service;

import org.springframework.stereotype.Service;

/**
 * DataLoaderService — kept as empty stub for backward compatibility.
 * All data loading is now handled by CsvIngestionService via file upload.
 * The hardcoded sample data has been removed.
 */
@Service
public class DataLoaderService {
    // No-op — data comes from CSV upload via POST /api/csv/upload
}
