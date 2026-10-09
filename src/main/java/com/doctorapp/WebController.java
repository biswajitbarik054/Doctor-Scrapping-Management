package com.doctorapp;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping; // <-- This is the corrected import

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

@Controller
public class WebController {

    @GetMapping("/")
    public String showHomePage(Model model) {
        List<Topic> topics = new ArrayList<>();

        // Fetch all saved articles from PostgreSQL
        String query = "SELECT title, source_url, speciality, image_url, content_preview FROM trending_topics ORDER BY speciality";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                topics.add(new Topic(
                        rs.getString("title"),
                        rs.getString("source_url"),
                        rs.getString("speciality"),
                        rs.getString("image_url"),
                        rs.getString("content_preview")
                ));
            }
        } catch (Exception e) {
            System.err.println("Database Error fetching topics: " + e.getMessage());
        }

        // Send the data to the HTML template
        model.addAttribute("topics", topics);
        return "index"; // Matches the index.html file name
    }
}