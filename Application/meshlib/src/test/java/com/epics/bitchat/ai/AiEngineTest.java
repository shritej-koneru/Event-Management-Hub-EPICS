package com.epics.bitchat.ai;

import com.epics.bitchat.nav.DemoGraphFactory;
import com.epics.bitchat.nav.Graph;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Mirrors the ai-service pytest coverage for the Java engine mirrors. */
public class AiEngineTest {

    @Test
    public void recommendRanksMatchingInterestsFirst() {
        List<RecommendationEngine.Event> events =
                DemoEventCatalog.build(1_000_000L);
        List<RecommendationEngine.Result> results =
                RecommendationEngine.recommend(events, new String[]{"tech", "ai"}, 5, 1_000_000L);
        assertEquals(5, results.size());
        assertEquals("tech", results.get(0).event.category);
        assertTrue("scores must descend",
                results.get(0).score >= results.get(results.size() - 1).score);
    }

    @Test
    public void recommendBreakdownComponentsPresent() {
        List<RecommendationEngine.Event> events =
                DemoEventCatalog.build(1_000_000L);
        RecommendationEngine.Result result = RecommendationEngine.recommend(
                events, new String[]{"sports"}, 1, 1_000_000L).get(0);
        // football final is the only sports event and is 90% full -> high popularity
        assertTrue(result.interest > 0.0);
        assertTrue(result.popularity > 0.5);
    }

    @Test
    public void assistantMatchesFaq() {
        Assistant assistant = new Assistant(DemoGraphFactory.buildCampusGraph());
        Assistant.Answer answer = assistant.answer("Where can I register for events?", false);
        assertTrue(answer.text.toLowerCase().contains("registration desk"));
        assertNotNull(answer.matchedFaq);
    }

    @Test
    public void assistantAnswersNavigationFromGraph() {
        Assistant assistant = new Assistant(DemoGraphFactory.buildCampusGraph());
        Assistant.Answer answer = assistant.answer(
                "How do I get to the Library from Hall 1?", false);
        assertEquals("navigate", answer.intent);
        assertTrue(answer.text.contains("Hall 1"));
        assertTrue(answer.text.contains("Library"));
        assertTrue(answer.text.contains("min walk"));
    }

    @Test
    public void assistantNotesFloorChange() {
        Assistant assistant = new Assistant(DemoGraphFactory.buildCampusGraph());
        Assistant.Answer answer = assistant.answer(
                "directions to Library from Main Gate", false);
        assertTrue(answer.text.toLowerCase().contains("floor change"));
    }

    @Test
    public void assistantAccessibleModeFindsStepFreeRoute() {
        Assistant assistant = new Assistant(DemoGraphFactory.buildCampusGraph());
        Assistant.Answer answer = assistant.answer(
                "directions to Library from Main Gate", true);
        // Every venue pair has a step-free route, so no refusal.
        assertTrue(!answer.text.toLowerCase().contains("no step-free"));
        assertTrue(answer.text.contains("min walk"));
    }

    @Test
    public void assistantFallsBackForUnknownQueries() {
        Assistant assistant = new Assistant(DemoGraphFactory.buildCampusGraph());
        Assistant.Answer answer = assistant.answer("what is the meaning of life", false);
        assertEquals("unknown", answer.intent);
        assertTrue(answer.text.contains("navigation"));
    }
}
