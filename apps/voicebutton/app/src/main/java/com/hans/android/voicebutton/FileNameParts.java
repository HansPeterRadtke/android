package com.hans.android.voicebutton;

final class FileNameParts {
    final String head;
    final String tail;

    private FileNameParts(String head, String tail) {
        this.head = head;
        this.tail = tail;
    }

    static FileNameParts split(String raw, int preferredHeadCharacters) {
        String value = raw == null || raw.isEmpty() ? "Unnamed" : raw;
        int limit = Math.max(16, preferredHeadCharacters);
        if (value.length() <= limit) return new FileNameParts(value, "");
        int split = limit;
        for (int i = limit; i >= Math.max(12, limit - 14); i--) {
            char character = value.charAt(i - 1);
            if (Character.isWhitespace(character) || character == '-'
                    || character == '_' || character == '.') {
                split = i;
                break;
            }
        }
        return new FileNameParts(value.substring(0, split), value.substring(split));
    }

    String complete() { return head + tail; }
}
