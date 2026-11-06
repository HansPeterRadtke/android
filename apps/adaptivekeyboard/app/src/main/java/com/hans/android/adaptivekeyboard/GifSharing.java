package com.hans.android.adaptivekeyboard;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** Share actual GIF bytes, not a provider web-page URL. */
public final class GifSharing {
    private GifSharing() { }

    public static Uri uriFor(Context context, String filename) {
        if (filename == null || !filename.matches("share-[A-Za-z0-9_.-]+\\.gif"))
            throw new IllegalArgumentException("Not a cached GIF file");
        return new Uri.Builder().scheme("content")
            .authority(context.getPackageName()+".gifs")
            .appendPath("gifs").appendPath(filename).build();
    }

    public static Intent shareIntent(Context context, Uri uri, String title) {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("image/gif");
        share.putExtra(Intent.EXTRA_STREAM, uri);
        share.setClipData(ClipData.newUri(context.getContentResolver(), title, uri));
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return share;
    }

    public static void share(Context context, Uri uri, String title) {
        Intent chooser=Intent.createChooser(shareIntent(context,uri,title),"GIF senden");
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(chooser);
    }

    public static void copyToClipboard(Context context, Uri uri, String title) {
        android.content.ClipboardManager clipboard=(android.content.ClipboardManager)
            context.getSystemService(Context.CLIPBOARD_SERVICE);
        if(clipboard==null)throw new IllegalStateException("Clipboard unavailable");
        ClipData data=ClipData.newUri(context.getContentResolver(),title,uri);
        // Set MIME to image/gif for applications that check clipboard format.
        // ClipData.newUri reads it from GifContentProvider.getType().
        clipboard.setPrimaryClip(data);
    }
}
