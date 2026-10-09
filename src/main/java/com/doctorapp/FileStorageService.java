package com.doctorapp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;

public class FileStorageService {

    private static final Path BASE_PATH = Paths.get(System.getProperty("user.home"), "DoctorContentApp_Data");

    public static void initializeSystem() {
        try {
            Path templatesDir = BASE_PATH.resolve("Templates");

            Files.createDirectories(templatesDir.resolve("Dental"));
            Files.createDirectories(templatesDir.resolve("Cardiology"));
            Files.createDirectories(templatesDir.resolve("General_Physician"));

            // Create operational folders
            Files.createDirectories(BASE_PATH.resolve("Temp"));
            Files.createDirectories(BASE_PATH.resolve("Logs"));

            System.out.println("Advanced file structure initialized at: " + BASE_PATH.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("System failed to initialize directories: " + e.getMessage());
        }
    }
    public static Path getDynamicOutputPath(String speciality) {
        LocalDate today = LocalDate.now();

        Path dynamicPath = BASE_PATH.resolve("Output")
                .resolve(String.valueOf(today.getYear()))
                .resolve(String.format("%02d", today.getMonthValue()))
                .resolve(speciality);

        try {
            Files.createDirectories(dynamicPath);
        } catch (Exception e) {
            System.err.println("Failed to create dynamic output path: " + e.getMessage());
        }

        return dynamicPath;
    }
}