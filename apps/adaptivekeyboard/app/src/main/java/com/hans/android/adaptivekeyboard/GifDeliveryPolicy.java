package com.hans.android.adaptivekeyboard;

/** Deterministic decision boundary; never convert a GIF action into a pasted URL. */
public final class GifDeliveryPolicy {
    private GifDeliveryPolicy() { }

    static boolean supportsDirectGif(String[] supportedMimeTypes) {
        if(supportedMimeTypes==null)return false;
        for(String mime:supportedMimeTypes) {
            if(mime==null)continue;
            String normalized=mime.toLowerCase(java.util.Locale.ROOT).trim();
            if("image/gif".equals(normalized) || "image/*".equals(normalized) || "*/*".equals(normalized))
                return true;
        }
        return false;
    }
    enum Route { DIRECT_GIF, SHARE_BINARY_GIF }

    /** A failed/unsupported rich insertion falls back to a real GIF attachment. */
    static Route route(boolean commitAccepted) {
        return commitAccepted?Route.DIRECT_GIF:Route.SHARE_BINARY_GIF;
    }
}
