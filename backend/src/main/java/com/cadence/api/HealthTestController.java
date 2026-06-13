package com.cadence.api;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("test")
public class HealthTestController {

    @GetMapping("/api/internal/slow")
    public ResponseEntity<String> slow() throws InterruptedException {
        Thread.sleep(5000);
        return ResponseEntity.ok("ok");
    }
}
