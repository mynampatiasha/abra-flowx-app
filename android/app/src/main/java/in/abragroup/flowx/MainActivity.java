package in.abragroup.flowx;

import android.app.DownloadManager;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebView;
import android.widget.Toast;

import com.getcapacitor.BridgeActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Abra FlowX — the CRM (abra-flowxai.abragroup.in/dashboard) in a native app.
 * Adds what a plain WebView lacks:
 *  - file downloads (PDF / Excel / CSV / zip / photos) into the phone's Downloads,
 *    using the logged-in session cookies;
 *  - "blob:" downloads made in the page by JavaScript (e.g. generated PDFs / CSV);
 *  - the phone's Back button goes back a page instead of closing the app.
 */
public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final WebView web = this.bridge.getWebView();
        web.getSettings().setSupportZoom(true);
        web.getSettings().setBuiltInZoomControls(true);
        web.getSettings().setDisplayZoomControls(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.addJavascriptInterface(new BlobSaver(), "AbraAppDownloader");
        // Test Notifications page (test-notifications.php) + high-priority alert channel
        NotifyHelper.ensureChannel(this);
        web.addJavascriptInterface(new NotifyHelper(this), "AbraNative");

        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
            if (url.startsWith("blob:")) {
                String js = "(function(){var x=new XMLHttpRequest();x.open('GET','" + url + "',true);x.responseType='blob';"
                    + "x.onload=function(){var r=new FileReader();r.onloadend=function(){"
                    + "AbraAppDownloader.save(r.result.split(',')[1]," + quote(mimeType) + "," + quote(name) + ");};r.readAsDataURL(x.response);};x.send();})();";
                web.evaluateJavascript(js, null);
                return;
            }
            if (url.startsWith("data:")) {
                int comma = url.indexOf(',');
                new BlobSaver().save(url.substring(comma + 1), mimeType, name);
                return;
            }
            try {
                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                String cookies = CookieManager.getInstance().getCookie(url);
                if (cookies != null) req.addRequestHeader("Cookie", cookies);
                req.addRequestHeader("User-Agent", userAgent);
                req.setMimeType(mimeType);
                req.setTitle(name);
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(req);
                Toast.makeText(this, "Downloading " + name + "…", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private static String quote(String s) {
        return "'" + (s == null ? "" : s.replace("\\", "\\\\").replace("'", "\\'")) + "'";
    }

    /* Login cookies (session + "remember me") are written to disk right away,
       so closing / killing the app — or Vivo / Oppo clearing it — never logs the user out. */
    @Override
    public void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    public void onStop() {
        super.onStop();
        CookieManager.getInstance().flush();
    }

    @Override
    public void onBackPressed() {
        WebView web = this.bridge.getWebView();
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    /** Saves base64 file data (from blob: / data: downloads) into Downloads. */
    private class BlobSaver {
        @JavascriptInterface
        public void save(String base64, String mime, String fileName) {
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                String type = (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                    v.put(MediaStore.Downloads.MIME_TYPE, type);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) { os.write(data); }
                } else {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    try (FileOutputStream os = new FileOutputStream(new File(dir, fileName))) { os.write(data); }
                }
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Saved to Downloads: " + fileName, Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Could not save file: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }
    }
}
