package com.proxy.backend;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootApplication
public class BackendApplication {

    public static void main(String[] args) throws Exception {
        Files.createDirectories(Path.of("data"));
        SpringApplication.run(BackendApplication.class, args);
    }

    @Bean
    CommandLineRunner walCheck(JdbcTemplate jdbc) {
        return args -> {
            String mode = jdbc.queryForObject("PRAGMA journal_mode", String.class);
            System.out.println("[DB] journal_mode = " + mode);   // phải in ra: wal
        };
    }
}