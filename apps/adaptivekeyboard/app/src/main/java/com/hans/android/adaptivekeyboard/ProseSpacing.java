package com.hans.android.adaptivekeyboard;

/** English/German prose spacing at the insertion point. Never changes earlier text. */
final class ProseSpacing {
    private static final String CLOSING=".,!?;:…)]}”»";
    private ProseSpacing() {}

    static int spacesBeforeMark(CharSequence before,String mark) {
        if(before==null || mark.length()!=1 || CLOSING.indexOf(mark.charAt(0))<0)return 0;
        int end=before.length(),i=end;
        while(i>0 && Character.getType(before.charAt(i-1))==Character.SPACE_SEPARATOR)i--;
        // Keep line indentation and tabs when punctuation begins a line.
        if(i==0 || Character.isWhitespace(before.charAt(i-1)))return 0;
        return end-i;
    }

    static String completion(String left,boolean replacingPrefix,String value,CharSequence after) {
        boolean lead=!left.isEmpty() && "!?;,:…".indexOf(left.charAt(left.length()-1))>=0;
        if(!replacingPrefix && left.endsWith("."))lead=left.endsWith("..") || GrammarTokens.isSentenceStart(left);
        String tail=" ";
        if(after!=null && after.length()>0) {
            char next=after.charAt(0);
            // A single existing ordinary space is consumed and reinserted so
            // the caret advances past it. Other whitespace and marks stay put.
            if(next!=' ' && (CLOSING.indexOf(next)>=0 || Character.isWhitespace(next) ||
                Character.isSpaceChar(next) || next=='\'' || next=='’' || next=='-'))tail="";
        }
        return (lead?" ":"")+value+tail;
    }
}
