package com.cadence;

import io.mongock.runner.springboot.EnableMongock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableMongock
public class CadenceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CadenceApplication.class, args);
    }
}
