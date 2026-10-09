package com.doctorapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Smart, speciality-aware scraper.
 *
 * Paste ANY url and it works out what it is:
 *   - RSS / Atom feed        -> reads the feed items
 *   - JSON API               -> finds the array of objects
 *   - Listing page           -> finds headline cards (title + image + summary), WHO-style cards included
 *   - Single article page    -> scrapes that page
 *
 * Every article is then checked against the chosen speciality. The speciality names come from the
 * DigiDr dropdown API (https://digidrapi.digidr.app/api/Medical_Specility/dropdown):
 *   - "All"                                       -> everything is kept, each article is tagged with the speciality it is about
 *   - a speciality (Cardiology, Oncologist, ...)  -> only matching articles are kept; if none match, the result is NO_MATCH
 *                                                    with a message listing what the page does contain
 *   - "General Physician" / "General Medicine"
 *     / "General Health"                          -> everything is kept and tagged with the chosen name
 *
 * Speed: article pages are downloaded in PARALLEL (plain HTTP). Headless Chromium (Playwright)
 * is only a limited fallback (max BROWSER_FALLBACKS pages per request) and always runs on the ONE
 * worker thread, because Playwright for Java is not thread-safe.
 *
 * FIX NOTES (content preview):
 *   - findContent() no longer grabs the first <p> tags of the whole page (which were sidebar / sponsored
 *     blocks shared by every article). It now uses the per-article meta description first, then paragraphs
 *     from the real article container with sidebars / widgets / promos removed.
 *   - dropSharedContent() is a safety net: if several articles in one batch end up with identical text,
 *     that text is page boilerplate and is replaced by the article's own card / feed summary.
 *   - saveToDb() now UPDATES existing rows (ON CONFLICT DO UPDATE) so old bad previews get repaired on re-scrape.
 */
public class ScraperService {

    private static final Logger log = LoggerFactory.getLogger(ScraperService.class);

    public static final String ALL = "All";
    /** Tag used when "All" is chosen and an article does not clearly belong to any speciality. */
    public static final String GENERAL = "General Physician";

    /** Specialities that mean "keep everything" (compared in lower case). */
    private static final Set<String> CATCH_ALL = Set.of("general health", "general physician", "general medicine");

    private static boolean isCatchAll(String s) {
        return s != null && CATCH_ALL.contains(s.trim().toLowerCase());
    }

    /** Old names / other spellings -> the exact label used by the DigiDr dropdown API. */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("cardiologist", "Cardiology"),
            Map.entry("neurology", "Neorologist"),
            Map.entry("neurologist", "Neorologist"),
            Map.entry("oncology", "Oncologist"),
            Map.entry("pediatrics", "Pediatrician"),
            Map.entry("paediatrics", "Pediatrician"),
            Map.entry("paediatrician", "Pediatrician"),
            Map.entry("dentistry", "Dentist"),
            Map.entry("orthopedics", "Orthopedist"),
            Map.entry("orthopaedics", "Orthopedist"),
            Map.entry("urology", "Urologist"),
            Map.entry("ayurveda", "Ayurvedic"),
            Map.entry("intensive care", "Intensivist"));

    private static final int PAGE_TIMEOUT_MILLIS = 10_000;      // first page (listing / feed / api)
    private static final int ARTICLE_TIMEOUT_MILLIS = 8_000;    // each article page
    private static final int BROWSER_TIMEOUT_MILLIS = 15_000;
    private static final int MAX_TITLE_LENGTH = 250;
    private static final int MAX_PREVIEW_LENGTH = 800;
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 30;
    private static final int MAX_CANDIDATES = 40;               // pages we are willing to look at per request
    private static final int FETCH_BATCH = 8;                   // parallel downloads
    private static final int BROWSER_FALLBACKS = 3;             // headless pages per request
    private static final int MIN_LISTING_LINKS = 3;
    private static final long TOTAL_BUDGET_MILLIS = 70_000;
    private static final long REQUEST_LIMIT_SECONDS = 100;

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";
    private static final Pattern URL_PARAM = Pattern.compile("[?&]url=([^&]+)");
    private static final Pattern CSS_URL = Pattern.compile("url\\(\\s*['\"]?([^'\")]+)");
    private static final Pattern FEED_HREF = Pattern.compile(
            "(?i)(/feed/?$|/rss/?$|rss[^/]*\\.xml$|atom\\.xml$|feed\\.xml$|\\.rss$)");
    private static final Pattern JUNK_PATH = Pattern.compile(
            "(?i)(/(tag|tags|category|categories|author|authors|login|signin|signup|subscribe|privacy|terms|contact|about|search)(/|$)"
                    + "|/page/\\d+|\\.(jpg|jpeg|png|gif|svg|pdf|zip|mp4)$)");
    private static final Pattern ARTICLE_SEGMENT = Pattern.compile(
            "(?i)/(news|articles?|stor(y|ies)|posts?|blog|press|item|items|research|updates?|releases?|health)/");
    private static final Pattern BOILER_CLASS = Pattern.compile(
            "(?i)(^|[\\s_-])(nav|navbar|menu|footer|breadcrumb|breadcrumbs|cookie|consent|social|share|pagination|pager|skip)([\\s_-]|$)");
    private static final Pattern JUNK_IMAGE = Pattern.compile(
            "(?i)(favicon|sprite|avatar|spacer|blank\\.|1x1|pixel\\.|/logo|logo[-_.]|placeholder)");
    private static final Set<String> SOCIAL_HOSTS = Set.of(
            "facebook.com", "twitter.com", "x.com", "linkedin.com", "instagram.com",
            "youtube.com", "youtu.be", "whatsapp.com", "pinterest.com", "t.me");

    /**
     * Page furniture that must never be used as article text: sidebars, sponsored / promo blocks,
     * "related" and "trending" widgets, newsletter boxes, ads. Note: plain "header" is NOT listed on purpose,
     * because many sites put the article's own <h1> inside a <header> (we use the <h1> to find the article).
     */
    private static final String NOISE_SELECTOR =
            "script, style, noscript, nav, footer, aside, form, figure, "
                    + "[class*=sidebar], [class*=related], [class*=widget], [class*=sponsor], "
                    + "[class*=promo], [class*=advert], [class*=newsletter], [class*=trending], "
                    + "[class*=recommend], [class*=popular], [class*=banner], "
                    + "[id*=sidebar], [id*=related], [id*=widget], [id*=sponsor], [id*=advert]";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---- speciality dictionary (edit / extend freely) ----------------------------------------
    // Keys are the EXACT labels returned by the DigiDr dropdown API ("Neorologist" is spelled the way the API spells it).
    // Each value is a regex alternation; every term is matched at the START of a word.
    // General Physician / General Medicine have no pattern on purpose: they are catch-all (see CATCH_ALL).
    private static final Map<String, Pattern> SPECIALITY_PATTERNS = new LinkedHashMap<>();
    private static final Map<String, Pattern> CUSTOM_PATTERNS = new ConcurrentHashMap<>();

    static {
        addSpeciality("Cardiology",
                "cardio|cardiac|cardiom|carditis|heart(?!h)|coronary|hypertensi|blood pressure|arrhythm|atrial|myocard"
                        + "|angioplast|stent|cholesterol|statin|aort|vascular|pacemaker|atheroscl|ecg|ekg");
        addSpeciality("Neorologist",
                "neuro|brain(?!storm)|stroke|epilep|seizure|alzheimer|dementia|parkinson|migraine|multiple sclerosis"
                        + "|spinal cord|cerebr|concussion|headache|nerve");
        addSpeciality("Oncologist",
                "onco|cancer|tumou?r|chemotherap|radiotherap|radiation therapy|leuk(?:a)?emi|lymphoma|melanoma|carcinoma"
                        + "|metasta|malignan|mammogra|immunotherap|myeloma|sarcoma");
        addSpeciality("Pediatrician",
                "pediatr|paediatr|child|infant|newborn|neonat|adolescen|toddler|baby|babies|kids?\\b|teen(?:ager)?s?\\b");
        addSpeciality("Dentist",
                "dental|dentist|dentur|tooth|teeth|oral health|oral disease|orthodont|periodon|gum disease"
                        + "|cavit(?:y|ies)|endodont|enamel");
        addSpeciality("Orthopedist",
                "orthop|osteo|arthr|fractur|bone|joint|knee|ligament|tendon|musculoskelet|scolios");
        addSpeciality("Urologist",
                "urolog|urinar|kidney|bladder|prostat|renal|dialysis|incontinen|urethr");
        addSpeciality("ENT",
                "ent\\b|otolaryng|tonsil|sinus|throat|laryn|hearing|tinnitus|deaf|otitis|nasal|ear\\b|ears\\b");
        addSpeciality("Ayurvedic",
                "ayurved|panchakarma|dosha|herbal|naturopath|unani|siddha|homeopath");
        addSpeciality("Intensivist",
                "intensive care|icu\\b|critical care|critically ill|ventilator|sepsis|septic|life support|resuscitat");
    }

    private static void addSpeciality(String name, String alternation) {
        SPECIALITY_PATTERNS.put(name, Pattern.compile("\\b(?:" + alternation + ")", Pattern.CASE_INSENSITIVE));
    }

    // ---- threads ------------------------------------------------------------------------------
    // ONE worker for everything that touches Playwright (and the request flow itself).
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "scraper-worker");
        t.setDaemon(true);
        return t;
    });
    // Pool used only for plain-HTTP article downloads (Jsoup is thread-safe).
    private static final ExecutorService FETCH_POOL = Executors.newFixedThreadPool(FETCH_BATCH, r -> {
        Thread t = new Thread(r, "scraper-fetch");
        t.setDaemon(true);
        return t;
    });
    private static volatile long deadline = 0;

    private static Playwright playwright;
    private static Browser browser;
    private static BrowserContext context;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(ScraperService::closeBrowser));
    }

    // =====================================================================
    // PUBLIC API
    // =====================================================================

    /** What the controller sends back to the browser. */
    public static class ScrapeResult {
        public String status = "OK";             // OK | NO_MATCH | EMPTY | ERROR
        public String message;
        public String speciality;
        public int scanned;
        public List<Topic> topics = new ArrayList<>();
        public Map<String, Integer> detected = new LinkedHashMap<>();   // speciality -> articles seen
    }

    public static ScrapeResult error(String message) {
        ScrapeResult r = new ScrapeResult();
        r.status = "ERROR";
        r.message = message;
        return r;
    }

    /**
     * Called by AdminApiController.
     *
     * @param filter true  = keep only articles that match the speciality (user form)
     *               false = trust the speciality and keep everything (saved feeds)
     * @throws IllegalArgumentException for a bad URL, IllegalStateException for a real scraping failure
     */
    public static ScrapeResult scrape(String url, String speciality, int limit, boolean filter) {
        final String target = normalizeUrl(url);
        final String spec = normalizeSpeciality(speciality);
        final int max = Math.max(1, Math.min(limit <= 0 ? DEFAULT_LIMIT : limit, MAX_LIMIT));

        Future<ScrapeResult> future = WORKER.submit(() -> doScrape(target, spec, max, filter));
        try {
            return future.get(REQUEST_LIMIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("Scraping took longer than " + REQUEST_LIMIT_SECONDS + " seconds and was stopped.");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof IllegalArgumentException) throw (IllegalArgumentException) cause;
            log.error("Scrape failed for {}", url, cause);
            throw new IllegalStateException(cause.getMessage() == null ? cause.toString() : cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Scrape was interrupted.");
        }
    }

    /** Old signature, kept so other classes still compile. Trusts the speciality (no filtering). */
    public static List<Topic> scrapeUrlIntelligently(String url, String speciality) {
        return scrape(url, speciality, DEFAULT_LIMIT, false).topics;
    }

    /** Used by ScraperJobService: scrape every saved feed under the speciality the admin chose. */
    public static void scrapeCustomFeeds(List<RssFeed> customFeeds) {
        if (customFeeds == null) return;
        for (RssFeed feed : customFeeds) {
            try {
                ScrapeResult r = scrape(feed.feedUrl, feed.speciality, DEFAULT_LIMIT, false);
                log.info("Feed #{} ({}) -> {} topics saved", feed.id, feed.feedUrl, r.topics.size());
            } catch (Exception e) {
                log.warn("Feed #{} ({}) failed: {}", feed.id, feed.feedUrl, e.getMessage());
            }
        }
    }

    public static void fetchAndStoreTrendingTopics() {
        // TODO: paste your ORIGINAL method body here. It was empty in the code you sent me.
    }

    // =====================================================================
    // MAIN FLOW (runs on the worker thread)
    // =====================================================================

    private static ScrapeResult doScrape(String target, String spec, int limit, boolean filter) {
        deadline = System.currentTimeMillis() + TOTAL_BUDGET_MILLIS;
        log.info(">>> Smart analyzing URL: {} (speciality={}, limit={})", target, spec, limit);

        if (!isSafeHost(target)) {
            throw new IllegalArgumentException("That address could not be resolved or points to a private network, so it was not fetched.");
        }

        org.jsoup.Connection.Response raw = fetchRaw(target, PAGE_TIMEOUT_MILLIS);
        String body = raw == null ? null : raw.body();

        if (body != null && looksLikeFeed(body)) {
            log.info("  Detected RSS/Atom feed");
            Document feedDoc = Jsoup.parse(body, raw.url().toString(), Parser.xmlParser());
            return process(feedCandidates(feedDoc), spec, limit, filter);
        }
        if (body != null && looksLikeJson(body)) {
            log.info("  Detected JSON API");
            return process(jsonCandidates(body, raw.url().toString()), spec, limit, filter);
        }
        return scrapeHtml(target, raw, spec, limit, filter);
    }

    // ---------------------------------------------------------------------
    // CANDIDATES -> ARTICLES -> SPECIALITY FILTER -> DB
    // ---------------------------------------------------------------------

    /** One page we might scrape, plus whatever the listing/feed already told us about it. */
    private static class Candidate {
        final String url;
        final String title;
        final boolean titleReliable;   // true when it is a real headline (feed/json/heading), not raw link text
        final String snippet;
        final String image;
        final boolean prefetched;      // the page itself is already downloaded; do not fetch again

        Candidate(String url, String title, boolean titleReliable, String snippet, String image, boolean prefetched) {
            this.url = url;
            this.title = title;
            this.titleReliable = titleReliable;
            this.snippet = snippet;
            this.image = image;
            this.prefetched = prefetched;
        }
    }

    private static class Resolved {
        final String url;
        final String title;
        final String image;
        final String content;

        Resolved(String url, String title, String image, String content) {
            this.url = url;
            this.title = title;
            this.image = image;
            this.content = content;
        }
    }

    private static ScrapeResult process(List<Candidate> all, String spec, int limit, boolean filter) {
        ScrapeResult result = new ScrapeResult();
        result.speciality = spec;

        boolean everything = ALL.equals(spec);
        boolean catchAll = isCatchAll(spec);
        boolean strict = filter && !everything && !catchAll;

        List<Candidate> cands = new ArrayList<>(all);
        if (strict) {
            // Items whose own headline/summary already match go first -> fewer pages to download.
            cands.sort(Comparator.comparingInt((Candidate c) -> matchesSpeciality(spec, c.title, c.snippet) ? 0 : 1));
        }

        int[] browserBudget = {BROWSER_FALLBACKS};
        for (int i = 0; i < cands.size() && result.topics.size() < limit && !timeUp(); i += FETCH_BATCH) {
            List<Candidate> batch = cands.subList(i, Math.min(i + FETCH_BATCH, cands.size()));
            for (Resolved r : resolveBatch(batch, browserBudget)) {
                if (result.topics.size() >= limit) break;
                if (r.title == null || r.title.trim().isEmpty()) continue;

                result.scanned++;
                String detected = detectSpeciality(r.title, r.content);
                result.detected.merge(detected, 1, Integer::sum);

                String finalSpec;
                if (everything) finalSpec = detected;
                else if (catchAll || !filter) finalSpec = spec;
                else if (matchesSpeciality(spec, r.title, r.content)) finalSpec = spec;
                else continue;                                   // not this speciality -> skip

                addTopic(result.topics, r.title, r.url, finalSpec, r.image, r.content);
            }
        }

        if (!result.topics.isEmpty()) {
            result.status = "OK";
            result.message = "Found " + result.topics.size() + " article(s)"
                    + (strict ? " about " + spec : "") + " (scanned " + result.scanned + ").";
        } else if (strict && result.scanned > 0) {
            result.status = "NO_MATCH";
            result.message = "No " + spec + " content found at this URL. Scanned " + result.scanned
                    + " article(s), found: " + breakdown(result.detected)
                    + ". Choose \"All Specialities\" to scrape everything from this page.";
        } else {
            result.status = "EMPTY";
            result.message = "Could not extract any articles from this URL (blocked, empty, or not readable).";
        }
        log.info("  Result: {} - {}", result.status, result.message);
        return result;
    }

    /** Downloads a batch of article pages in parallel, then fills gaps from the card/feed hints. */
    private static List<Resolved> resolveBatch(List<Candidate> batch, int[] browserBudget) {
        List<CompletableFuture<ArticleData>> futures = new ArrayList<>();
        for (Candidate c : batch) {
            if (c.prefetched) futures.add(CompletableFuture.completedFuture(ArticleData.EMPTY));
            else futures.add(CompletableFuture.supplyAsync(() -> fromDocument(fetchStatic(c.url)), FETCH_POOL));
        }

        List<Resolved> out = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            Candidate c = batch.get(i);
            ArticleData d;
            try {
                long wait = Math.max(1000, Math.min(ARTICLE_TIMEOUT_MILLIS + 4000, deadline - System.currentTimeMillis()));
                d = futures.get(i).get(wait, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Scrape was interrupted.");
            } catch (Exception e) {
                futures.get(i).cancel(true);
                d = ArticleData.EMPTY;
            }

            // Plain HTTP got nothing at all (blocked / JS-only) and the listing gave no text or image -> real browser.
            boolean needsMore = d.isEmpty() && !c.prefetched && (c.snippet == null || c.image == null);
            if (needsMore && browserBudget[0] > 0 && !timeUp()) {
                browserBudget[0]--;
                d = d.orElse(fromDocument(renderWithBrowser(c.url, false)));
            }
            out.add(merge(c, d));
        }
        // Safety net: identical text on several articles = page boilerplate, not their own content.
        return dropSharedContent(batch, out);
    }

    private static Resolved merge(Candidate c, ArticleData d) {
        String title = (c.titleReliable && notBlank(c.title)) ? c.title : firstNonNull(d.title, c.title);
        String content = firstNonNull(d.content, c.snippet);
        String image = firstNonNull(d.image, c.image);
        return new Resolved(c.url, title, image, content);
    }

    /** Short, normalised fingerprint of a text, used to detect the same text repeated on several articles. */
    private static String contentKey(String s) {
        if (s == null) return "";
        String k = s.toLowerCase().replaceAll("\\s+", " ").trim();
        return k.length() > 80 ? k.substring(0, 80) : k;
    }

    /**
     * If several articles of one batch got identical text, it is page boilerplate (sidebar, promo, site-wide
     * description), not the article's own content. Replace it with the article's own card / feed summary
     * (or nothing, when the summary is the same boilerplate).
     */
    private static List<Resolved> dropSharedContent(List<Candidate> batch, List<Resolved> in) {
        Map<String, Integer> count = new HashMap<>();
        for (Resolved r : in) {
            String k = contentKey(r.content);
            if (!k.isEmpty()) count.merge(k, 1, Integer::sum);
        }
        List<Resolved> out = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            Resolved r = in.get(i);
            String k = contentKey(r.content);
            if (!k.isEmpty() && count.get(k) > 1) {
                String own = batch.get(i).snippet;                       // the card / feed summary
                String replacement = (own == null || contentKey(own).equals(k)) ? null : own;
                log.info("  Shared text detected on {} articles; replacing for {}", count.get(k), r.url);
                r = new Resolved(r.url, r.title, r.image, replacement);
            }
            out.add(r);
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // SPECIALITY MATCHING
    // ---------------------------------------------------------------------

    /** Maps whatever the form sent to the exact label we use (the DigiDr dropdown label). */
    private static String normalizeSpeciality(String s) {
        if (s == null || s.trim().isEmpty()) return ALL;
        String t = s.trim();
        if (t.equalsIgnoreCase("all") || t.toLowerCase().startsWith("all ")) return ALL;
        if (isCatchAll(t)) return t;                                   // keep the label the admin picked
        for (String known : SPECIALITY_PATTERNS.keySet()) {
            if (known.equalsIgnoreCase(t)) return known;
        }
        String alias = ALIASES.get(t.toLowerCase());
        return alias != null ? alias : t;
    }

    private static Pattern patternFor(String spec) {
        Pattern p = SPECIALITY_PATTERNS.get(spec);
        if (p != null) return p;
        if (spec == null || spec.trim().isEmpty() || ALL.equals(spec) || isCatchAll(spec)) return null;
        // Unknown speciality (e.g. a new one added to the API later): match on its word stem.
        String stem = spec.trim().toLowerCase();
        if (stem.length() > 6) stem = stem.substring(0, 6);
        final String regex = stem.length() < 4
                ? "\\b" + Pattern.quote(stem) + "\\b"        // very short names must match the whole word
                : "\\b" + Pattern.quote(stem);
        return CUSTOM_PATTERNS.computeIfAbsent(spec, k -> Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
    }

    private static int hits(Pattern p, String text) {
        if (p == null || text == null) return 0;
        Matcher m = p.matcher(text);
        int n = 0;
        while (n < 50 && m.find()) n++;
        return n;
    }

    /** Match = the speciality appears in the headline, or at least twice in the body text. */
    private static boolean matchesSpeciality(String spec, String title, String content) {
        Pattern p = patternFor(spec);
        if (p == null) return true;
        return hits(p, title) >= 1 || hits(p, content) >= 2;
    }

    /** Which known speciality is this article mostly about? Falls back to General Physician. */
    private static String detectSpeciality(String title, String content) {
        String best = GENERAL;
        int bestScore = 0;
        for (Map.Entry<String, Pattern> e : SPECIALITY_PATTERNS.entrySet()) {
            int t = hits(e.getValue(), title);
            int c = hits(e.getValue(), content);
            if (t < 1 && c < 2) continue;
            int score = t * 3 + c;
            if (score > bestScore) {
                bestScore = score;
                best = e.getKey();
            }
        }
        return best;
    }

    private static String breakdown(Map<String, Integer> detected) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : detected.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append(" (").append(e.getValue()).append(")");
        }
        return sb.length() == 0 ? "nothing readable" : sb.toString();
    }

    // ---------------------------------------------------------------------
    // RSS / ATOM
    // ---------------------------------------------------------------------

    private static List<Candidate> feedCandidates(Document feedDoc) {
        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Elements items = feedDoc.select("item, entry");
        log.info("  Feed has {} items", items.size());

        for (Element item : items) {
            if (out.size() >= MAX_CANDIDATES) break;
            try {
                String title = textOf(item, "title");

                String link = textOf(item, "link");
                if (link == null) {
                    Element l = item.selectFirst("link[rel=alternate][href]");
                    if (l == null) l = item.selectFirst("link[href]");
                    if (l != null) link = l.attr("href");
                }
                if (link == null) {
                    String guid = textOf(item, "guid");
                    if (guid != null && guid.startsWith("http")) link = guid;
                }
                String articleUrl = resolvePublisherUrl(link);
                if (title == null || articleUrl == null || !seen.add(articleUrl)) continue;

                String descHtml = textOf(item, "description, summary, content");
                String description = htmlToText(descHtml);

                String feedImage = firstNonNull(
                        attrOf(item, "media|content[url]", "url"),
                        attrOf(item, "media|thumbnail[url]", "url"),
                        attrOf(item, "enclosure[url]", "url"),
                        textOf(item, "News|Image"),
                        firstImgFromHtml(descHtml));

                out.add(new Candidate(articleUrl, title, true, description,
                        isUsableImage(feedImage) ? feedImage.trim() : null, false));
            } catch (Exception e) {
                log.warn("  Skipped one feed item: {}", e.getMessage());
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // JSON API
    // ---------------------------------------------------------------------

    private static List<Candidate> jsonCandidates(String body, String baseUrl) {
        List<Candidate> out = new ArrayList<>();
        JsonNode root;
        try {
            root = MAPPER.readTree(body.replace("\uFEFF", "").trim());
        } catch (Exception e) {
            log.warn("  Body looked like JSON but could not be parsed: {}", e.getMessage());
            return out;
        }
        JsonNode array = findObjectArray(root, 0);
        if (array == null) {
            log.warn("  JSON contains no array of objects to read");
            return out;
        }

        Set<String> seen = new HashSet<>();
        for (JsonNode node : array) {
            if (out.size() >= MAX_CANDIDATES) break;
            try {
                String title = jsonValue(node, "title", "name", "headline", "header");
                String itemUrl = resolveUrl(baseUrl, jsonValue(node, "ItemDefaultUrl", "url", "link", "href", "webUrl"));
                String summary = htmlToText(jsonValue(node, "summary", "description", "content", "body", "Tab1Summary", "abstract", "excerpt"));
                String image = resolveUrl(baseUrl, jsonValue(node, "urlToImage", "image", "imageUrl", "image_url",
                        "thumbnail", "thumbnailUrl", "featuredImage", "ImageUrl"));
                if (itemUrl == null || !seen.add(itemUrl)) continue;   // a unique source URL is needed for the DB
                out.add(new Candidate(itemUrl, title, title != null, summary, isUsableImage(image) ? image : null, false));
            } catch (Exception e) {
                log.warn("  Skipped one JSON item: {}", e.getMessage());
            }
        }
        return out;
    }

    private static JsonNode findObjectArray(JsonNode node, int depth) {
        if (node == null) return null;
        if (node.isArray()) return (node.size() > 0 && node.get(0).isObject()) ? node : null;
        if (!node.isObject() || depth >= 3) return null;
        for (JsonNode child : node) {
            if (child.isArray() && child.size() > 0 && child.get(0).isObject()) return child;
        }
        for (JsonNode child : node) {
            if (child.isObject()) {
                JsonNode found = findObjectArray(child, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Case-insensitive lookup of the first non-empty scalar value among several possible key names. */
    private static String jsonValue(JsonNode node, String... keys) {
        for (String key : keys) {
            for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
                String field = it.next();
                if (!field.equalsIgnoreCase(key)) continue;
                JsonNode v = node.get(field);
                if (v != null && v.isValueNode() && !v.isNull()) {
                    String s = v.asText().trim();
                    if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) return s;
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // HTML (listing page OR single article)
    // ---------------------------------------------------------------------

    private static ScrapeResult scrapeHtml(String target, org.jsoup.Connection.Response raw,
                                           String spec, int limit, boolean filter) {
        Document doc = raw == null ? null : Jsoup.parse(raw.body(), raw.url().toString());
        String base = raw == null ? target : raw.url().toString();

        boolean article = doc != null && isArticle(doc);
        List<Candidate> links = doc == null ? new ArrayList<>() : collectListingCandidates(doc, base);

        // Blocked, timed out, or an empty JavaScript shell -> one pass in a real browser.
        if (doc == null || (!article && links.size() < MIN_LISTING_LINKS)) {
            Document rendered = renderWithBrowser(target, true);
            if (rendered != null) {
                doc = rendered;
                base = rendered.location();
                article = isArticle(doc);
                links = collectListingCandidates(doc, base);
            }
        }
        if (doc == null) {
            throw new IllegalStateException("Could not load " + target
                    + " (blocked, offline, timed out, or the headless browser is not installed). Check the server log.");
        }

        if (!article && links.size() >= MIN_LISTING_LINKS) {
            log.info("  Listing page: found {} headline links", links.size());
            ScrapeResult r = process(links, spec, limit, filter);
            if (r.scanned > 0) return r;                        // OK or NO_MATCH: both are real answers
        }

        // No usable links: maybe the site advertises an RSS feed.
        if (!article) {
            String feedUrl = discoverFeedUrl(doc);
            if (feedUrl != null && !feedUrl.equals(target)) {
                log.info("  Trying discovered feed: {}", feedUrl);
                org.jsoup.Connection.Response feedResp = fetchRaw(feedUrl, PAGE_TIMEOUT_MILLIS);
                if (feedResp != null && looksLikeFeed(feedResp.body())) {
                    ScrapeResult r = process(feedCandidates(
                            Jsoup.parse(feedResp.body(), feedUrl, Parser.xmlParser())), spec, limit, filter);
                    if (r.scanned > 0) return r;
                }
            }
        }

        // Treat the page itself as the article.
        log.info("  Treating as a single page");
        ArticleData d = fromDocument(doc);
        String content = d.content;
        if (content == null && doc.body() != null) {
            String bodyText = doc.body().text();
            if (bodyText.length() > 20) content = bodyText;
        }
        String title = d.title != null ? d.title : hostOf(base);
        List<Candidate> single = new ArrayList<>();
        single.add(new Candidate(base, title, true, content, d.image, true));
        return process(single, spec, limit, filter);
    }

    private static class Link {
        final Element anchor;
        final String href;
        final String title;
        final boolean reliable;

        Link(Element anchor, String href, String title, boolean reliable) {
            this.anchor = anchor;
            this.href = href;
            this.title = title;
            this.reliable = reliable;
        }
    }

    /**
     * Finds headline cards on a listing page: any link that looks like an article URL, has a
     * headline-like text, and is NOT inside navigation / header / footer / sidebar.
     * For each one it also reads the surrounding card for an image and a short summary.
     */
    private static List<Candidate> collectListingCandidates(Document doc, String pageUrl) {
        String pageHost = hostOf(pageUrl);
        String pageKey = stripTrailingSlash(stripFragment(pageUrl));
        Map<String, Link> found = new LinkedHashMap<>();

        for (Element a : doc.select("a[href]")) {
            String href = stripFragment(a.absUrl("href"));
            if (href.isEmpty() || !isHttp(href)) continue;
            if (stripTrailingSlash(href).equals(pageKey) || isJunkLink(href)) continue;
            if (articleScore(href) < 2 || inBoilerplate(a)) continue;

            Link link = describeLink(a, href);
            if (link == null) continue;
            Link old = found.get(href);
            if (old == null || (!old.reliable && link.reliable)) found.put(href, link);
        }

        List<Link> same = new ArrayList<>();
        List<Link> other = new ArrayList<>();
        for (Link l : found.values()) {
            if (hostOf(l.href).equals(pageHost)) same.add(l);
            else other.add(l);
        }
        List<Link> chosen = new ArrayList<>(same);
        if (same.size() < MIN_LISTING_LINKS) chosen.addAll(other);

        List<Candidate> out = new ArrayList<>();
        for (Link l : chosen) {
            if (out.size() >= MAX_CANDIDATES) break;
            Element card = cardOf(l, found.keySet());
            out.add(new Candidate(l.href, l.title, l.reliable, snippetOf(card, l.title), imageOf(card, pageUrl), false));
        }
        return out;
    }

    private static Link describeLink(Element a, String href) {
        String title = null;
        boolean reliable = false;

        Element heading = a.selectFirst("h1, h2, h3, h4, h5, h6, [class*=title], [class*=heading], [class*=headline]");
        if (heading != null && heading.text().trim().length() >= 15) {
            title = heading.text().trim();
            reliable = true;
        }
        if (title == null) {
            String t = a.text().trim();
            if (t.isEmpty()) t = a.attr("title").trim();
            if (t.isEmpty()) t = a.attr("aria-label").trim();
            title = t;
            Element p = a.parent();
            reliable = p != null && p.tagName().matches("h[1-6]");
        }
        if (title.length() < 20 || title.split("\\s+").length < 3) return null;     // "Read more", "Home" ...
        return new Link(a, href, title, reliable);
    }

    /** Walks up from the headline link to the smallest container that holds only this one article. */
    private static Element cardOf(Link l, Set<String> candidateHrefs) {
        Element card = l.anchor;
        for (int i = 0; i < 5; i++) {
            Element p = card.parent();
            if (p == null || p.tagName().equals("body") || p.tagName().equals("html")) break;
            boolean shared = false;
            for (Element x : p.select("a[href]")) {
                String h = stripFragment(x.absUrl("href"));
                if (!h.equals(l.href) && candidateHrefs.contains(h)) {
                    shared = true;
                    break;
                }
            }
            if (shared) break;
            card = p;
        }
        return card;
    }

    private static String snippetOf(Element card, String title) {
        for (Element p : card.select("p")) {
            String t = p.text().trim();
            if (t.length() >= 40 && !t.equals(title) && !title.contains(t)) return t;
        }
        return null;
    }

    private static String imageOf(Element card, String pageUrl) {
        for (Element img : card.select("img, source")) {
            for (String attr : new String[]{"data-src", "data-original", "data-lazy-src", "src", "data-srcset", "srcset"}) {
                String v = img.attr(attr).trim();
                if (v.isEmpty()) continue;
                if (attr.endsWith("srcset")) v = v.split("[\\s,]+")[0];
                String abs = resolveUrl(pageUrl, v);
                if (isUsableImage(abs)) return abs;
            }
        }
        for (Element el : card.select("[style*=background]")) {          // CSS background-image cards
            Matcher m = CSS_URL.matcher(el.attr("style"));
            if (m.find()) {
                String abs = resolveUrl(pageUrl, m.group(1));
                if (isUsableImage(abs)) return abs;
            }
        }
        return null;
    }

    /** True when the link sits in a nav bar, footer, sidebar, cookie banner, breadcrumb, etc. */
    private static boolean inBoilerplate(Element a) {
        boolean insideArticle = false;
        boolean insideHeaderTag = false;
        for (Element p = a.parent(); p != null; p = p.parent()) {
            String tag = p.tagName();
            if (tag.equals("article")) insideArticle = true;
            if (tag.equals("nav") || tag.equals("footer") || tag.equals("aside")) return true;
            if (tag.equals("header")) insideHeaderTag = true;
            if ("navigation".equals(p.attr("role"))) return true;
            if (tag.equals("body") || tag.equals("html") || tag.equals("main")) continue;
            if (BOILER_CLASS.matcher(p.className() + " " + p.id()).find()) return true;
        }
        return insideHeaderTag && !insideArticle;
    }

    /** Rough "does this URL look like an article, not a menu page" score. */
    private static int articleScore(String href) {
        String path;
        try {
            path = new URI(href).getPath();
        } catch (Exception e) {
            return 0;
        }
        if (path == null || path.length() <= 1) return 0;

        String[] segs = path.replaceAll("^/|/$", "").split("/");
        String last = segs[segs.length - 1].replaceAll("(?i)\\.(html?|php|aspx?)$", "");
        long hyphens = last.chars().filter(ch -> ch == '-').count();

        int score = 0;
        if (segs.length >= 2) score++;
        if (hyphens >= 3 || last.length() >= 25) score += 2;
        else if (hyphens >= 1 && last.length() >= 10) score += 1;
        if (path.matches(".*/(19|20)\\d\\d([/-]).*") || last.matches(".*\\d{2}-\\d{2}-\\d{4}.*")) score++;
        if (last.matches(".*\\d{5,}.*")) score++;
        if (ARTICLE_SEGMENT.matcher(path).find()) score++;
        if (hyphens == 0 && last.length() <= 14 && !last.matches(".*\\d.*")) score -= 2;
        return score;
    }

    private static boolean isArticle(Document doc) {
        Element og = doc.selectFirst("meta[property=og:type]");
        if (og != null && "article".equalsIgnoreCase(og.attr("content").trim())) return true;
        return doc.select("article").size() == 1 && doc.select("article p").size() >= 3;
    }

    private static String discoverFeedUrl(Document doc) {
        Element l = doc.selectFirst("link[rel=alternate][type*=rss], link[rel=alternate][type*=atom]");
        if (l != null) {
            String href = l.absUrl("href");
            if (!href.isEmpty()) return href;
        }
        for (Element a : doc.select("a[href]")) {
            String href = a.absUrl("href");
            if (!href.isEmpty() && FEED_HREF.matcher(href).find()) return href;
        }
        return null;
    }

    // =====================================================================
    // ARTICLE EXTRACTION
    // =====================================================================

    private static class ArticleData {
        static final ArticleData EMPTY = new ArticleData(null, null, null);

        final String title;
        final String image;
        final String content;

        ArticleData(String title, String image, String content) {
            this.title = title;
            this.image = image;
            this.content = content;
        }

        boolean isEmpty() {
            return title == null && image == null && content == null;
        }

        /** Keep my values, fill the gaps from the other one. */
        ArticleData orElse(ArticleData o) {
            return new ArticleData(
                    title != null ? title : o.title,
                    image != null ? image : o.image,
                    content != null ? content : o.content);
        }
    }

    private static ArticleData fromDocument(Document doc) {
        if (doc == null || isBlockedPage(doc)) return ArticleData.EMPTY;

        String title = null;
        Element t = doc.selectFirst("meta[property=og:title], meta[name=twitter:title]");
        if (t != null && !t.attr("content").trim().isEmpty()) title = t.attr("content").trim();
        else if (!doc.title().trim().isEmpty()) title = doc.title().trim();
        else {
            Element h1 = doc.selectFirst("h1");
            if (h1 != null && !h1.text().trim().isEmpty()) title = h1.text().trim();
        }
        Element site = doc.selectFirst("meta[property=og:site_name]");
        title = stripSiteName(title, site == null ? null : site.attr("content"), doc.location());

        return new ArticleData(title, findImage(doc), findContent(doc));
    }

    /** "Heart disease news | WHO" -> "Heart disease news" (only when the suffix really is the site name). */
    private static String stripSiteName(String title, String siteName, String pageUrl) {
        if (title == null) return null;
        String host = hostOf(pageUrl);
        String hostLabel = host.contains(".") ? host.substring(0, host.indexOf('.')) : host;
        String site = alnum(siteName);
        String hl = alnum(hostLabel);
        for (String sep : new String[]{" | ", " - ", " \u2013 ", " \u2014 ", " \u00B7 ", " :: "}) {
            int i = title.lastIndexOf(sep);
            if (i < 10) continue;
            String suffix = alnum(title.substring(i + sep.length()));
            if (suffix.length() < 3) continue;
            boolean isSite = (!site.isEmpty() && (site.contains(suffix) || suffix.contains(site)))
                    || (!hl.isEmpty() && (hl.contains(suffix) || suffix.contains(hl)));
            if (isSite) return title.substring(0, i).trim();
        }
        return title;
    }

    private static String alnum(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private static boolean isBlockedPage(Document doc) {
        String t = doc.title().toLowerCase();
        return t.contains("just a moment") || t.contains("access denied") || t.contains("attention required")
                || t.contains("are you a robot") || t.contains("403 forbidden") || t.startsWith("404")
                || t.contains("page not found") || t.contains("verify you are human") || t.contains("captcha");
    }

    private static String findImage(Document doc) {
        String[] metaSelectors = {
                "meta[property=og:image]", "meta[property=og:image:secure_url]",
                "meta[name=twitter:image]", "meta[name=twitter:image:src]", "meta[itemprop=image]"};
        for (String sel : metaSelectors) {
            Element meta = doc.selectFirst(sel);
            if (meta != null) {
                String candidate = meta.absUrl("content");
                if (candidate.isEmpty()) candidate = meta.attr("content").trim();
                if (isUsableImage(candidate)) return candidate;
            }
        }
        String ld = imageFromJsonLd(doc);
        if (ld != null) return ld;

        Element link = doc.selectFirst("link[rel=image_src][href]");
        if (link != null && isUsableImage(link.absUrl("href"))) return link.absUrl("href");

        for (Element img : doc.select("article img, .article-content img, main img")) {
            for (String attr : new String[]{"src", "data-src", "data-original", "data-lazy-src"}) {
                String candidate = img.absUrl(attr);
                if (isUsableImage(candidate)) return candidate;
            }
        }
        return null;
    }

    private static String imageFromJsonLd(Document doc) {
        for (Element s : doc.select("script[type=application/ld+json]")) {
            try {
                String img = jsonLdImage(MAPPER.readTree(s.data()), 0);
                if (isUsableImage(img)) return img;
            } catch (Exception ignored) { /* malformed JSON-LD is common */ }
        }
        return null;
    }

    private static String jsonLdImage(JsonNode n, int depth) {
        if (n == null || depth > 4) return null;
        if (n.isArray()) {
            for (JsonNode c : n) {
                String r = jsonLdImage(c, depth + 1);
                if (r != null) return r;
            }
            return null;
        }
        if (n.isObject()) {
            JsonNode img = n.get("image");
            if (img != null) {
                String r = jsonLdImageValue(img);
                if (r != null) return r;
            }
            JsonNode graph = n.get("@graph");
            if (graph != null) return jsonLdImage(graph, depth + 1);
        }
        return null;
    }

    private static String jsonLdImageValue(JsonNode img) {
        if (img.isTextual()) return img.asText();
        if (img.isArray() && img.size() > 0) return jsonLdImageValue(img.get(0));
        if (img.isObject() && img.hasNonNull("url")) return img.get("url").asText();
        return null;
    }

    /**
     * Reads the text that belongs to THIS article only.
     *
     * Order of preference:
     *   1. og:description / meta description  (unique per article on news sites, never a sidebar)
     *   2. paragraphs inside the real article container, after removing sidebars / widgets / promos
     *   3. same as 2 but without removing anything (in case the cleanup removed too much)
     */
    private static String findContent(Document doc) {
        Element desc = doc.selectFirst("meta[property=og:description]");
        if (desc == null) desc = doc.selectFirst("meta[name=description]");
        if (desc != null) {
            String d = desc.attr("content").trim();
            if (d.length() >= 60) return d;
        }

        Document work = doc.clone();
        work.select(NOISE_SELECTOR).remove();
        String text = paragraphsFrom(articleRoot(work));
        if (text == null) text = paragraphsFrom(articleRoot(doc));
        return text;
    }

    /** Smallest container around the headline that holds at least 3 real paragraphs. */
    private static Element articleRoot(Document doc) {
        Element h1 = doc.selectFirst("h1");
        for (Element p = (h1 == null ? null : h1.parent()); p != null; p = p.parent()) {
            if (p.tagName().equals("body") || p.tagName().equals("html")) break;
            int n = 0;
            for (Element para : p.select("p")) {
                if (para.text().trim().length() > 60) n++;
            }
            if (n >= 3) return p;
        }
        Element a = doc.selectFirst("[itemprop=articleBody], article, main");
        return a != null ? a : doc.body();
    }

    /** Joins the real paragraphs of one container (up to ~1200 characters), skipping cookie / footer text. */
    private static String paragraphsFrom(Element root) {
        if (root == null) return null;
        StringBuilder sb = new StringBuilder();
        for (Element p : root.select("p")) {
            String text = p.text().trim();
            String lower = text.toLowerCase();
            if (text.length() > 40 && !lower.contains("cookie") && !lower.contains("subscribe")
                    && !lower.contains("all rights reserved") && !lower.contains("enable javascript")
                    && !lower.contains("cannot be found") && !lower.contains("moved or deleted")) {
                sb.append(text).append("\n\n");
                if (sb.length() >= 1200) break;
            }
        }
        String result = sb.toString().trim();
        return result.isEmpty() ? null : result;
    }

    private static boolean isUsableImage(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase();
        if (!u.startsWith("http")) return false;
        if (u.contains("googleusercontent.com") || u.contains("gstatic.com") || u.contains("news.google.")
                || u.endsWith(".svg") || u.endsWith(".ico")) return false;
        return !JUNK_IMAGE.matcher(u).find();
    }

    // =====================================================================
    // NETWORK
    // =====================================================================

    /**
     * Plain HTTP GET. Redirects are followed by hand so EVERY hop is checked against private networks.
     * Returns null (and logs why) on timeout, HTTP error, or a blocked address.
     */
    private static org.jsoup.Connection.Response fetchRaw(String startUrl, int timeoutMillis) {
        String url = startUrl;
        for (int hop = 0; hop < 6; hop++) {
            if (!isSafeHost(url)) {
                log.warn("  Blocked unsafe or unresolvable address: {}", url);
                return null;
            }
            try {
                org.jsoup.Connection.Response r = Jsoup.connect(url)
                        .userAgent(USER_AGENT)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.8,*/*;q=0.7")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .timeout(timeoutMillis)
                        .followRedirects(false)
                        .ignoreHttpErrors(true)
                        .ignoreContentType(true)
                        .maxBodySize(3 * 1024 * 1024)
                        .execute();
                int code = r.statusCode();
                if (code >= 300 && code < 400) {
                    String loc = r.header("Location");
                    if (loc == null || loc.isEmpty()) return null;
                    url = new URI(url).resolve(loc.trim()).toString();
                    continue;
                }
                if (code >= 400) {
                    log.warn("  HTTP {} for {}", code, url);
                    return null;
                }
                return r;
            } catch (Exception e) {
                log.warn("  Direct HTTP fetch failed for {} ({}).", url, firstLine(e));
                return null;
            }
        }
        log.warn("  Too many redirects for {}", startUrl);
        return null;
    }

    private static Document fetchStatic(String url) {
        org.jsoup.Connection.Response r = fetchRaw(url, ARTICLE_TIMEOUT_MILLIS);
        if (r == null) return null;
        String type = r.contentType() == null ? "" : r.contentType().toLowerCase();
        if (!type.contains("html") && !type.contains("xml")) return null;
        return Jsoup.parse(r.body(), r.url().toString());
    }

    /** SSRF guard: users can paste any URL, so never let the server call localhost / internal networks. */
    private static boolean isSafeHost(String url) {
        try {
            URI u = new URI(url);
            String scheme = u.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) return false;
            String host = u.getHost();
            if (host == null) return false;
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                        || a.isSiteLocalAddress() || a.isMulticastAddress()) return false;
                byte[] b = a.getAddress();
                if (b.length == 16 && (b[0] & 0xFE) == 0xFC) return false;                          // fc00::/7
                if (b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64) return false;     // 100.64.0.0/10
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Renders the page in headless Chromium. Returns null (and logs why) on any failure. */
    private static Document renderWithBrowser(String url, boolean scroll) {
        if (!isSafeHost(url)) return null;
        Page page = null;
        try {
            page = getContext().newPage();
            try {
                page.navigate(url, new Page.NavigateOptions()
                        .setTimeout(BROWSER_TIMEOUT_MILLIS).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            } catch (RuntimeException e) {
                log.warn("  Browser navigation problem for {} ({}). Using whatever DOM is available.", url, firstLine(e));
            }
            try {
                page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(4000));
            } catch (RuntimeException ignored) { /* many sites never go idle; that is fine */ }
            if (scroll) {                                   // trigger lazy-loaded cards
                try {
                    page.evaluate("window.scrollTo(0, document.body.scrollHeight)");
                    page.waitForTimeout(700);
                } catch (RuntimeException ignored) { }
            }
            return Jsoup.parse(page.content(), page.url());
        } catch (RuntimeException e) {
            log.error("  Headless browser failed for {}: {}", url, firstLine(e));
            return null;
        } finally {
            if (page != null) {
                try { page.close(); } catch (Exception ignored) { }
            }
        }
    }

    private static synchronized BrowserContext getContext() {
        if (browser == null || !browser.isConnected()) {
            closeBrowser();
            playwright = Playwright.create();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage")));
            context = browser.newContext(new Browser.NewContextOptions()
                    .setUserAgent(USER_AGENT).setLocale("en-US"));
            // Images/fonts/media are not downloaded (we only read their URLs from the HTML) -> much faster pages.
            context.route("**/*", route -> {
                String type = route.request().resourceType();
                if ("image".equals(type) || "font".equals(type) || "media".equals(type)) route.abort();
                else route.resume();
            });
        }
        return context;
    }

    private static synchronized void closeBrowser() {
        try { if (context != null) context.close(); } catch (Exception ignored) { }
        try { if (browser != null) browser.close(); } catch (Exception ignored) { }
        try { if (playwright != null) playwright.close(); } catch (Exception ignored) { }
        context = null;
        browser = null;
        playwright = null;
    }

    // =====================================================================
    // DATABASE
    // =====================================================================

    private static void addTopic(List<Topic> out, String title, String url, String speciality, String image, String content) {
        if (title == null || title.trim().isEmpty() || url == null) return;
        if (content == null || content.trim().isEmpty()) {
            if (image == null) return;      // nothing useful at all
            content = title;
        }
        for (Topic t : out) if (t.sourceUrl.equals(url)) return;

        title = title.trim();
        if (title.length() > MAX_TITLE_LENGTH) title = title.substring(0, MAX_TITLE_LENGTH);
        if (content.length() > MAX_PREVIEW_LENGTH) content = content.substring(0, MAX_PREVIEW_LENGTH) + "...";

        saveToDb(title, url, speciality, image, content);
        out.add(new Topic(title, url, speciality, image, content));
    }

    /**
     * Inserts the article, or UPDATES it when the same source_url already exists, so rows that were saved
     * earlier with a wrong (repeated) content preview are repaired the next time they are scraped.
     */
    private static void saveToDb(String title, String url, String speciality, String imageUrl, String content) {
        String insertSql = "INSERT INTO trending_topics (title, source_url, speciality, image_url, content_preview) "
                + "VALUES (?, ?, ?, ?, ?) "
                + "ON CONFLICT (source_url) DO UPDATE SET "
                + "title = EXCLUDED.title, "
                + "speciality = EXCLUDED.speciality, "
                + "image_url = EXCLUDED.image_url, "
                + "content_preview = EXCLUDED.content_preview";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(insertSql)) {
            stmt.setString(1, title);
            stmt.setString(2, url);
            stmt.setString(3, speciality);
            stmt.setString(4, imageUrl);
            stmt.setString(5, content);
            stmt.executeUpdate();
        } catch (Exception e) {
            // If you see this, fix the table (unique source_url, TEXT columns).
            log.error("DB save failed for {}: {}", url, e.getMessage());
        }
    }

    // =====================================================================
    // SMALL HELPERS
    // =====================================================================

    private static boolean timeUp() {
        return System.currentTimeMillis() > deadline;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String normalizeUrl(String raw) {
        if (raw == null || raw.trim().isEmpty()) throw new IllegalArgumentException("URL is empty.");
        String u = raw.trim();
        if (!u.matches("(?i)^https?://.*")) u = "https://" + u;
        try {
            if (new URI(u).getHost() == null) throw new IllegalArgumentException("Invalid URL: " + raw);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URL: " + raw);
        }
        return u;
    }

    private static boolean looksLikeFeed(String body) {
        if (body == null) return false;
        String head = body.substring(0, Math.min(body.length(), 1500)).toLowerCase();
        if (head.contains("<html") || head.contains("<!doctype html")) return false;
        return head.contains("<rss") || head.contains("<feed") || head.contains("<rdf:rdf");
    }

    private static boolean looksLikeJson(String body) {
        if (body == null) return false;
        String t = body.replace("\uFEFF", "").trim();
        return t.startsWith("{") || t.startsWith("[");
    }

    private static String textOf(Element parent, String cssQuery) {
        Element el = parent.selectFirst(cssQuery);
        if (el == null) return null;
        String t = el.text().trim();
        return t.isEmpty() ? null : t;
    }

    private static String attrOf(Element parent, String cssQuery, String attr) {
        try {
            Element el = parent.selectFirst(cssQuery);
            if (el == null) return null;
            String v = el.attr(attr).trim();
            return v.isEmpty() ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    private static String htmlToText(String html) {
        if (html == null) return null;
        String t = Jsoup.parse(html).text().trim();
        return t.isEmpty() ? null : t;
    }

    private static String firstImgFromHtml(String html) {
        if (html == null) return null;
        Element img = Jsoup.parse(html).selectFirst("img[src]");
        return img == null ? null : img.attr("src").trim();
    }

    private static String firstNonNull(String... values) {
        for (String v : values) if (v != null && !v.trim().isEmpty()) return v.trim();
        return null;
    }

    private static String resolvePublisherUrl(String rawLink) {
        if (rawLink == null || rawLink.trim().isEmpty()) return null;
        String link = rawLink.trim().replace("&amp;", "&");
        if (link.contains("bing.com")) {
            Matcher m = URL_PARAM.matcher(link);
            if (m.find()) link = URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
        }
        return isHttp(link) ? link : null;
    }

    private static String resolveUrl(String base, String ref) {
        if (ref == null || ref.trim().isEmpty()) return null;
        try {
            return new URI(base).resolve(ref.trim().replace(" ", "%20")).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isHttp(String u) {
        String l = u.toLowerCase();
        return l.startsWith("http://") || l.startsWith("https://");
    }

    private static String stripFragment(String u) {
        int i = u.indexOf('#');
        return i < 0 ? u : u.substring(0, i);
    }

    private static String stripTrailingSlash(String u) {
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    private static String hostOf(String url) {
        try {
            String h = new URI(url).getHost();
            if (h == null) return "";
            h = h.toLowerCase();
            return h.startsWith("www.") ? h.substring(4) : h;
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean isJunkLink(String href) {
        String host = hostOf(href);
        for (String s : SOCIAL_HOSTS) {
            if (host.equals(s) || host.endsWith("." + s)) return true;
        }
        try {
            String path = new URI(href).getPath();
            if (path == null || path.length() <= 1) return true;
            return JUNK_PATH.matcher(path).find();
        } catch (Exception e) {
            return true;
        }
    }

    private static String firstLine(Throwable e) {
        String m = e.getMessage();
        if (m == null) return e.getClass().getSimpleName();
        int nl = m.indexOf('\n');
        return nl > 0 ? m.substring(0, nl) : m;
    }
}