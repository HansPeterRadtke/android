package com.hans.android.adaptivekeyboard;
import org.junit.Test;
import static org.junit.Assert.*;
public final class GifSourcesTest {
    @Test public void noKeyDefaultsToRelevantReactionsNotCommonsArchive() {
        assertEquals("tenorweb",GifSources.initialProvider(false));
        assertEquals("giphy",GifSources.initialProvider(true));
    }
    @Test public void searchTermsAreKeptForProviderInsteadOfInventingRandomKeywords() {
        assertEquals("reaction",GifSources.slug(""));
        assertEquals("angry-cat",GifSources.slug("angry  cat"));
        assertEquals("verdammt-noch-mal",GifSources.slug("verdammt noch mal"));
        assertEquals("giphy.com",GifSources.websiteHost("giphyweb"));
        assertEquals("tenor.com",GifSources.websiteHost("tenorweb"));
    }
}
