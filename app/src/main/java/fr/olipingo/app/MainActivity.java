package fr.olipingo.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/* Olipingo app (code and releases: github.com/damienburglin-cyber/Olipingo-app).
   The Olipingo artifact on claude.ai is the brain (coachs, data); this app shows it full screen, keeps
   the claude.ai sign-in, opens the camera for meal photos, opens other sites (Foulée, WhatsApp) in their
   own app, and updates itself from this repo's releases.

   Updates, done cleanly so the phone does not complain:
   - every release is signed with the same private key (never the public debug key, which Play Protect flags);
   - before installing, the app checks that the downloaded file carries that same signature and a higher
     version, and refuses anything else;
   - the update is opened in Android's own installer screen (one tap to confirm), never installed silently. */
public class MainActivity extends Activity {
    static String VERSION = "0";
    static long VERSION_CODE = 0;
    private static final String HOME = "https://claude.ai/artifact/U6CkAKW5Lu89RqSjpzH8oi";
    private static final String RELEASES = "https://api.github.com/repos/damienburglin-cyber/Olipingo-app/releases/latest";
    private static final String ARTIFACT_ORIGINS = "https://*.frame.claudeusercontent.com";
    private static final int PICK_FILE = 7;

    private WebView web;
    private ValueCallback<Uri[]> pendingFiles;
    private Uri pendingPhoto;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private AlertDialog updateDialog;
    private long lastCheck = 0;
    private String pendingApk, pendingVersion;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            VERSION = pi.versionName;
            VERSION_CODE = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception ignored) { }
        FrameLayout root = new FrameLayout(this);
        root.setFitsSystemWindows(true);
        web = newWeb();
        root.addView(web);
        setContentView(root);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "OlipingoApp", Collections.singleton(ARTIFACT_ORIGINS),
                (view, message, origin, isMainFrame, reply) -> ui.post(() -> handle(message.getData(), reply)));
        }
        String deep = deepLink(getIntent());
        if (deep != null) web.loadUrl(deep);
        else if (state != null) web.restoreState(state);
        else web.loadUrl(HOME);
        lastCheck = SystemClock.elapsedRealtime();
        checkUpdate();
    }

    /* olipingo://open/name opens the artifact at #name (a plain name only, as claude.ai passes it on). */
    private static String deepLink(Intent i) {
        if (i == null || !Intent.ACTION_VIEW.equals(i.getAction()) || i.getData() == null) return null;
        Uri u = i.getData();
        if (!"olipingo".equals(u.getScheme())) return null;
        String f = u.getPath() == null ? null : u.getPath().replaceFirst("^/", "");
        return HOME + (f != null && f.matches("[A-Za-z0-9-]{1,40}") ? "#" + f : "");
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        String deep = deepLink(i);
        if (deep != null) web.loadUrl(deep);
    }

    /* ---------- The page ---------- */

    /* Pages that stay inside the app: claude.ai, its artifact frames, and the sign-in steps. */
    private static boolean inApp(Uri u) {
        String h = u.getHost() == null ? "" : u.getHost().toLowerCase();
        if (!"https".equals(u.getScheme())) return false;
        return h.equals("claude.ai") || h.endsWith(".claude.ai") || h.endsWith(".claudeusercontent.com")
            || h.equals("anthropic.com") || h.endsWith(".anthropic.com")
            || h.equals("accounts.google.com") || h.equals("appleid.apple.com");
    }

    private void openOutside(Uri u) {
        String s = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
        if (!s.equals("https") && !s.equals("mailto") && !s.equals("tel") && !s.equals("whatsapp")) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) { Toast.makeText(this, "Aucune appli ne peut ouvrir ce lien", Toast.LENGTH_SHORT).show(); }
    }

    private WebView newWeb() {
        WebView w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        /* A link opened in a new tab (Foulée, WhatsApp…) is caught in onCreateWindow and opened outside. */
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        s.setUserAgentString(s.getUserAgentString() + " OlipingoApp/" + VERSION);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (req.isForMainFrame() && !inApp(u)) { openOutside(u); return true; }
                if (!req.isForMainFrame() && !"https".equals(u.getScheme())) { openOutside(u); return true; }
                return false;
            }
        });
        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean userGesture, Message msg) {
                WebView tmp = new WebView(MainActivity.this);
                tmp.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                        Uri u = req.getUrl();
                        if (inApp(u)) view.loadUrl(u.toString()); else openOutside(u);
                        ui.post(v::destroy);
                        return true;
                    }
                });
                ((WebView.WebViewTransport) msg.obj).setWebView(tmp);
                msg.sendToTarget();
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (pendingFiles != null) pendingFiles.onReceiveValue(null);
                pendingFiles = cb;
                pendingPhoto = null;
                Intent pick = params.createIntent();
                /* The gallery and, for images, the camera app are offered together. */
                Intent chooser = Intent.createChooser(pick, "Ajouter une photo");
                if (wantsImage(params)) {
                    try {
                        File dir = new File(getCacheDir(), "photos");
                        dir.mkdirs();
                        File f = new File(dir, "photo-" + System.currentTimeMillis() + ".jpg");
                        pendingPhoto = FileProvider.getUriForFile(MainActivity.this, "fr.olipingo.app.files", f);
                        Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, pendingPhoto)
                            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[] { cam });
                    } catch (Exception ignored) { pendingPhoto = null; }
                }
                try { startActivityForResult(chooser, PICK_FILE); }
                catch (Exception e) { pendingFiles = null; return false; }
                return true;
            }
        });
        return w;
    }

    private static boolean wantsImage(WebChromeClient.FileChooserParams p) {
        String[] t = p.getAcceptTypes();
        if (t == null || t.length == 0) return true;
        for (String x : t) if (x == null || x.isEmpty() || x.startsWith("image") || x.equals("*/*")) return true;
        return false;
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK_FILE || pendingFiles == null) return;
        final ValueCallback<Uri[]> cb = pendingFiles;
        pendingFiles = null;
        /* 0.1.1: the photo picker of recent Android versions may answer with a list (ClipData) and no
           single address; both are read here. Before, a photo chosen in the gallery never reached the page. */
        java.util.ArrayList<Uri> picked = new java.util.ArrayList<>();
        if (res == RESULT_OK && data != null) {
            if (data.getClipData() != null)
                for (int k = 0; k < data.getClipData().getItemCount(); k++) picked.add(data.getClipData().getItemAt(k).getUri());
            else if (data.getData() != null) picked.add(data.getData());
        }
        if (picked.isEmpty() && res == RESULT_OK && pendingPhoto != null && photoTaken()) picked.add(pendingPhoto);
        if (picked.isEmpty()) { cb.onReceiveValue(null); return; }
        /* Gallery photos can be HEIC, which the page cannot read: those are turned into JPEG first. */
        new Thread(() -> {
            Uri[] out = new Uri[picked.size()];
            for (int k = 0; k < out.length; k++) out[k] = asReadableImage(picked.get(k));
            ui.post(() -> cb.onReceiveValue(out));
        }).start();
    }

    private Uri asReadableImage(Uri u) {
        try {
            String type = getContentResolver().getType(u);
            if (type == null || !type.startsWith("image/") || type.equals("image/jpeg") || type.equals("image/png")
                || type.equals("image/webp") || type.equals("image/gif")) return u;
            android.graphics.Bitmap bmp;
            if (Build.VERSION.SDK_INT >= 28) {
                bmp = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(getContentResolver(), u),
                    (dec, info, src) -> {
                        int w = info.getSize().getWidth(), h = info.getSize().getHeight(), m = Math.max(w, h);
                        if (m > 2048) dec.setTargetSize(w * 2048 / m, h * 2048 / m);
                        dec.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                    });
            } else {
                try (InputStream in = getContentResolver().openInputStream(u)) { bmp = android.graphics.BitmapFactory.decodeStream(in); }
            }
            if (bmp == null) return u;
            File dir = new File(getCacheDir(), "photos");
            dir.mkdirs();
            File f = new File(dir, "photo-" + System.currentTimeMillis() + ".jpg");
            try (FileOutputStream out = new FileOutputStream(f)) { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out); }
            return FileProvider.getUriForFile(this, "fr.olipingo.app.files", f);
        } catch (Exception e) { return u; }
    }

    private boolean photoTaken() {
        try (InputStream in = getContentResolver().openInputStream(pendingPhoto)) { return in != null && in.read() != -1; }
        catch (Exception e) { return false; }
    }

    /* ---------- Bridge: only claude.ai artifact pages can call it ----------
       The page posts {"id","cmd",...}; the app answers {"id","ok",...}.
       hello -> {version}; copy {text}; share {text,title}; openUrl {url} (https only). */
    private void handle(String json, JavaScriptReplyProxy r) {
        String id = "";
        try {
            JSONObject in = new JSONObject(json);
            id = in.optString("id");
            JSONObject out = new JSONObject().put("id", id).put("ok", true);
            switch (in.optString("cmd")) {
                case "hello":
                    out.put("app", "olipingo").put("version", VERSION).put("commands", new JSONArray(Arrays.asList("hello", "copy", "share", "openUrl")));
                    break;
                case "copy": {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("Olipingo", in.optString("text")));
                    break;
                }
                case "share": {
                    Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, in.optString("text"));
                    startActivity(Intent.createChooser(s, in.optString("title", "Partager")));
                    break;
                }
                case "openUrl": {
                    Uri u = Uri.parse(in.optString("url"));
                    if (!"https".equals(u.getScheme())) { out.put("ok", false).put("error", "https_only"); break; }
                    openOutside(u);
                    break;
                }
                default:
                    out.put("ok", false).put("error", "unknown_command");
            }
            r.postMessage(out.toString());
        } catch (Exception e) {
            try { r.postMessage(new JSONObject().put("id", id).put("ok", false).put("error", "bad_request").toString()); } catch (Exception ignored) { }
        }
    }

    /* ---------- Updates from this repo's releases ---------- */

    static boolean newer(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }
    private static int num(String s) { try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); } catch (Exception e) { return 0; } }

    private void checkUpdate() {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(RELEASES).openConnection();
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                if (c.getResponseCode() != 200) return;
                JSONObject rel = new JSONObject(readAll(c.getInputStream()));
                String tag = rel.optString("tag_name").replaceFirst("^v", "");
                if (!newer(tag, VERSION)) return;
                JSONArray assets = rel.optJSONArray("assets");
                String apk = null;
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    String u = a.optString("browser_download_url");
                    if (a.optString("name").endsWith(".apk") && u.startsWith("https://github.com/damienburglin-cyber/Olipingo-app/releases/download/")) apk = u;
                }
                if (apk == null) return;
                final String url = apk, notes = rel.optString("body");
                ui.post(() -> offerUpdate(tag, url, notes));
            } catch (Exception ignored) { }
        }).start();
    }

    private void offerUpdate(String version, String url, String notes) {
        if (isFinishing() || (updateDialog != null && updateDialog.isShowing())) return;
        updateDialog = new AlertDialog.Builder(this)
            .setTitle("Olipingo " + version + " est disponible")
            .setMessage((notes == null || notes.isEmpty() ? "" : notes + "\n\n") + "Tu as la " + VERSION
                + ". Tes connexions et tes données sont gardées. L'appli se ferme pendant la mise à jour : rouvre-la ensuite.")
            .setPositiveButton("Mettre à jour", (d, w) -> install(version, url))
            .setNegativeButton("Plus tard", null)
            .show();
    }

    private void install(String version, String url) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            pendingApk = url; pendingVersion = version;
            Toast.makeText(this, "Autorise Olipingo à installer ses mises à jour, puis reviens", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        Toast.makeText(this, "Téléchargement d'Olipingo " + version + "…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                File dir = new File(getCacheDir(), "updates");
                dir.mkdirs();
                File f = new File(dir, "olipingo.apk");
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setInstanceFollowRedirects(true);
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                String problem = checkApk(f);
                if (problem != null) {
                    f.delete();
                    ui.post(() -> Toast.makeText(this, problem, Toast.LENGTH_LONG).show());
                    return;
                }
                commit(f);
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, "Téléchargement impossible, réessaie plus tard.", Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    /* Refuses a file that is not Olipingo, not newer, or not signed with the same key as the installed app. */
    @SuppressWarnings("deprecation")
    private String checkApk(File f) {
        PackageManager pm = getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo got = pm.getPackageArchiveInfo(f.getPath(), flags);
        if (got == null || !getPackageName().equals(got.packageName)) return "Mise à jour refusée : ce fichier n'est pas Olipingo.";
        long code = Build.VERSION.SDK_INT >= 28 ? got.getLongVersionCode() : got.versionCode;
        if (code <= VERSION_CODE) return "Mise à jour refusée : version pas plus récente.";
        try {
            PackageInfo mine = pm.getPackageInfo(getPackageName(), flags);
            if (!signatures(mine).equals(signatures(got))) return "Mise à jour refusée : signature différente.";
        } catch (Exception e) { return "Mise à jour refusée : signature illisible."; }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static Set<String> signatures(PackageInfo p) {
        Signature[] s;
        if (Build.VERSION.SDK_INT >= 28) s = p.signingInfo == null ? null : p.signingInfo.getApkContentsSigners();
        else s = p.signatures;
        Set<String> out = new HashSet<>();
        if (s != null) for (Signature x : s) out.add(x.toCharsString());
        return out;
    }

    /* 0.1.2: the update is handed to Android's own installer screen, as Echo does. 0.1.0-0.1.1 installed it
       themselves through PackageInstaller without asking, which is what Play Protect watches for in apps
       that download and install other apps; it flagged Olipingo. */
    private void commit(File f) {
        Uri u = FileProvider.getUriForFile(this, "fr.olipingo.app.files", f);
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        ui.post(() -> startActivity(i));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (SystemClock.elapsedRealtime() - lastCheck > 10 * 60 * 1000L && (updateDialog == null || !updateDialog.isShowing())) {
            lastCheck = SystemClock.elapsedRealtime();
            checkUpdate();
        }
        if (pendingApk != null && (Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls())) {
            String u = pendingApk, v = pendingVersion;
            pendingApk = null;
            install(v, u);
        }
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }
}
