package myapp.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class VoiceConnectionTrackerTest {
  @Test public void staleGenerationCannotMutateCurrentConnection() {
    VoiceConnectionTracker tracker = new VoiceConnectionTracker();
    long oldGeneration = tracker.beginConnect();
    assertTrue(tracker.onOpen(oldGeneration, 1000));
    long currentGeneration = tracker.beginConnect();
    assertFalse(tracker.onClosed(oldGeneration));
    assertFalse(tracker.onMediaAck(oldGeneration, 2000));
    assertTrue(tracker.onOpen(currentGeneration, 2100));
    assertTrue(tracker.isOpen());
    assertFalse(tracker.isMediaConfirmed());
  }

  @Test public void closeIsHandledOnlyOncePerGeneration() {
    VoiceConnectionTracker tracker = new VoiceConnectionTracker();
    long generation = tracker.beginConnect();
    tracker.onOpen(generation, 1000);
    assertTrue(tracker.onClosed(generation));
    assertFalse(tracker.onClosed(generation));
  }

  @Test public void mediaAckIsTheOnlyHealthyBackoffReset() {
    VoiceConnectionTracker tracker = new VoiceConnectionTracker();
    assertEquals(0, tracker.nextReconnectAttempt(6));
    assertEquals(1, tracker.nextReconnectAttempt(6));
    long generation = tracker.beginConnect();
    assertTrue(tracker.onOpen(generation, 1000));
    assertEquals(2, tracker.nextReconnectAttempt(6));
    assertTrue(tracker.onMediaAck(generation, 1100));
    assertEquals(0, tracker.nextReconnectAttempt(6));
  }

  @Test public void mediaPathHasGraceAndThenBecomesStale() {
    VoiceConnectionTracker tracker = new VoiceConnectionTracker();
    long generation = tracker.beginConnect();
    tracker.onOpen(generation, 1000);
    assertFalse(tracker.mediaPathStale(10999, 10000));
    assertTrue(tracker.mediaPathStale(11001, 10000));
    tracker.onMediaAck(generation, 12000);
    assertFalse(tracker.mediaPathStale(21999, 10000));
    assertTrue(tracker.mediaPathStale(22001, 10000));
  }

  @Test public void lastSuccessfulMediaAckSurvivesReconnectForFreshness() {
    VoiceConnectionTracker tracker = new VoiceConnectionTracker();
    long first = tracker.beginConnect();
    tracker.onOpen(first, 1000);
    tracker.onMediaAck(first, 1200);
    tracker.onClosed(first);
    long second = tracker.beginConnect();
    tracker.onOpen(second, 2000);
    assertEquals(1200L, tracker.lastMediaAckMs());
    assertFalse(tracker.mediaPathStale(11999, 10000));
    assertTrue(tracker.mediaPathStale(12001, 10000));
  }

  @Test public void reconnectAttemptSaturates() {
    VoiceConnectionTracker tracker = new VoiceConnectionTracker();
    for (int i = 0; i < 20; i++) tracker.nextReconnectAttempt(3);
    assertEquals(3, tracker.nextReconnectAttempt(3));
  }
}
