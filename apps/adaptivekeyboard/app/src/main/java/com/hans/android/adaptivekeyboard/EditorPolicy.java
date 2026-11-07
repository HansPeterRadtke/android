package com.hans.android.adaptivekeyboard;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;

/** Field-specific keyboard behavior based on Android EditorInfo, not package names. */
public final class EditorPolicy {
    public enum Kind {PROSE, SEARCH, URI, EMAIL, PRIVATE, NUMERIC}
    private EditorPolicy() { }
    public static Kind classify(int inputType,int imeOptions) {
        int cls=inputType & InputType.TYPE_MASK_CLASS;
        int variation=inputType & InputType.TYPE_MASK_VARIATION;
        if(cls!=InputType.TYPE_CLASS_TEXT)return Kind.NUMERIC;
        if(variation==InputType.TYPE_TEXT_VARIATION_PASSWORD ||
           variation==InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
           variation==InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
           (imeOptions&EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)!=0)
            return Kind.PRIVATE;
        if(variation==InputType.TYPE_TEXT_VARIATION_URI)return Kind.URI;
        if(variation==InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
           variation==InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)return Kind.EMAIL;
        if((imeOptions&EditorInfo.IME_MASK_ACTION)==EditorInfo.IME_ACTION_SEARCH)
            return Kind.SEARCH;
        return Kind.PROSE; // WEB_EDIT_TEXT alone is NOT a URL.
    }
    public static boolean learns(Kind kind) {return kind==Kind.PROSE;}
    public static boolean predictsWords(Kind kind) {return kind==Kind.PROSE||kind==Kind.SEARCH;}
    public static boolean allowsAutoShift(Kind kind) {return kind==Kind.PROSE;}
    public static String[] shortcuts(Kind kind) {
        if(kind==Kind.URI)return new String[]{".com",".de","/"};
        if(kind==Kind.EMAIL)return new String[]{"@",".com",".de"};
        return new String[0];
    }
}
