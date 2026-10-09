CREATE TABLE IF NOT EXISTS trending_topics (
    id              SERIAL PRIMARY KEY,
    title           TEXT NOT NULL,
    source_url      TEXT NOT NULL UNIQUE,
    speciality      TEXT,
    image_url       TEXT,
    content_preview TEXT,
    created_at      TIMESTAMP DEFAULT NOW()
);
