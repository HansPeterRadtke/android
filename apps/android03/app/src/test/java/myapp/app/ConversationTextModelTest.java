package myapp.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class ConversationTextModelTest {
  @Test public void liveUserTextIsReplacedInPlaceUntilSubmitted() {
    ConversationTextModel model = new ConversationTextModel();
    model.replaceHistory("Agent\nEarlier answer", "", "a0");
    model.setLiveUser("", "and what forward are you");
    assertTrue(model.render("empty").contains("and what forward are you"));
    model.setLiveUser("u1", "And what folder are you?");
    String verified = model.render("empty");
    assertFalse(verified.contains("and what forward are you"));
    assertTrue(verified.contains("And what folder are you?"));
    model.setLiveUser("u1", "And what folder are you exactly?");
    assertTrue(model.render("empty").contains("exactly"));
    model.confirmUser("u1", "And what folder are you exactly?");
    String finalText = model.render("empty");
    assertEquals(1, occurrences(finalText, "And what folder are you exactly?"));
  }

  @Test public void assistantDraftFinalizesWithoutDuplicate() {
    ConversationTextModel model = new ConversationTextModel();
    model.replaceHistory("You\nQuestion", "u1", "");
    model.setLiveAssistant("a1", "Draft answer");
    assertTrue(model.render("empty").contains("Draft answer"));
    model.setLiveAssistant("a1", "Final answer");
    assertFalse(model.render("empty").contains("Draft answer"));
    model.confirmAssistant("a1", "Final answer");
    assertEquals(1, occurrences(model.render("empty"), "Final answer"));
  }

  @Test public void interruptedAssistantDraftCanBeRemoved() {
    ConversationTextModel model = new ConversationTextModel();
    model.replaceHistory("You\nQuestion", "u1", "");
    model.setLiveAssistant("a1", "Interrupted draft");
    model.clearLiveAssistant();
    assertFalse(model.render("empty").contains("Interrupted draft"));
  }

  private static int occurrences(String value, String needle) {
    int count=0, at=0;
    while ((at=value.indexOf(needle, at)) >= 0) { count++; at += needle.length(); }
    return count;
  }
}
