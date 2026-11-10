package com.hans.android.adaptivekeyboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Punctuation-aware text tokens and sentence boundaries, independent of Android. */
public final class GrammarTokens {
    public static final class Token {
        public final String text;
        public final int start,end;
        public final boolean word;
        Token(String text,int start,int end,boolean word) {
            this.text=text;this.start=start;this.end=end;this.word=word;
        }
        @Override public String toString(){return text;}
    }
    private static final Set<String> ABBREVIATIONS=Set.of(
        "dr.","prof.","mr.","mrs.","ms.","etc.","ca.","bzw.","usw.",
        "e.g.","i.e.","z.b.","z. b.","u.a.","u. a.","d.h.","d. h.","vs.","approx.");
    private GrammarTokens(){}

    private static boolean wordCp(int cp) {
        int type=Character.getType(cp);
        return Character.isLetterOrDigit(cp)||type==Character.NON_SPACING_MARK ||
            type==Character.COMBINING_SPACING_MARK;
    }
    public static List<Token> tokenize(String text) {
        if(text==null||text.isEmpty())return Collections.emptyList();
        ArrayList<Token> tokens=new ArrayList<>();
        int at=0;
        while(at<text.length()) {
            int c=text.codePointAt(at),width=Character.charCount(c),start=at;
            if(Character.isWhitespace(c)) {at+=width;continue;}
            if(c=='.' && text.startsWith("...",at)) {
                while(at<text.length() && text.charAt(at)=='.')at++;
                tokens.add(new Token(text.substring(start,at),start,at,false));continue;
            }
            if(wordCp(c)) {
                at+=width;
                while(at<text.length()) {
                    int next=text.codePointAt(at);
                    if(wordCp(next)){at+=Character.charCount(next);continue;}
                    if((next=='\''||next=='’') && at>start && at+1<text.length() &&
                       Character.isLetter(text.codePointAt(at+1))) {
                        at+=1;continue;
                    }
                    break;
                }
                tokens.add(new Token(text.substring(start,at),start,at,true));continue;
            }
            at+=width;
            tokens.add(new Token(text.substring(start,at),start,at,false));
        }
        return tokens;
    }
    public static String activeWord(String text) {
        if(text==null||text.isEmpty())return "";
        List<Token> tokens=tokenize(text);
        if(tokens.isEmpty())return "";
        Token last=tokens.get(tokens.size()-1);
        return last.word && last.end==text.length()?last.text:"";
    }
    public static boolean isSentenceStart(String before) {
        if(before==null||before.isEmpty())return true;
        return isSentenceStartAt(before, before.length());
    }
    static boolean isSentenceStartAt(String before, int end) {
        int cursor = end - 1;
        while (cursor >= 0) {
            char c = before.charAt(cursor);
            if (c == '\n' || !(Character.isWhitespace(c) || Character.isSpaceChar(c)
                    || "\"'’“”«»‹›)]".indexOf(c) >= 0)) {
                break;
            }
            cursor--;
        }
        if(cursor<0)return true;
        char c=before.charAt(cursor);
        if(c=='\n')return true;
        if(c=='?'||c=='!')return true;
        return c=='.'&&realPeriod(before,cursor);
    }
    public static int lastSentenceStart(String text) {
        if(text==null||text.isEmpty())return 0;
        int start=0;
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(c=='\n'||c=='!'||c=='?')start=i+1;
            else if(c=='.'&&realPeriod(text,i))start=i+1;
        }
        return start;
    }
    // Classify dots using the full text: the prefix in "example.com" tells us
    // the preceding dot is inside a domain even though "com" is still being typed.
    static String sentenceBeforePrefix(String text, String prefix) {
        return text.substring(lastSentenceStart(text), text.length() - prefix.length());
    }

    private static boolean isAbbreviationPeriod(String text, int at) {
        for (String abbreviation : ABBREVIATIONS) {
            for (int dot = abbreviation.indexOf('.'); dot >= 0; dot = abbreviation.indexOf('.', dot + 1)) {
                int start = at - dot;
                if (start < 0 || (start > 0 && Character.isLetterOrDigit(text.codePointBefore(start)))) {
                    continue;
                }
                if (text.regionMatches(true, start, abbreviation, 0, abbreviation.length())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean realPeriod(String value,int at) {
        if(at<0||at>=value.length()||value.charAt(at)!='.')return false;
        if(at>0&&value.charAt(at-1)=='.'||at+1<value.length()&&value.charAt(at+1)=='.')
            return false; // ... is a single punctuation token, not 3 sentence endings.
        if(at>0&&at+1<value.length()&&Character.isDigit(value.charAt(at-1))&&
            Character.isDigit(value.charAt(at+1)))return false; // 3.14
        if(at+1<value.length() && Character.isLetterOrDigit(value.charAt(at+1)))
            return false; // example.com, v1.2, abbreviations
        if (isAbbreviationPeriod(value, at)) return false;
        // Single initials, such as "J. Smith", are normally not full stops.
        if(at>=1 && Character.isUpperCase(value.charAt(at-1)) &&
            (at==1||!Character.isLetter(value.charAt(at-2))))return false;
        return true;
    }
    public static String lastToken(String text) {
        List<Token> ts=tokenize(text);
        return ts.isEmpty()?"":ts.get(ts.size()-1).text;
    }
    public static String previousToken(String text) {
        List<Token> ts=tokenize(text);
        return ts.size()<2?"":ts.get(ts.size()-2).text;
    }
    public static String normalize(String token) {
        return token==null?"":token.toLowerCase(Locale.GERMAN).replace('’','\'');
    }
}
