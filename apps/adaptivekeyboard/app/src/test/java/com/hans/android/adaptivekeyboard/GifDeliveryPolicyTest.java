package com.hans.android.adaptivekeyboard;

import static org.junit.Assert.*;
import org.junit.Test;

public final class GifDeliveryPolicyTest {
    @Test public void plainTextEditorsRequireBinaryShareNotTextLink() {
        assertFalse(GifDeliveryPolicy.supportsDirectGif(null));
        assertFalse(GifDeliveryPolicy.supportsDirectGif(new String[]{}));
        assertFalse(GifDeliveryPolicy.supportsDirectGif(new String[]{"text/plain","image/png"}));
        assertEquals(GifDeliveryPolicy.Route.SHARE_BINARY_GIF,GifDeliveryPolicy.route(false));
    }
    @Test public void compatibleEditorsAcceptGifMimeAndWildcards() {
        assertTrue(GifDeliveryPolicy.supportsDirectGif(new String[]{"image/gif"}));
        assertTrue(GifDeliveryPolicy.supportsDirectGif(new String[]{"text/plain","image/*"}));
        assertTrue(GifDeliveryPolicy.supportsDirectGif(new String[]{"*/*"}));
        assertEquals(GifDeliveryPolicy.Route.DIRECT_GIF,GifDeliveryPolicy.route(true));
    }
    @Test public void rejectionOfRichContentAlwaysFallsBackToImageShare() {
        assertEquals(GifDeliveryPolicy.Route.SHARE_BINARY_GIF,GifDeliveryPolicy.route(false));
    }
}
