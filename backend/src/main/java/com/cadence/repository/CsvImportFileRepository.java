package com.cadence.repository;

import com.cadence.domain.CsvImportFile;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/** F42 raw-blob persistence. One doc per job (unique {jobId}); disposed on terminal/TTL. */
public interface CsvImportFileRepository extends MongoRepository<CsvImportFile, String> {

    Optional<CsvImportFile> findByJobId(String jobId);

    void deleteByJobId(String jobId);
}
