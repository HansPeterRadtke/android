package com.hans.android.adaptivekeyboard;
import static org.junit.Assert.*;
import org.junit.Test;
public class GiphyConfigurationTest {
  @Test public void richSearchDoesNotForceGermanOrPG() throws Exception {
    String u=GiphySearch.giphyUrl("test-key","oh my god", "r",0);
    assertTrue(u.contains("/gifs/search?"));
    assertTrue(u.contains("q=oh+my+god"));
    assertTrue(u.contains("rating=r"));
    assertTrue(u.contains("limit=24"));
    assertFalse(u.contains("lang=de"));
  }
  @Test public void trendingAndPaginationUseOfficialEndpoint() throws Exception {
    String u=GiphySearch.giphyUrl("test-key","", "pg-13",24);
    assertTrue(u.contains("/gifs/trending?"));
    assertTrue(u.contains("offset=24"));
    assertTrue(u.contains("rating=pg-13"));
    assertFalse(u.contains("&q="));
  }
  @Test public void ratingIsValidatedAndKeyIsRequired() throws Exception {
    assertTrue(GiphySearch.giphyUrl("test-key","x","bogus",-1).contains("rating=r"));
    try {GiphySearch.giphyUrl("  ","x","r",0);fail("missing key should fail");}
    catch(IllegalArgumentException expected) {assertTrue(expected.getMessage().contains("Schlüssel"));}
  }
}
