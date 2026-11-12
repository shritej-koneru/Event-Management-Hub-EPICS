package com.epics.bitchat.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Demo event catalog — Java mirror of `ai-service/app/events.py`. Venues reuse
 * the routing-graph names (cross-team contract). Replace with Person 2's
 * backend data when it lands.
 */
public final class DemoEventCatalog {

    private DemoEventCatalog() {
    }

    public static List<RecommendationEngine.Event> build(long nowMs) {
        List<RecommendationEngine.Event> events = new ArrayList<>();
        events.add(new RecommendationEngine.Event("evt-001", "AI & Robotics Workshop",
                "tech", new String[]{"ai", "robotics", "workshop", "tech"},
                nowMs + days(1), 120, 96, "Hall 1"));
        events.add(new RecommendationEngine.Event("evt-002", "Inter-College Hackathon Kickoff",
                "tech", new String[]{"hackathon", "coding", "tech", "ai"},
                nowMs + days(3), 300, 285, "Hall 2"));
        events.add(new RecommendationEngine.Event("evt-003", "Open Mic Night",
                "cultural", new String[]{"music", "performance", "cultural"},
                nowMs + days(5), 200, 44, "Cafeteria"));
        events.add(new RecommendationEngine.Event("evt-004", "Research Paper Reading Circle",
                "academic", new String[]{"research", "reading", "academic"},
                nowMs + days(2), 40, 12, "Library"));
        events.add(new RecommendationEngine.Event("evt-005", "Inter-Department Football Final",
                "sports", new String[]{"football", "sports", "tournament"},
                nowMs + days(4), 500, 430, "Sports Complex"));
        events.add(new RecommendationEngine.Event("evt-006", "Startup Pitch Showcase",
                "tech", new String[]{"startup", "pitch", "business", "tech"},
                nowMs + hours(6), 150, 140, "Lecture Block A"));
        events.add(new RecommendationEngine.Event("evt-007", "Campus Photography Walk",
                "cultural", new String[]{"photography", "walk", "cultural"},
                nowMs + days(6), 60, 18, "Main Gate"));
        events.add(new RecommendationEngine.Event("evt-008", "Placement Readiness Seminar",
                "academic", new String[]{"placement", "career", "seminar", "interview"},
                nowMs + days(2), 180, 175, "Lecture Block B"));
        return events;
    }

    private static long days(int n) {
        return n * 86_400_000L;
    }

    private static long hours(int n) {
        return n * 3_600_000L;
    }
}
