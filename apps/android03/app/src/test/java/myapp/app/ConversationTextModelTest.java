package myapp.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class ConversationTextModelTest {
  @Test public void confirmedMessagesRetainExactWhitespaceAcrossAppendAndRestore() {
    ConversationTextModel model = new ConversationTextModel();
    String user = "  first line\n\tindented line  \n";
    String answer = "\n  exact answer  \n";
    model.confirmUser("u1", user);
    assertEquals("You\n" + user + "\n\n", model.confirmedText());
    model.confirmAssistant("a1", answer);
    String expected = "You\n" + user + "\n\nAgent\n" + answer + "\n\n";
    assertEquals(expected, model.confirmedText());
    ConversationTextModel restored = new ConversationTextModel();
    restored.replaceHistory(model.confirmedText(), "u1", "a1");
    assertEquals(expected, restored.render("empty"));
    restored.confirmUser("u2", "next");
    assertEquals(expected + "You\nnext\n\n", restored.confirmedText());
  }

  @Test public void liveMessagesRetainExactWhitespaceThroughConfirmation() {
    ConversationTextModel model = new ConversationTextModel();
    String user = "  corrected text  \n";
    String answer = "\n\tformatted reply  ";
    model.setLiveUser("u1", user);
    model.setLiveAssistant("a1", answer);
    String expected = "You\n" + user + "\n\nAgent\n" + answer + "\n\n";
    assertEquals(expected, model.render("empty"));
    model.confirmUser("u1", user);
    model.confirmAssistant("a1", answer);
    assertEquals(expected, model.render("empty"));
  }

  @Test public void whitespaceOnlyMessagesDoNotCreateConversationEntries() {
    ConversationTextModel model = new ConversationTextModel();
    model.setLiveUser("u1", " \n\t ");
    model.setLiveAssistant("a1", " \n\t ");
    assertEquals("empty", model.render("empty"));
    model.confirmUser("u1", " \n\t ");
    model.confirmAssistant("a1", " \n\t ");
    assertEquals("", model.confirmedText());
  }

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

  @Test public void lateConfirmationsMustNotEraseLaterMessages() {
    ConversationTextModel model = new ConversationTextModel();
    model.replaceHistory("You\nEarlier question", "old-user", "old-agent");
    model.setLiveUser("", "new speech is being recognized");
    model.confirmUser("old-user-2", "an earlier submitted message");
    assertTrue(model.render("empty").contains("new speech is being recognized"));
    model.setLiveUser("new-user", "the finalized newer question");
    model.confirmUser("other-user", "another previous message");
    assertTrue(model.render("empty").contains("the finalized newer question"));
    model.confirmUser("new-user", "the finalized newer question");
    assertEquals(1, occurrences(model.render("empty"), "the finalized newer question"));
    model.setLiveAssistant("new-reply", "current answer being generated");
    model.confirmAssistant("older-reply", "older reply");
    assertTrue(model.render("empty").contains("current answer being generated"));
    model.confirmAssistant("new-reply", "current answer being generated");
    assertEquals(1, occurrences(model.render("empty"), "current answer being generated"));
  }

  private static int occurrences(String value, String needle) {
    int count=0, at=0;
    while ((at=value.indexOf(needle, at)) >= 0) { count++; at += needle.length(); }
    return count;
  }
}
