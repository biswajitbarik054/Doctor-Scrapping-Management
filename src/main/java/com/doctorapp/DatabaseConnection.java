package com.doctorapp;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DatabaseConnection {
    private static final String URL = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://dpg-db4dltjncjis73cloaa0-a.singapore-postgres.render.com:5432/scrapper_fgh0");
    private static final String USER = System.getenv().getOrDefault("DB_USER", "scrapper_fgh0_user");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD", "NoYHYbilTyWNoB3OZFIvsQYQ7NWZmb2C");

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }
}