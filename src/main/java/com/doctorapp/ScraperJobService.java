package com.doctorapp;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class ScraperJobService {

    @Async
    public void startBackgroundScrape(List<RssFeed> feeds) {
        System.out.println("🚀 Background Scrape Job Started for " + feeds.size() + " feeds...");

        // Pass the custom feeds to the scraper instead of hardcoded Bing URLs
        ScraperService.scrapeCustomFeeds(feeds);

        System.out.println("✅ Background Scrape Job Completed!");
    }
}