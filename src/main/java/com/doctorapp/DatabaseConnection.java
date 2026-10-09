package com.doctorapp;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DatabaseConnection {
    private static final String URL = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/doctor_content_db");
    private static final String USER = System.getenv().getOrDefault("DB_USER", "doctor_app");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD", "ChangeThisPassword");

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }
}