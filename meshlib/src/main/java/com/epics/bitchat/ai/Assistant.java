package com.epics.bitchat.ai;

import com.epics.bitchat.nav.Dijkstra;
import com.epics.bitchat.nav.Graph;
import com.epics.bitchat.nav.GraphNode;
import com.epics.bitchat.nav.Route;
import com.epics.bitchat.nav.RouteBuilder;
import com.epics.bitchat.nav.RouteStep;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Grounded FAQ + navigation assistant — Java mirror of
 * `ai-service/app/assistant.py`. Same FAQ knowledge base, same nav answers
 * computed from the routing graph.
 */
public final class Assistant {

    /** One organizer-defined FAQ entry. */
    public static final class FaqEntry {
        public final String question;
        public final String answer;
        public final String[] keywords;

        public FaqEntry(String question, String answer, String[] keywords) {
            this.question = question;
            this.answer = answer;
            this.keywords = keywords;
        }
    }

    /** answer() result: text plus meta for tests/debug output. */
    public static final class Answer {
        public final String text;
        public final String intent;
        public final String matchedFaq;

        Answer(String text, String intent, String matchedFaq) {
            this.text = text;
            this.intent = intent;
            this.matchedFaq = matchedFaq;
        }
    }

    private static final String[] NAV_WORDS = {"where ", "how do i get", "directions",
            "route", "navigate", "way to"};

    private final Graph graph;
    private final List<FaqEntry> faq;

    public Assistant(Graph graph) {
        this(graph, defaultFaq());
    }

    public Assistant(Graph graph, List<FaqEntry> faq) {
        this.graph = graph;
        this.faq = faq;
    }

    public Answer answer(String query, boolean accessibilityMode) {
        String text = (query == null ? "" : query.toLowerCase().trim());
        String intent = classify(text);

        FaqEntry best = bestFaq(text);
        if (best != null) {
            return new Answer(best.answer, intent, best.question);
        }

        List<GraphNode> venues = mentionedVenues(text);
        if ("navigate".equals(intent) && venues.size() >= 2) {
            return navAnswer(text, venues, accessibilityMode);
        }
        if (!venues.isEmpty()) {
            GraphNode node = venues.get(0);
            return new Answer(
                    node.getName() + " is on floor " + node.getFloor()
                            + " of the campus map. Say \"directions to "
                            + node.getName() + " from <where you are>\" for a route.",
                    intent, null);
        }
        return new Answer(
                "I can help with event info and campus navigation. Try \"Where is Hall 2?\" "
                        + "or \"How do I get to the Library from Hall 1?\"",
                intent, null);
    }

    public String classify(String text) {
        if (containsAny(text, NAV_WORDS)) {
            return "navigate";
        }
        if (containsAny(text, "register", "check in", "check-in")) {
            return "registration";
        }
        if (containsAny(text, "when ", "what time", "start")) {
            return "schedule";
        }
        return "unknown";
    }

    private FaqEntry bestFaq(String text) {
        Set<String> tokens = new LinkedHashSet<>(List.of(text.split("\\s+")));
        FaqEntry best = null;
        double bestScore = 0.0;
        for (FaqEntry entry : faq) {
            double score = 0.0;
            for (String kw : entry.keywords) {
                if (text.contains(kw)) {
                    score += 2.0;
                }
            }
            Set<String> qTokens = new LinkedHashSet<>(
                    List.of(entry.question.toLowerCase().split("\\s+")));
            if (!qTokens.isEmpty()) {
                Set<String> common = new LinkedHashSet<>(tokens);
                common.retainAll(qTokens);
                score += 3.0 * common.size() / qTokens.size();
            }
            if (score > bestScore) {
                best = entry;
                bestScore = score;
            }
        }
        return bestScore >= 2.0 ? best : null;
    }

