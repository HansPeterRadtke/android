package myapp.app;

final class ConversationTextModel {
  private String confirmed = "";
  private String lastConfirmedUserTurnId = "";
  private String lastConfirmedAssistantTurnId = "";
  private String liveUserTurnId = "";
  private String liveUserText = "";
  private String liveAssistantTurnId = "";
  private String liveAssistantText = "";

  synchronized void replaceHistory(
      String confirmedText, String lastUserTurnId, String lastAssistantTurnId) {
    confirmed = normalizeBlock(confirmedText);
    lastConfirmedUserTurnId = clean(lastUserTurnId);
    lastConfirmedAssistantTurnId = clean(lastAssistantTurnId);
    liveUserTurnId = "";
    liveUserText = "";
    liveAssistantTurnId = "";
    liveAssistantText = "";
  }

  synchronized void setLiveUser(String turnId, String text) {
    liveUserTurnId = clean(turnId);
    liveUserText = clean(text);
  }

  synchronized void setLiveAssistant(String turnId, String text) {
    liveAssistantTurnId = clean(turnId);
    liveAssistantText = clean(text);
  }

  synchronized void clearLiveUser() {
    liveUserTurnId = "";
    liveUserText = "";
  }

  synchronized void clearLiveAssistant() {
    liveAssistantTurnId = "";
    liveAssistantText = "";
  }

  synchronized void confirmUser(String turnId, String text) {
    String id = clean(turnId);
    String value = clean(text);
    if (!value.isEmpty() && (id.isEmpty() || !id.equals(lastConfirmedUserTurnId))) {
      confirmed = append(confirmed, "You", value);
      lastConfirmedUserTurnId = id;
    }
    clearLiveUser();
  }

  synchronized void confirmAssistant(String turnId, String text) {
    String id = clean(turnId);
    String value = clean(text);
    if (!value.isEmpty() && (id.isEmpty() || !id.equals(lastConfirmedAssistantTurnId))) {
      confirmed = append(confirmed, "Agent", value);
      lastConfirmedAssistantTurnId = id;
    }
    clearLiveAssistant();
  }

  synchronized String confirmedText() {
    return confirmed;
  }

  synchronized String render(String emptyText) {
    StringBuilder out = new StringBuilder(confirmed);
    if (!liveUserText.isEmpty()
        && (liveUserTurnId.isEmpty() || !liveUserTurnId.equals(lastConfirmedUserTurnId))) {
      appendTo(out, "You", liveUserText);
    }
    if (!liveAssistantText.isEmpty()
        && (liveAssistantTurnId.isEmpty()
            || !liveAssistantTurnId.equals(lastConfirmedAssistantTurnId))) {
      appendTo(out, "Agent", liveAssistantText);
    }
    String value = out.toString();
    return value.trim().isEmpty() ? emptyText : value;
  }

  private static String append(String base, String role, String text) {
    StringBuilder out = new StringBuilder(normalizeBlock(base));
    appendTo(out, role, text);
    return out.toString();
  }

  private static void appendTo(StringBuilder out, String role, String text) {
    if (out.length() > 0 && !out.toString().endsWith("\n\n")) out.append("\n\n");
    out.append(role).append('\n').append(text).append("\n\n");
  }

  private static String normalizeBlock(String value) {
    String clean = value == null ? "" : value.trim();
    return clean.isEmpty() ? "" : clean + "\n\n";
  }

  private static String clean(String value) {
    return value == null ? "" : value.trim();
  }
}
