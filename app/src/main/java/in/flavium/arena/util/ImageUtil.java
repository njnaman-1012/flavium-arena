package in.flavium.arena.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * Team logos. A picture chosen from the phone is shrunk and copied into the app's own
 * private folder; the database stores only the path of that copy. Because the copy is
 * private to the app, no storage permission is needed.
 */
public final class ImageUtil {

    private static final int MAX_SIDE = 512;

    private ImageUtil() {
    }

    /** Copies the chosen picture into files/logos and returns the path of the copy. */
    public static String saveLogo(Context context, Uri source) throws IOException {
        Bitmap original;
        try (InputStream in = context.getContentResolver().openInputStream(source)) {
            original = BitmapFactory.decodeStream(in);
        }
        if (original == null) {
            throw new IOException("The chosen file is not a picture.");
        }

        // Shrink large photos so lists stay fast and storage stays small.
        int longest = Math.max(original.getWidth(), original.getHeight());
        Bitmap small = original;
        if (longest > MAX_SIDE) {
            float scale = MAX_SIDE / (float) longest;
            small = Bitmap.createScaledBitmap(original,
                    Math.round(original.getWidth() * scale),
                    Math.round(original.getHeight() * scale), true);
        }

        File folder = new File(context.getFilesDir(), "logos");
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("Could not create the logo folder.");
        }
        File file = new File(folder, UUID.randomUUID().toString() + ".png");
        try (FileOutputStream out = new FileOutputStream(file)) {
            small.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        return file.getAbsolutePath();
    }

    /** The saved logo, or null when there is none or the file has gone. */
    public static Bitmap load(String path) {
        if (path == null || path.isEmpty() || !new File(path).exists()) {
            return null;
        }
        return BitmapFactory.decodeFile(path);
    }
}
