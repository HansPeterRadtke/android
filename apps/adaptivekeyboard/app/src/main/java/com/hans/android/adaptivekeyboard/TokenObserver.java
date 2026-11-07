package com.hans.android.adaptivekeyboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Observe actual completed tokens when the user enters a delimiter. */
public final class TokenObserver {
    private String lastAfter="";
    private int confirmedThrough=0;
    public void reset(){lastAfter="";confirmedThrough=0;}
    public List<String[]> observe(String before,String delimiter) {
        if(before==null||delimiter==null||delimiter.isEmpty())return Collections.emptyList();
        if(!before.startsWith(lastAfter)) {
            // Editor switch, paste or cursor movement: do not relabel the whole
            // existing field as newly typed text. Start at the last token.
            List<GrammarTokens.Token> previous=GrammarTokens.tokenize(before);
            confirmedThrough=previous.isEmpty()?before.length():previous.get(previous.size()-1).start;
        }
        List<GrammarTokens.Token> tokens=GrammarTokens.tokenize(before);
        ArrayList<String[]> observations=new ArrayList<>();
        boolean separator=Character.isWhitespace(delimiter.charAt(0));
        for(int i=0;i<tokens.size();i++) {
            GrammarTokens.Token current=tokens.get(i);
            if(current.end<=confirmedThrough)continue;
            // A run of dots is still being extended until another delimiter
            // closes it. Do not learn '.' and '..' while the user types '...'.
            if(!separator && delimiter.equals(".") && !current.word &&
                current.text.chars().allMatch(ch->ch=='.'))continue;
            if(i==tokens.size()-1 && !separator && !current.word)continue;
            String predecessor=i==0?TokenHistory.START:tokens.get(i-1).text;
            observations.add(new String[]{predecessor,current.text});
            confirmedThrough=current.end;
        }
        lastAfter=before+delimiter;
        return observations;
    }
}
