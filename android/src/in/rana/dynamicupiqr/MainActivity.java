package in.rana.dynamicupiqr;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Dynamic UPI QR Code - Android app (v5.5).
 * Hosts the bundled web app in a WebView, fully offline, with native save / share / paste / update helpers.
 *
 * Startup is deliberately defensive: nothing here registers broadcast receivers or touches APIs that
 * newer Android versions restrict, and any unexpected failure is recorded and shown instead of silently exiting.
 */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.local";
    private static final String ORIGIN = "https://" + HOST + "/index.html";
    private static final String WEB_FALLBACK = "https://therjmali.github.io/dynamic-upi-qr-code/";
    private static final String VERSION_FALLBACK = "5.5";
    private static final int REQ_FILE = 71;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web;
    private ValueCallback<Uri[]> fileCb;
    private volatile String pendingImage = "";   // QR image shared from Gallery, waiting for the web layer
    private String dlSha = "";

    // ---------------------------------------------------------------- lifecycle
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        installCrashReporter();
        try {
            web = new WebView(this);
            web.setBackgroundColor(0xFFF4F7FB);
            setContentView(web);

            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            s.setAllowFileAccess(false);
            s.setAllowContentAccess(false);
            s.setBuiltInZoomControls(false);
            s.setUseWideViewPort(true);          // honour the page viewport (zoomed-out app layout)
            s.setLoadWithOverviewMode(false);
            s.setUserAgentString(s.getUserAgentString() + " DynamicUPIQR/" + VERSION_FALLBACK);

            web.addJavascriptInterface(new Bridge(), "AndroidApp");
            web.setWebViewClient(new AppClient());
            web.setWebChromeClient(new AppChrome());

            boolean restored = false;
            if (b != null) {
                try {
                    restored = web.restoreState(b) != null;
                } catch (Throwable t) {
                    restored = false;
                }
            }
            if (!restored) open(getIntent(), true);
        } catch (Throwable t) {
            recordCrash(t);
            showStartupFailure(t);
            return;
        }
        showLastCrash();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        try {
            if (web != null) web.saveState(out);
        } catch (Throwable t) {
            // ignore: state is optional
        }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        try {
            if (web != null) open(i, false);
        } catch (Throwable t) {
            recordCrash(t);
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (web != null) {
            try {
                web.removeJavascriptInterface("AndroidApp");
                web.destroy();
            } catch (Throwable t) {
                // ignore
            }
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE && fileCb != null) {
            Uri[] r = null;
            if (res == RESULT_OK && data != null && data.getData() != null) r = new Uri[]{data.getData()};
            fileCb.onReceiveValue(r);
            fileCb = null;
        } else {
            super.onActivityResult(req, res, data);
        }
    }

    /** Loads the app, forwarding shared text / pay links / QR images that opened it. */
    private void open(Intent i, boolean first) {
        String url = ORIGIN;
        if (i != null) {
            String a = i.getAction();
            if (Intent.ACTION_SEND.equals(a)) {
                String type = i.getType();
                String t = i.getStringExtra(Intent.EXTRA_TEXT);
                if (type != null && type.startsWith("image/")) {
                    Uri img = (Uri) i.getParcelableExtra(Intent.EXTRA_STREAM);
                    if (img != null) readSharedImage(img);
                } else if (t != null) {
                    try {
                        url = ORIGIN + "?text=" + URLEncoder.encode(t, "UTF-8");
                    } catch (Exception e) {
                        url = ORIGIN;
                    }
                }
            } else if (Intent.ACTION_VIEW.equals(a) && i.getData() != null) {
                String q = i.getData().getEncodedQuery();
                if (q != null) url = ORIGIN + "?" + q;
            }
        }
        if (first || !ORIGIN.equals(url)) web.loadUrl(url);
    }

    // ---------------------------------------------------------------- crash safety
    private void installCrashReporter() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                recordCrash(e);
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    private void recordCrash(Throwable e) {
        try {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String info = "v" + VERSION_FALLBACK + " | Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") | "
                    + Build.MANUFACTURER + " " + Build.MODEL + "\n\n" + sw;
            getSharedPreferences("crash", MODE_PRIVATE).edit().putString("last", info).commit();
        } catch (Throwable x) {
            // nothing more we can do
        }
    }

    /** If the previous run ended in a crash, show what happened so it can be fixed (instead of a silent exit). */
    private void showLastCrash() {
        try {
            final SharedPreferences p = getSharedPreferences("crash", MODE_PRIVATE);
            final String last = p.getString("last", null);
            if (last == null) return;
            p.edit().remove("last").commit();
            final String shown = last.length() > 1800 ? last.substring(0, 1800) : last;
            new AlertDialog.Builder(this)
                    .setTitle("The app closed unexpectedly last time")
                    .setMessage(shown)
                    .setPositiveButton("Copy details", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(ClipData.newPlainText("crash", last));
                            Toast.makeText(MainActivity.this, "Details copied", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("Close", null)
                    .show();
        } catch (Throwable t) {
            // ignore
        }
    }

    /** Last resort if the built-in browser cannot start: say so and offer the web version. */
    private void showStartupFailure(Throwable t) {
        try {
            TextView tv = new TextView(this);
            tv.setPadding(48, 120, 48, 48);
            tv.setTextSize(16);
            tv.setText("Dynamic UPI QR could not start its built-in browser.\n\nTap here to open the web version instead.\n\n" + t);
            tv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    openExternal(WEB_FALLBACK);
                }
            });
            setContentView(tv);
        } catch (Throwable x) {
            finish();
        }
    }

    // ---------------------------------------------------------------- shared QR image from Gallery
    private void readSharedImage(final Uri uri) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    pendingImage = encodeImage(uri);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            try {
                                web.evaluateJavascript("window.dupiSharedImage&&window.dupiSharedImage()", null);
                            } catch (Throwable t) {
                                // page will pick the image up on its own start-up pass
                            }
                        }
                    });
                } catch (Throwable e) {
                    toast("Could not open the shared image");
                }
            }
        }).start();
    }

    private String encodeImage(Uri uri) throws IOException {
        ContentResolver r = getContentResolver();
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        InputStream in = r.openInputStream(uri);
        try {
            BitmapFactory.decodeStream(in, null, o);
        } finally {
            in.close();
        }
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / sample > 2400) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        in = r.openInputStream(uri);
        Bitmap bmp;
        try {
            bmp = BitmapFactory.decodeStream(in, null, o2);
        } finally {
            in.close();
        }
        if (bmp == null) throw new IOException("decode failed");
        Bitmap flat = Bitmap.createBitmap(bmp.getWidth(), bmp.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(flat);
        c.drawColor(Color.WHITE);
        c.drawBitmap(bmp, 0, 0, null);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        flat.compress(Bitmap.CompressFormat.JPEG, 92, bo);
        bmp.recycle();
        flat.recycle();
        return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
    }

    // ---------------------------------------------------------------- in-app updates (no broadcast receivers: the download is polled)
    private void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast("Could not open the link");
        }
    }

    private void beginUpdate(String url, String sha, String name) {
        if (url == null || !url.startsWith("https://")) {
            toast("Invalid update link");
            return;
        }
        try {
            String fname = "Dynamic-UPI-QR-Code-" + safeName(name) + ".apk";
            File old = new File(getExternalFilesDir(null), fname);
            if (old.exists()) old.delete();
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.setTitle("Dynamic UPI QR " + name);
            r.setDescription("Downloading update");
            r.setMimeType("application/vnd.android.package-archive");
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalFilesDir(this, null, fname);
            dlSha = sha == null ? "" : sha.toLowerCase();
            long id = dm.enqueue(r);
            toast("Downloading update...");
            pollDownload(id, 0);
        } catch (Exception e) {
            openExternal(url);
        }
    }

    private void pollDownload(final long id, final int tries) {
        ui.postDelayed(new Runnable() {
            public void run() {
                Cursor c = null;
                try {
                    DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    c = dm.query(new DownloadManager.Query().setFilterById(id));
                    if (c != null && c.moveToFirst()) {
                        int st = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
                        if (st == DownloadManager.STATUS_SUCCESSFUL) {
                            installDownloaded(id);
                            return;
                        }
                        if (st == DownloadManager.STATUS_FAILED) {
                            toast("Update download failed. Check your connection and try again.");
                            return;
                        }
                    }
                } catch (Throwable t) {
                    // keep polling
                } finally {
                    if (c != null) c.close();
                }
                if (tries < 240) pollDownload(id, tries + 1);
                else toast("Update download timed out");
            }
        }, 750);
    }

    private void installDownloaded(long id) {
        try {
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            Uri u = dm.getUriForDownloadedFile(id);
            if (u == null) {
                toast("Update download failed");
                return;
            }
            if (dlSha.length() == 64) {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                InputStream in = getContentResolver().openInputStream(u);
                try {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
                } finally {
                    in.close();
                }
                StringBuilder sb = new StringBuilder();
                for (byte x : md.digest()) sb.append(String.format("%02x", x));
                if (!sb.toString().equals(dlSha)) {
                    dm.remove(id);
                    toast("Update file is damaged. Please try again.");
                    return;
                }
            }
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable e) {
            toast("Could not start the installer");
        }
    }

    // ---------------------------------------------------------------- serving the bundled web app
    private static String mime(String p) {
        String l = p.toLowerCase();
        if (l.endsWith(".html")) return "text/html";
        if (l.endsWith(".js")) return "application/javascript";
        if (l.endsWith(".css")) return "text/css";
        if (l.endsWith(".png")) return "image/png";
        if (l.endsWith(".svg")) return "image/svg+xml";
        if (l.endsWith(".woff2")) return "font/woff2";
        if (l.endsWith(".json") || l.endsWith(".webmanifest")) return "application/json";
        if (l.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    private WebResourceResponse serve(Uri u) {
        if (u == null || !HOST.equals(u.getHost())) return null;
        String p = u.getPath();
        if (p == null || p.equals("/")) p = "/index.html";
        p = p.replace("..", "");
        try {
            InputStream in = getAssets().open("www" + p);
            return new WebResourceResponse(mime(p), "UTF-8", in);
        } catch (IOException e) {
            return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                    new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
        }
    }

    private class AppClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            try {
                return serve(r.getUrl());
            } catch (Throwable t) {
                return null;
            }
        }

        // UPI app links (upi://, gpay://, phonepe://, paytmmp://) and web links open in the right app.
        @Override
        public boolean shouldOverrideUrlLoading(WebView v, String url) {
            Uri u = Uri.parse(url);
            if (HOST.equals(u.getHost())) return false;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, u));
            } catch (Exception e) {
                String sch = u.getScheme();
                boolean isWeb = "http".equals(sch) || "https".equals(sch);
                Toast.makeText(MainActivity.this, isWeb ? "No browser found" : "No UPI app found to open this payment", Toast.LENGTH_LONG).show();
            }
            return true;
        }
    }

    private class AppChrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
            if (fileCb != null) fileCb.onReceiveValue(null);
            fileCb = cb;
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            try {
                startActivityForResult(Intent.createChooser(i, "Choose a QR image"), REQ_FILE);
            } catch (Exception e) {
                fileCb = null;
                cb.onReceiveValue(null);
                return false;
            }
            return true;
        }
    }

    // ---------------------------------------------------------------- native features exposed to the web app as window.AndroidApp
    private void toast(final String m) {
        runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(MainActivity.this, m, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private Uri store(String name, String mimeType, byte[] data, boolean image) throws IOException {
        ContentResolver r = getContentResolver();
        ContentValues v = new ContentValues();
        v.put("_display_name", name);
        v.put("mime_type", mimeType);
        v.put("relative_path", image ? "Pictures/Dynamic UPI QR" : "Download/Dynamic UPI QR");
        v.put("is_pending", 1);
        Uri col = image ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI : Uri.parse("content://media/external/downloads");
        Uri u = r.insert(col, v);
        if (u == null) throw new IOException("insert failed");
        OutputStream os = r.openOutputStream(u);
        try {
            os.write(data);
        } finally {
            os.close();
        }
        ContentValues d = new ContentValues();
        d.put("is_pending", 0);
        r.update(u, d, null, null);
        return u;
    }

    private static String safeName(String n) {
        String s = n == null ? "upi-qr" : n.replaceAll("[^A-Za-z0-9._-]", "_");
        return s.length() == 0 ? "upi-qr" : s;
    }

    private class Bridge {
        @JavascriptInterface
        public String version() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return VERSION_FALLBACK;
            }
        }

        @JavascriptInterface
        public int versionCode() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
            } catch (Exception e) {
                return 0;
            }
        }

        @JavascriptInterface
        public String takeSharedImage() {
            String p = pendingImage;
            pendingImage = "";
            return p == null ? "" : p;
        }

        @JavascriptInterface
        public void startUpdate(final String url, final String sha, final String name) {
            runOnUiThread(new Runnable() {
                public void run() {
                    beginUpdate(url, sha, name);
                }
            });
        }

        @JavascriptInterface
        public void copy(final String t) {
            runOnUiThread(new Runnable() {
                public void run() {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("UPI", t));
                }
            });
        }

        @JavascriptInterface
        public String readClipboard() {
            FutureTask<String> f = new FutureTask<String>(new Callable<String>() {
                public String call() {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    ClipData d = cm.getPrimaryClip();
                    if (d != null && d.getItemCount() > 0) {
                        CharSequence c = d.getItemAt(0).coerceToText(MainActivity.this);
                        return c == null ? "" : c.toString();
                    }
                    return "";
                }
            });
            runOnUiThread(f);
            try {
                return f.get(1500, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void saveFile(String b64, String name, String mimeType) {
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                boolean image = mimeType != null && mimeType.startsWith("image/") && !mimeType.contains("svg");
                store(safeName(name), mimeType == null ? "application/octet-stream" : mimeType, data, image);
                toast(image ? "Saved to Gallery (Pictures/Dynamic UPI QR)" : "Saved to Downloads/Dynamic UPI QR");
            } catch (Exception e) {
                toast("Could not save the file");
            }
        }

        @JavascriptInterface
        public void shareImage(String b64, String name, final String text) {
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                final Uri u = store(safeName(name), "image/jpeg", data, true);
                runOnUiThread(new Runnable() {
                    public void run() {
                        try {
                            Intent s = new Intent(Intent.ACTION_SEND);
                            s.setType("image/jpeg");
                            s.putExtra(Intent.EXTRA_STREAM, u);
                            s.putExtra(Intent.EXTRA_TEXT, text);
                            s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivity(Intent.createChooser(s, "Share QR"));
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, "No app found to share with", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            } catch (Exception e) {
                toast("Could not share the image");
            }
        }
    }
}
