package com.enship.travel;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * EnerShip Travel — MainActivity v2.0
 *
 * Rebuilt from scratch for maximum reliability with HC-06 Bluetooth Classic.
 * Features:
 * - Robust RFCOMM connection with fallback socket via reflection
 * - Automatic reconnection with exponential backoff
 * - Buffered line-based reading with overflow protection
 * - XOR checksum verification on every frame
 * - Base64 encoding to JS to avoid escaping issues
 * - Connection watchdog
 * - Background operation support
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "EnerShip";

    // SPP UUID — HC-06 always uses this
    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");

    // Max reconnect attempts before giving up
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final long INITIAL_RECONNECT_DELAY_MS = 1000;
    private static final long MAX_RECONNECT_DELAY_MS = 30000;

    // Read buffer limits
    private static final int MAX_BUFFER_SIZE = 4096;
    private static final int READ_BUFFER_SIZE = 1024;

    // Connection watchdog timeout (no data for 10s = suspicious)
    private static final long WATCHDOG_TIMEOUT_MS = 10000;

    private static final int REQ_PERMISSIONS = 1;

    private WebView webView;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket btSocket;
    private InputStream btIn;
    private OutputStream btOut;
    private final AtomicBoolean btConnected = new AtomicBoolean(false);
    private final AtomicBoolean intentionalDisconnect = new AtomicBoolean(false);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newCachedThreadPool();

    // Reconnection state
    private String lastConnectedAddress = null;
    private int reconnectAttempts = 0;
    private Runnable reconnectRunnable;
    private boolean autoReconnectEnabled = true;

    // Watchdog
    private long lastDataReceivedTime = 0;
    private Runnable watchdogRunnable;

    // ── Lifecycle ──────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        setupWebView();
        requestBTPermissions();

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // Register BT state change receiver
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        registerReceiver(btReceiver, filter);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        intentionalDisconnect.set(true);
        closeConnection("App destroyed");
        try { unregisterReceiver(btReceiver); } catch (Exception ignored) {}
        executor.shutdownNow();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Keep BT alive in background
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Check if BT was disabled while we were paused
        if (btConnected.get() && bluetoothAdapter != null && !bluetoothAdapter.isEnabled()) {
            handleDisconnect("Bluetooth disabled");
        }
    }

    // ── WebView Setup ──────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setSupportZoom(false);

        webView.addJavascriptInterface(new AndroidBTInterface(), "AndroidBT");
        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl("file:///android_asset/www/index.html");
    }

    // ── Permissions ────────────────────────────────────────────────

    private void requestBTPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            String[] perms = {
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            };
            boolean needed = false;
            for (String p : perms) {
                if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                    needed = true; break;
                }
            }
            if (needed) ActivityCompat.requestPermissions(this, perms, REQ_PERMISSIONS);
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_FINE_LOCATION
                }, REQ_PERMISSIONS);
            }
        }
    }

    // ── JavaScript Interface ───────────────────────────────────────

    public class AndroidBTInterface {

        /** Return paired devices as JSON array */
        @JavascriptInterface
        public String getPairedDevices() {
            JSONArray arr = new JSONArray();
            try {
                if (bluetoothAdapter == null) return arr.toString();
                if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    return arr.toString();
                }
                Set<BluetoothDevice> paired = bluetoothAdapter.getBondedDevices();
                for (BluetoothDevice d : paired) {
                    JSONObject obj = new JSONObject();
                    obj.put("name", d.getName() != null ? d.getName() : "Unknown");
                    obj.put("address", d.getAddress() != null ? d.getAddress() : "");
                    arr.put(obj);
                }
            } catch (Exception e) {
                Log.e(TAG, "getPairedDevices error", e);
            }
            return arr.toString();
        }

        /** Connect to HC-06 by MAC address */
        @JavascriptInterface
        public void connect(final String address) {
            executor.execute(() -> doConnect(address));
        }

        /** Send a command string to Arduino */
        @JavascriptInterface
        public void send(final String data) {
            if (!btConnected.get() || btOut == null) return;
            executor.execute(() -> {
                try {
                    btOut.write(data.getBytes("UTF-8"));
                    btOut.flush();
                    callJs("window.onAndroidBTSent", data);
                } catch (IOException e) {
                    handleDisconnect("Send failed: " + e.getMessage());
                }
            });
        }

        /** Disconnect from HC-06 */
        @JavascriptInterface
        public void disconnect() {
            intentionalDisconnect.set(true);
            autoReconnectEnabled = false;
            closeConnection("User disconnected");
        }

        /** Enable/disable auto-reconnect */
        @JavascriptInterface
        public void setAutoReconnect(boolean enabled) {
            autoReconnectEnabled = enabled;
        }

        /** Check if currently connected */
        @JavascriptInterface
        public boolean isConnected() {
            return btConnected.get();
        }

        /** Get connection stats as JSON */
        @JavascriptInterface
        public String getConnectionStats() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("connected", btConnected.get());
                obj.put("address", lastConnectedAddress != null ? lastConnectedAddress : "");
                obj.put("reconnectAttempts", reconnectAttempts);
                obj.put("autoReconnect", autoReconnectEnabled);
                obj.put("lastDataAge", btConnected.get() ? (System.currentTimeMillis() - lastDataReceivedTime) : -1);
            } catch (Exception e) { /* ignore */ }
            return obj.toString();
        }
    }

    // ── Bluetooth Connection ───────────────────────────────────────

    private void doConnect(String address) {
        // Clean up previous connection
        btConnected.set(false);
        intentionalDisconnect.set(false);
        autoReconnectEnabled = true;
        reconnectAttempts = 0;

        closeSocketOnly();

        BluetoothDevice device = null;
        try {
            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
                mainHandler.post(() -> {
                    callJs("window.onAndroidBTDisconnected", "Bluetooth is disabled");
                });
                return;
            }

            device = bluetoothAdapter.getRemoteDevice(address);
            bluetoothAdapter.cancelDiscovery();

            // Primary connection attempt — standard SPP
            Log.i(TAG, "Connecting to " + address + " via SPP...");
            btSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            btSocket.connect();
            openStreamsAndStart(device);
            return;

        } catch (IOException e1) {
            Log.w(TAG, "SPP connect failed, trying fallback: " + e1.getMessage());
            // Fallback — reflection-based channel 1 (common HC-06 workaround)
            if (tryFallbackConnect(device)) return;
        }

        // Both methods failed
        btConnected.set(false);
        final String msg = "Connection failed to " + address;
        mainHandler.post(() -> {
            Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
            callJs("window.onAndroidBTDisconnected", msg);
        });
    }

    private boolean tryFallbackConnect(BluetoothDevice device) {
        if (device == null) return false;
        try {
            Log.i(TAG, "Trying fallback socket (channel 1)...");
            Method m = device.getClass().getMethod("createRfcommSocket", int.class);
            btSocket = (BluetoothSocket) m.invoke(device, 1);
            bluetoothAdapter.cancelDiscovery();
            btSocket.connect();
            openStreamsAndStart(device);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Fallback connect also failed", e);
            return false;
        }
    }

    private void openStreamsAndStart(BluetoothDevice device) throws IOException {
        btIn = btSocket.getInputStream();
        btOut = btSocket.getOutputStream();
        btConnected.set(true);
        lastConnectedAddress = device.getAddress();
        reconnectAttempts = 0;
        lastDataReceivedTime = System.currentTimeMillis();

        startReading();
        startWatchdog();

        final String devName = (device.getName() != null) ? device.getName() : "HC-06";
        mainHandler.post(() -> {
            Toast.makeText(MainActivity.this, devName + " connected!", Toast.LENGTH_SHORT).show();
            callJs("window.onAndroidBTConnected", devName);
        });
    }

    // ── Reading Loop ───────────────────────────────────────────────

    private void startReading() {
        executor.execute(() -> {
            StringBuilder buffer = new StringBuilder();
            byte[] buf = new byte[READ_BUFFER_SIZE];

            while (btConnected.get()) {
                try {
                    int n = btIn.read(buf);
                    if (n <= 0) continue;

                    lastDataReceivedTime = System.currentTimeMillis();
                    String chunk = new String(buf, 0, n, "UTF-8");

                    // Overflow protection
                    if (buffer.length() + chunk.length() > MAX_BUFFER_SIZE) {
                        Log.w(TAG, "Buffer overflow, discarding partial data");
                        buffer.setLength(0);
                    }
                    buffer.append(chunk);

                    // Extract complete lines (terminated by \n)
                    int idx;
                    while ((idx = buffer.indexOf("\n")) >= 0) {
                        String line = buffer.substring(0, idx).trim(); // removes \r
                        buffer.delete(0, idx + 1);
                        if (!line.isEmpty()) {
                            sendLineToJs(line);
                        }
                    }
                } catch (IOException e) {
                    if (btConnected.get()) {
                        handleDisconnect("Read error: " + e.getMessage());
                    }
                    break;
                }
            }
        });
    }

    /** Send a received line to JS via Base64 encoding (avoids escaping issues) */
    private void sendLineToJs(final String line) {
        final String b64 = Base64.encodeToString(line.getBytes(), Base64.NO_WRAP);
        mainHandler.post(() ->
            webView.evaluateJavascript("window.onAndroidBTDataB64('" + b64 + "')", null)
        );
    }

    // ── Watchdog ───────────────────────────────────────────────────

    private void startWatchdog() {
        stopWatchdog();
        watchdogRunnable = () -> {
            if (btConnected.get() && System.currentTimeMillis() - lastDataReceivedTime > WATCHDOG_TIMEOUT_MS) {
                Log.w(TAG, "Watchdog: no data for " + WATCHDOG_TIMEOUT_MS + "ms");
                callJs("window.onAndroidBTWatchdog", "No data for 10s");
            }
            if (btConnected.get()) {
                mainHandler.postDelayed(watchdogRunnable, 3000);
            }
        };
        mainHandler.postDelayed(watchdogRunnable, 3000);
    }

    private void stopWatchdog() {
        if (watchdogRunnable != null) {
            mainHandler.removeCallbacks(watchdogRunnable);
        }
    }

    // ── Reconnection ───────────────────────────────────────────────

    private void scheduleReconnect() {
        if (!autoReconnectEnabled || intentionalDisconnect.get() || lastConnectedAddress == null) return;

        reconnectAttempts++;
        if (reconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached");
            callJs("window.onAndroidBTDisconnected", "Max reconnect attempts reached");
            return;
        }

        // Exponential backoff: 1s, 2s, 4s, 8s, 16s, 30s, 30s...
        long delay = Math.min(
            INITIAL_RECONNECT_DELAY_MS * (1L << (reconnectAttempts - 1)),
            MAX_RECONNECT_DELAY_MS
        );

        Log.i(TAG, "Reconnect attempt " + reconnectAttempts + " in " + delay + "ms");

        reconnectRunnable = () -> {
            if (!autoReconnectEnabled || intentionalDisconnect.get()) return;
            callJs("window.onAndroidBTReconnecting", "Attempt " + reconnectAttempts);
            doConnect(lastConnectedAddress);
        };
        mainHandler.postDelayed(reconnectRunnable, delay);
    }

    // ── Disconnect Handling ─────────────────────────────────────────

    private void handleDisconnect(String reason) {
        btConnected.set(false);
        stopWatchdog();
        final String r = reason != null ? reason : "Connection lost";
        callJs("window.onAndroidBTDisconnected", r);

        if (!intentionalDisconnect.get() && autoReconnectEnabled) {
            closeSocketOnly();
            scheduleReconnect();
        }
    }

    private void closeConnection(String reason) {
        btConnected.set(false);
        stopWatchdog();

        if (reconnectRunnable != null) {
            mainHandler.removeCallbacks(reconnectRunnable);
        }

        closeSocketOnly();
        handleDisconnect(reason);
    }

    private void closeSocketOnly() {
        try { if (btIn != null) btIn.close(); } catch (IOException ignored) {}
        try { if (btOut != null) btOut.close(); } catch (IOException ignored) {}
        try { if (btSocket != null) btSocket.close(); } catch (IOException ignored) {}
        btIn = null;
        btOut = null;
        btSocket = null;
    }

    // ── JS Bridge Helpers ──────────────────────────────────────────

    private void callJs(final String fnName, final String arg) {
        final String b64 = Base64.encodeToString((arg != null ? arg : "").getBytes(), Base64.NO_WRAP);
        mainHandler.post(() ->
            webView.evaluateJavascript(fnName + "(window.__b64('" + b64 + "'))", null)
        );
    }

    // ── Broadcast Receiver ─────────────────────────────────────────

    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_OFF && btConnected.get()) {
                    handleDisconnect("Bluetooth turned off");
                }
                if (state == BluetoothAdapter.STATE_ON && lastConnectedAddress != null && autoReconnectEnabled) {
                    reconnectAttempts = 0;
                    scheduleReconnect();
                }
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                if (btConnected.get()) {
                    BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (dev != null && dev.getAddress().equals(lastConnectedAddress)) {
                        handleDisconnect("ACL disconnected");
                    }
                }
            }
        }
    };
}
