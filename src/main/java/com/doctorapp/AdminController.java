package com.doctorapp;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

@Controller
public class AdminController {

    @Autowired
    private ScraperJobService scraperJobService;

    /**
     * Serves the main Admin Dashboard UI.
     * Fetches configured RSS feeds from the database to display in the view.
     */
    @GetMapping("/admin")
    public String showAdminDashboard(Model model) {
        List<RssFeed> feeds = fetchAllFeeds();
        model.addAttribute("feeds", feeds);
        return "admin"; // Renders src/main/resources/templates/admin.html
    }

    /**
     * Handles adding a new RSS Feed to the database.
     */
    @PostMapping("/admin/add-feed")
    public String addFeed(@RequestParam String speciality,
                          @RequestParam String feedUrl,
                          RedirectAttributes redirectAttributes) {

        String sql = "INSERT INTO rss_feeds (speciality, feed_url) VALUES (?, ?) ON CONFLICT (feed_url) DO NOTHING";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, speciality);
            stmt.setString(2, feedUrl);
            int rowsAffected = stmt.executeUpdate();

            if (rowsAffected > 0) {
                redirectAttributes.addFlashAttribute("success", "RSS Feed Added Successfully!");
            } else {
                redirectAttributes.addFlashAttribute("error", "This RSS Feed URL already exists in the system.");
            }
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Database Error: " + e.getMessage());
        }
        return "redirect:/admin";
    }

    /**
     * Triggers the background job to scrape all saved RSS feeds.
     */
    @PostMapping("/admin/start-scrape")
    public String triggerScrape(RedirectAttributes redirectAttributes) {
        List<RssFeed> feeds = fetchAllFeeds();

        if (feeds.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "No RSS feeds configured. Please add one first!");
            return "redirect:/admin";
        }

        // Trigger the async background scraping job
        scraperJobService.startBackgroundScrape(feeds);

        redirectAttributes.addFlashAttribute("success", "Background Scraping Job Started! The server is currently pulling data from your RSS feeds.");
        return "redirect:/admin";
    }

    /**
     * Helper method to retrieve all RSS feeds from PostgreSQL.
     */
    private List<RssFeed> fetchAllFeeds() {
        List<RssFeed> feeds = new ArrayList<>();
        String query = "SELECT id, speciality, feed_url FROM rss_feeds ORDER BY id DESC";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                feeds.add(new RssFeed(
                        rs.getInt("id"),
                        rs.getString("speciality"),
                        rs.getString("feed_url")
                ));
            }
        } catch (Exception e) {
            System.err.println("Error fetching RSS feeds: " + e.getMessage());
        }
        return feeds;
    }
}