    private List<GraphNode> mentionedVenues(String text) {
        List<GraphNode> found = new ArrayList<>();
        for (GraphNode node : graph.getNodes()) {
            String name = node.getName().toLowerCase();
            if (text.contains(name)) {
                found.add(node);
            }
        }
        found.sort((a, b) -> Integer.compare(
                text.indexOf(a.getName().toLowerCase()),
                text.indexOf(b.getName().toLowerCase())));
        return found;
    }

    private Answer navAnswer(String text, List<GraphNode> venues, boolean accessibilityMode) {
        // First mention before " from " is the destination; the one after is
        // the origin ("Library from Hall 1"); without " from ", the first
        // mention is the origin.
        String origin = venues.get(0).getName();
        String dest = venues.get(1).getName();
        if (text.contains(" from ")) {
            int fromPos = text.indexOf(" from ");
            for (GraphNode v : venues) {
                if (text.indexOf(v.getName().toLowerCase()) > fromPos) {
                    origin = v.getName();
                } else {
                    dest = v.getName();
                }
            }
        }

        List<GraphNode> path = Dijkstra.findShortestPath(graph, origin, dest, accessibilityMode);
        if (path.isEmpty()) {
            List<GraphNode> standard = Dijkstra.findShortestPath(graph, origin, dest);
            if (!standard.isEmpty()) {
                return new Answer(
                        "There is no step-free route from " + origin + " to " + dest
                                + "; the standard route uses stairs. Please ask a volunteer.",
                        "navigate", null);
            }
            return new Answer(
                    "Sorry, I couldn't find a route from " + origin + " to " + dest + ".",
                    "navigate", null);
        }

        Route route = RouteBuilder.build(graph, path, accessibilityMode);
        int mins = (int) Math.max(1, Math.round(route.getTotalSeconds() / 60.0));
        boolean floorChange = false;
        for (RouteStep step : route.getSteps()) {
            floorChange |= step.isFloorChange();
        }
        String note = floorChange ? " It includes a floor change." : "";
        return new Answer(
                "From " + origin + " to " + dest + ": about "
                        + (int) route.getTotalMeters() + " m, roughly " + mins
                        + " min walk." + note + " First step: \""
                        + route.getSteps().get(0).getText() + "\".",
                "navigate", null);
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    public static List<FaqEntry> defaultFaq() {
        List<FaqEntry> faq = new ArrayList<>();
        faq.add(new FaqEntry("Where is the registration desk?",
                "The registration desk is in the Admin Block lobby, open 8:00-17:00. "
                        + "Bring your college ID; your QR pass is generated right after check-in.",
                new String[]{"registration", "desk", "register"}));
        faq.add(new FaqEntry("Is the campus wheelchair accessible?",
                "Yes. Enable accessibility mode on any route and the navigation service "
                        + "will only use ramps, elevators and step-free corridors — stairs are "
                        + "excluded automatically.",
                new String[]{"wheelchair", "accessible", "accessibility", "ramp", "elevator", "stairs"}));
        faq.add(new FaqEntry("Where can I eat on campus?",
                "The Cafeteria serves meals all day, and food stalls open near the "
                        + "Sports Complex during major events.",
                new String[]{"eat", "food", "cafeteria", "lunch", "canteen", "hungry"}));
        faq.add(new FaqEntry("Where do I park?",
                "Visitor parking is at the Parking lot next to the Main Gate. Show your "
                        + "event QR at the barrier for free entry.",
                new String[]{"park", "parking", "car"}));
        faq.add(new FaqEntry("I lost something. What do I do?",
                "Report lost or found items at the Admin Block help desk or via the "
                        + "Lost & Found section in the app; staff will match reports for you.",
                new String[]{"lost", "found", "misplaced"}));
        faq.add(new FaqEntry("How do I check in to a session?",
                "Open My Events in the app and show the signed QR code at the session "
                        + "door. Scans are verified against your registration in real time.",
                new String[]{"check in", "check-in", "qr", "scan"}));
        return faq;
    }
}
