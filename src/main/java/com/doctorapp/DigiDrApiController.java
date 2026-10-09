package com.doctorapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bridge between the admin page and the DigiDr API.
 *
 *   GET  /api/specialities -> https://digidrapi.digidr.app/api/Medical_Specility/dropdown
 *   POST /api/upload-csv   -> https://digidrapi.digidr.app/api/Trending/upload-csv   (multipart field: "file")
 *
 * The browser talks to THIS server, and this server talks to DigiDr. That avoids CORS problems
 * and keeps the DigiDr URL (and any token you add later) out of the page source.
 */
@RestController
public class DigiDrApiController {

    private static final Logger log = LoggerFactory.getLogger(DigiDrApiController.class);

    private static final String DROPDOWN_URL = "https://digidrapi.digidr.app/api/Medical_Specility/dropdown";
    private static final String UPLOAD_URL = "https://digidrapi.digidr.app/api/Trending/upload-csv";
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestTemplate rest;

    public DigiDrApiController() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(60_000);
        this.rest = new RestTemplate(factory);
    }

    /** Returns the DigiDr response untouched: [{"id":2,"speciality":"Cardiology"}, ...] */
    @GetMapping("/api/specialities")
    public ResponseEntity<Object> specialities() {
        try {
            String json = rest.getForObject(DROPDOWN_URL, String.class);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json == null ? "[]" : json);
        } catch (Exception e) {
            log.warn("Speciality dropdown failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(result("ERROR", "Could not load specialities: " + e.getMessage(), null));
        }
    }

    /** Forwards the chosen CSV file to DigiDr as multipart/form-data with the parameter name "file". */
    @PostMapping("/api/upload-csv")
    public ResponseEntity<Map<String, Object>> uploadCsv(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(result("ERROR", "Please choose a CSV file.", null));
        }
        String original = file.getOriginalFilename();
        final String filename = (original == null || original.trim().isEmpty()) ? "upload.csv" : original;
        if (!filename.toLowerCase().endsWith(".csv")) {
            return ResponseEntity.badRequest().body(result("ERROR", "Only .csv files are accepted.", null));
        }
        if (file.getSize() > MAX_BYTES) {
            return ResponseEntity.badRequest().body(result("ERROR", "File size exceeds the 5 MB limit.", null));
        }

        try {
            ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
                @Override
                public String getFilename() {
                    return filename;           // without this, the part is sent without a file name
                }
            };

            HttpHeaders partHeaders = new HttpHeaders();
            partHeaders.setContentType(MediaType.parseMediaType("text/csv"));

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new HttpEntity<>(resource, partHeaders));     // parameter name = "file"

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            // If DigiDr later needs a token:  headers.setBearerAuth("YOUR_TOKEN");

            ResponseEntity<String> resp = rest.postForEntity(UPLOAD_URL, new HttpEntity<>(body, headers), String.class);
            log.info("CSV '{}' uploaded to DigiDr, HTTP {}", filename, resp.getStatusCode());
            return ResponseEntity.ok(result("OK", "CSV uploaded to DigiDr successfully.", resp.getBody()));

        } catch (HttpStatusCodeException e) {
            String upstream = e.getResponseBodyAsString();
            log.warn("DigiDr rejected the upload: {} {}", e.getStatusText(), upstream);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(result("ERROR",
                    "DigiDr could not save this file (HTTP " + e.getStatusCode().value() + "): " + readableError(upstream), null));
        } catch (Exception e) {
            log.error("CSV upload failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(result("ERROR", "Upload failed: " + e.getMessage(), null));
        }
    }

    /** DigiDr answers {"success":false,"message":"...","error":"..."}: show message + error, not raw JSON. */
    private static String readableError(String body) {
        if (body == null || body.trim().isEmpty()) return "no details were returned.";
        try {
            JsonNode n = MAPPER.readTree(body);
            String text = (n.path("message").asText("") + " " + n.path("error").asText("")).trim();
            if (!text.isEmpty()) return text;
        } catch (Exception ignored) { /* not JSON */ }
        return body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }

    private static Map<String, Object> result(String status, String message, String apiResponse) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("message", message);
        if (apiResponse != null) m.put("apiResponse", apiResponse);
        return m;
    }
}