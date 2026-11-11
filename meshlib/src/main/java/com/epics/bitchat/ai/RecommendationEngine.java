package com.epics.bitchat.ai;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Event recommendation engine — Java mirror of `ai-service/app/recommendations.py`
 * so the on-device test UI and the server score identically.
 *
 * Hybrid score: interest-tag Jaccard similarity (0.6) + popularity (0.25) +
 * recency boost (0.15), with a multiplier when the category matches an
 * interest. Deterministic, no dependencies.
 */
public final class RecommendationEngine {

    public static final double W_INTEREST = 0.6;
    public static final double W_POPULARITY = 0.25;
    public static final double W_RECENCY = 0.15;
    public static final double CATEGORY_BOOST = 1.25;
    private static final long DAY_MS = 86_400_000L;

    private RecommendationEngine() {
    }

    /** Minimal event record (mirrors the demo catalog and Person 2's schema). */
    public static final class Event {
        public final String eventId;
        public final String title;
        public final String category;
        public final String[] tags;
        public final long startEpochMs;
        public final int capacity;
        public final int registered;
        public final String venue;

        public Event(String eventId, String title, String category, String[] tags,
                     long startEpochMs, int capacity, int registered, String venue) {
            this.eventId = eventId;
            this.title = title;
            this.category = category;
            this.tags = tags;
            this.startEpochMs = startEpochMs;
            this.capacity = capacity;
            this.registered = registered;
            this.venue = venue;
        }

        public double popularity() {
            return capacity <= 0 ? 0.0 : Math.min(1.0, (double) registered / capacity);
        }
    }

    /** One ranked result with the score breakdown for display. */
    public static final class Result {
        public final Event event;
        public final double score;
        public final double interest;
        public final double popularity;
        public final double recency;

        Result(Event event, double score, double interest, double popularity, double recency) {
            this.event = event;
            this.score = score;
            this.interest = interest;
            this.popularity = popularity;
            this.recency = recency;
        }
    }

    public static List<Result> recommend(List<Event> events, String[] interests,
                                         int limit, long nowMs) {
        Set<String> wanted = normalize(interests);
        List<Result> scored = new ArrayList<>();
        for (Event event : events) {
            double interest = jaccard(wanted, event.tags, event.category);
            if (!wanted.isEmpty() && wanted.contains(event.category.toLowerCase())) {
                interest = Math.min(1.0, interest * CATEGORY_BOOST);
            }
            double popularity = event.popularity();
            double recency = recencyBoost(event, nowMs);
            double score = W_INTEREST * interest + W_POPULARITY * popularity
                    + W_RECENCY * (recency - 1.0);
            scored.add(new Result(event, score, interest, popularity, recency));
        }
        scored.sort((a, b) -> {
            int byScore = Double.compare(b.score, a.score);
            return byScore != 0 ? byScore : a.event.eventId.compareTo(b.event.eventId);
        });
        return scored.subList(0, Math.min(limit, scored.size()));
    }

    private static Set<String> normalize(String[] raw) {
        Set<String> out = new HashSet<>();
        if (raw != null) {
            for (String tag : raw) {
                if (tag != null && !tag.trim().isEmpty()) {
                    out.add(tag.trim().toLowerCase());
                }
            }
        }
        return out;
    }

    private static double jaccard(Set<String> wanted, String[] tags, String category) {
        Set<String> offered = new HashSet<>();
        for (String tag : tags) {
            offered.add(tag.toLowerCase());
        }
        offered.add(category.toLowerCase());
        if (wanted.isEmpty() && offered.isEmpty()) {
            return 0.0;
        }
        Set<String> union = new HashSet<>(wanted);
        union.addAll(offered);
        Set<String> common = new HashSet<>(wanted);
        common.retainAll(offered);
        return union.isEmpty() ? 0.0 : (double) common.size() / union.size();
    }

    private static double recencyBoost(Event event, long nowMs) {
        double deltaDays = (event.startEpochMs - nowMs) / (double) DAY_MS;
        if (deltaDays <= 0) {
            return 2.0;
        }
        return 1.0 + Math.exp(-deltaDays / 2.0);
    }
}
