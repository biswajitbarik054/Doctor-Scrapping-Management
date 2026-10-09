package com.doctorapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.CommandLineRunner;
import org.springframework.scheduling.annotation.EnableAsync;

import java.sql.Connection;
import java.sql.Statement;

@SpringBootApplication
@EnableAsync // Enables background scraping jobs
public class Main {
    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }

    @Bean
    public CommandLineRunner runApp() {
        return args -> {
            System.out.println("Initializing Doctor Content App (Advanced UI Pattern)...");

            // 1. Setup File System
            FileStorageService.initializeSystem();

            // 2. Auto-create database tables if they don't exist
            try (Connection conn = DatabaseConnection.getConnection();
                 Statement stmt = conn.createStatement()) {

                // Create feeds table
                stmt.execute("CREATE TABLE IF NOT EXISTS rss_feeds (" +
                        "id SERIAL PRIMARY KEY, " +
                        "speciality VARCHAR(255), " +
                        "feed_url TEXT UNIQUE)");

                // Ensure trending topics table exists
                stmt.execute("CREATE TABLE IF NOT EXISTS trending_topics (" +
                        "id SERIAL PRIMARY KEY, " +
                        "title VARCHAR(255), " +
                        "source_url TEXT UNIQUE, " +
                        "speciality VARCHAR(255), " +
                        "image_url TEXT, " +
                        "content_preview TEXT)");
                System.out.println("Database schema verified.");
            } catch (Exception e) {
                System.err.println("DB Init Error: " + e.getMessage());
            }

            System.out.println("✅ Web Server Running! Go to: http://localhost:8080/admin");
        };
    }
}