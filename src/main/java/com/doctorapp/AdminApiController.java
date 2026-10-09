package com.doctorapp;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminApiController {

    /**
     * Always answers with JSON: { status, message, speciality, scanned, topics[], detected{} }
     *   status = OK        -> topics found
     *   status = NO_MATCH  -> page was read, but nothing about the chosen speciality (message explains)
     *   status = EMPTY     -> nothing readable at that URL
     *   status = ERROR     -> bad URL / real failure (HTTP 400 or 500)
     */
    @PostMapping("/api/scrape-url")
    public ResponseEntity<ScraperService.ScrapeResult> scrapeCustomUrl(
            @RequestParam("url") String url,
            @RequestParam(value = "speciality", defaultValue = "All") String speciality,
            @RequestParam(value = "limit", defaultValue = "10") int limit) {

        try {
            return ResponseEntity.ok(ScraperService.scrape(url, speciality, limit, true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ScraperService.error(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ScraperService.error("An error occurred during scraping: " + e.getMessage()));
        }
    }
}