package com.hans.android.adaptivekeyboard;

import java.util.Locale;

/** Official GIF discovery destinations; no third-party API keys are bundled. */
public final class GifSources {
    private GifSources() { }
    public static String initialProvider(boolean hasOwnGiphyKey) {
        // The anonymous Wikimedia corpus is archival and is not a suitable
        // default for reaction GIF searches.
        return hasOwnGiphyKey?"giphy":"reactions";
    }
    public static String slug(String query) {
        if(query==null||query.trim().isEmpty())return "reaction";
        String s=query.trim().replaceAll("\\s+","-");
        return s.length()>80?s.substring(0,80):s;
    }
    public static String websiteHost(String provider) {
        return "giphyweb".equals(provider)?"giphy.com":"tenor.com";
    }
}
