package com.glassvision.client;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Config UI: API key input, start/stop server, IP display, log.
 * Follows glass-notify-client MainActivity pattern.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "glass_vision_prefs";
    private static final String KEY_API_KEY = "api_key";
    private static final int MAX_LOG_LINES = 100;

    private EditText editApiKey;
    private Button btnToggle;
    private Button btnClear;
    private TextView textIp;
    private TextView textStatus;
    private LinearLayout logList;
    private ScrollView logScroll;

    private boolean serviceRunning = false;
    private int logCount = 0;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (VisionServerService.ACTION_STATUS.equals(action)) {
                String status = intent.getStringExtra(VisionServerService.EXTRA_STATUS);
                if (status != null) {
                    textStatus.setText(status);
                }
            } else if (VisionServerService.ACTION_LOG.equals(action)) {
                String log = intent.getStringExtra(VisionServerService.EXTRA_LOG);
                if (log != null) {
                    addLog(log);
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textIp = (TextView) findViewById(R.id.text_ip);
        editApiKey = (EditText) findViewById(R.id.edit_api_key);
        btnToggle = (Button) findViewById(R.id.btn_toggle);
        btnClear = (Button) findViewById(R.id.btn_clear);
        textStatus = (TextView) findViewById(R.id.text_status);
        logList = (LinearLayout) findViewById(R.id.log_list);
        logScroll = (ScrollView) findViewById(R.id.log_scroll);

        // Show phone IP
        updateIpDisplay();

        // Restore saved API key
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String savedKey = prefs.getString(KEY_API_KEY, "");
        editApiKey.setText(savedKey);

        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (serviceRunning) {
                    stopServer();
                } else {
                    startServer();
                }
            }
        });

        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (VisionServerService.instance != null) {
                    VisionServerService.instance.clearHistory();
                }
                addLog("History cleared");
            }
        });

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(VisionServerService.ACTION_STATUS);
        filter.addAction(VisionServerService.ACTION_LOG);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
        updateIpDisplay();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(statusReceiver);
    }

    private void startServer() {
        String apiKey = editApiKey.getText().toString().trim();
        if (apiKey.isEmpty()) {
            textStatus.setText("Enter API key first");
            return;
        }

        // Save API key
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_API_KEY, apiKey).apply();

        Intent intent = new Intent(this, VisionServerService.class);
        intent.putExtra(VisionServerService.EXTRA_API_KEY, apiKey);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        serviceRunning = true;
        btnToggle.setText(R.string.btn_stop);
        editApiKey.setEnabled(false);
        textStatus.setText("Starting...");
        addLog("Server starting...");
    }

    private void stopServer() {
        stopService(new Intent(this, VisionServerService.class));
        serviceRunning = false;
        btnToggle.setText(R.string.btn_start);
        editApiKey.setEnabled(true);
        textStatus.setText(R.string.status_ready);
        addLog("Server stopped");
    }

    private void updateIpDisplay() {
        String ip = getWifiIpAddress();
        textIp.setText("IP: " + ip + ":9877");
    }

    private String getWifiIpAddress() {
        WifiManager wifiManager = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) return "?.?.?.?";
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        int ip = wifiInfo.getIpAddress();
        if (ip == 0) return "no wifi";
        return String.format(Locale.US, "%d.%d.%d.%d",
                ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
    }

    private void addLog(String message) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.US);
        String time = sdf.format(new Date());

        TextView tv = new TextView(this);
        tv.setText(time + "  " + message);
        tv.setTextSize(12);
        tv.setPadding(0, 2, 0, 2);
        logList.addView(tv);
        logCount++;

        while (logCount > MAX_LOG_LINES) {
            logList.removeViewAt(0);
            logCount--;
        }

        logScroll.post(new Runnable() {
            @Override
            public void run() {
                logScroll.fullScroll(ScrollView.FOCUS_DOWN);
            }
        });
    }
}
