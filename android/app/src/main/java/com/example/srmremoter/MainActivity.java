package com.example.srmremoter;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.BackEventCompat;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends AppCompatActivity {
    private enum ConnectionUiState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    private enum DebugCategory {
        TRANSPORT,
        DATA
    }

    private static final class DebugEntry {
        final String timestamp;
        final String message;
        final DebugCategory category;

        DebugEntry(String timestamp, String message, DebugCategory category) {
            this.timestamp = timestamp;
            this.message = message;
            this.category = category;
        }

        String formatted() {
            return timestamp + "  " + message;
        }
    }

    private static final String PREFS = "remote_settings";
    private static final String PREF_DEVICE = "device_address";
    private static final String PREF_AUTO_RECONNECT = "auto_reconnect";
    private static final String PREF_RECONNECT_AFTER_DROP = "reconnect_after_drop";
    private static final String PREF_APPEND_NEWLINE = "append_newline";
    private static final String PREF_KEEP_SCREEN_ON = "keep_screen_on";
    private static final String PREF_EXPERIMENTAL_SPP = "experimental_spp";
    private static final String PREF_TRANSMIT_CONTROL_FRAMES = "transmit_control_frames";
    private static final String PREF_CONTROL_RATE_HZ = "control_rate_hz";
    private static final int DEBUG_FILTER_ALL = 0;
    private static final int DEBUG_FILTER_TRANSPORT = 1;
    private static final int DEBUG_FILTER_DATA = 2;
    private static final int MAX_DEBUG_ENTRIES = 500;
    private static final long COMMAND_FEEDBACK_INTERVAL_MS = 50L;
    private static final long LOOPBACK_LOG_INTERVAL_MS = 100L;
    private static final long DEBUG_REFRESH_INTERVAL_MS = 50L;
    private static final long SCAN_RESULTS_REFRESH_INTERVAL_MS = 200L;
    private static final long RECONNECT_BASE_DELAY_MS = 1_000L;
    private static final long RECONNECT_MAX_DELAY_MS = 15_000L;
    private static final long WALLPAPER_REFRESH_DELAY_MS = 350L;
    private static final long DIALOG_BLUR_ENTER_DURATION_MS = 320L;
    private static final long DIALOG_BLUR_EXIT_DURATION_MS = 140L;
    private static final long DIALOG_BLUR_RESTORE_DURATION_MS = 180L;
    private static final float DIALOG_BLUR_RADIUS_DP = 28f;
    private static final float DIALOG_DIM_AMOUNT = 0.24f;
    private static final DateTimeFormatter DEBUG_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.getDefault());

    private final int[] switchIds = {
            R.id.switch1, R.id.switch2, R.id.switch3,
            R.id.switch4, R.id.switch5, R.id.switch6
    };
    private final List<MaterialSwitch> commandSwitches = new ArrayList<>();
    private final List<BluetoothDeviceListAdapter.Item> discoveredDevices = new ArrayList<>();
    private final Handler protocolHandler = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService controlScheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "control-heartbeat");
                thread.setDaemon(true);
                return thread;
            });
    private final SrmProtocol protocol = new SrmProtocol();
    private final ArrayDeque<DebugEntry> debugEntries = new ArrayDeque<>();
    private final Map<AlertDialog, ValueAnimator> dialogBlurAnimators = new HashMap<>();

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothFfe1Client ffe1Client;
    private BluetoothSppClient sppClient;
    private BluetoothLeScanner bleScanner;
    private SharedPreferences preferences;
    private GamepadView gamepad;
    private TextView commandText;
    private TextView receivedText;
    private TextView receivedMeta;
    private TextView connectionStatusText;
    private TextView settingsConnectionStatus;
    private MaterialButton settingsDisconnectButton;
    private MaterialSwitch settingsGamepadRelaySwitch;
    private View connectionIndicator;
    private StatusLightView connectionIndicatorDot;
    private MaterialButton debugButton;
    private GamepadRelay gamepadRelay;
    private boolean synchronizingGamepadRelaySwitch;
    private volatile boolean connected;
    private volatile boolean activeSpp;
    private volatile boolean activityStarted;
    private volatile boolean transmitControlFrames = true;
    private ScheduledFuture<?> controlHeartbeat;
    private boolean reconnectSuppressed;
    private boolean manualConnectionInProgress;
    private boolean currentSessionWasConnected;
    private int reconnectAttempt;
    private boolean wallpaperReceiverRegistered;
    private boolean classicBluetoothReceiverRegistered;
    private boolean startupInitialized;
    private boolean scanning;
    private ConnectionUiState connectionUiState = ConnectionUiState.DISCONNECTED;
    private ObjectAnimator connectionPulse;
    private String connectedDeviceName = "";
    private BluetoothDeviceListAdapter deviceListAdapter;
    private AlertDialog scanDialog;
    private ProgressBar scanProgress;
    private ListView scanDeviceList;
    private TextView scanStateText;
    private TextView scanEmptyState;
    private BluetoothDevice pendingSppDevice;
    private String pendingSppPin;
    private TextView expandedDebugText;
    private TextView expandedDebugSummary;
    private ScrollView expandedDebugScroll;
    private int expandedDebugFilter = DEBUG_FILTER_ALL;
    private boolean expandedDebugAutoScroll = true;
    private String pendingCommandLabel;
    private boolean pendingLoopbackLog;
    private boolean controlFeedbackScheduled;
    private boolean debugRefreshScheduled;
    private boolean scanResultsRefreshScheduled;
    private long lastLoopbackLogAt;
    private final SrmProtocol.StreamDecoder incomingDecoder = new SrmProtocol.StreamDecoder();

    private final Runnable flushControlFeedback = new Runnable() {
        @Override
        public void run() {
            controlFeedbackScheduled = false;
            if (pendingCommandLabel != null) {
                commandText.setText(pendingCommandLabel);
                pendingCommandLabel = null;
            }
            if (!connected && pendingLoopbackLog) {
                long now = SystemClock.uptimeMillis();
                long remaining = LOOPBACK_LOG_INTERVAL_MS - (now - lastLoopbackLogAt);
                if (remaining <= 0L) {
                    pendingLoopbackLog = false;
                    lastLoopbackLogAt = now;
                    appendControlLoopback();
                }
            }
            scheduleControlFeedback();
        }
    };

    private final Runnable refreshDebugViewsTask = () -> {
        debugRefreshScheduled = false;
        refreshDebugViews();
    };

    private final Runnable refreshScanResultsTask = () -> {
        scanResultsRefreshScheduled = false;
        refreshScanResults();
    };

    private final Runnable reconnectAfterDrop = new Runnable() {
        @Override
        public void run() {
            if (!shouldReconnectAfterDrop()) return;
            if (bluetoothAdapter != null && !bluetoothAdapter.isEnabled()) {
                scheduleReconnectAfterDrop();
                return;
            }
            reconnectSavedDevice();
        }
    };

    private final Runnable refreshWallpaperColors = () -> {
        if (!connected && connectionUiState == ConnectionUiState.DISCONNECTED
                && !isFinishing() && !isDestroyed()) {
            recreate();
        }
    };

    private final BroadcastReceiver wallpaperChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Intent.ACTION_WALLPAPER_CHANGED.equals(intent.getAction())) return;
            protocolHandler.removeCallbacks(refreshWallpaperColors);
            protocolHandler.postDelayed(refreshWallpaperColors, WALLPAPER_REFRESH_DELAY_MS);
        }
    };

    private final Runnable scanTimeout = () -> {
        if (!scanning) return;
        cancelDiscovery();
        refreshScanResultsNow();
        if (scanProgress != null) scanProgress.setVisibility(android.view.View.INVISIBLE);
        if (scanDialog != null) scanDialog.setTitle(R.string.scan_finished);
        appendReceived(getString(R.string.scan_finished_log,
                discoveredDevices.size()) + "\n");
    };

    private final ScanCallback bleScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (!scanning || scanDialog == null) return;
            addOrUpdateDevice(result.getDevice(), result.getRssi(), true, false);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            if (!scanning || scanDialog == null) return;
            for (ScanResult result : results) {
                addOrUpdateDevice(result.getDevice(), result.getRssi(), true, false);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            if (!scanning) return;
            scanning = false;
            bleScanner = null;
            protocolHandler.removeCallbacks(scanTimeout);
            refreshScanResultsNow();
            if (scanProgress != null) scanProgress.setVisibility(android.view.View.INVISIBLE);
            if (scanStateText != null) scanStateText.setText(R.string.scan_connection_failed);
            if (!connected) setConnectionUiState(ConnectionUiState.ERROR);
            appendReceived(getString(R.string.scan_failed_code, errorCode) + "\n");
            toast(R.string.scan_start_failed);
        }
    };

    private final BroadcastReceiver classicBluetoothReceiver = new BroadcastReceiver() {
        @Override
        @SuppressLint("MissingPermission")
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            BluetoothDevice device = bluetoothDeviceExtra(intent);
            if (BluetoothDevice.ACTION_FOUND.equals(action) && device != null && isSppMode()) {
                int rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                addOrUpdateDevice(device, rssi, rssi != Short.MIN_VALUE, true);
                return;
            }
            if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)
                    && scanning && isSppMode()) {
                scanning = false;
                protocolHandler.removeCallbacks(scanTimeout);
                refreshScanResultsNow();
                if (scanProgress != null) scanProgress.setVisibility(View.INVISIBLE);
                if (scanDialog != null) scanDialog.setTitle(R.string.scan_finished);
                appendReceived(getString(R.string.scan_finished_log,
                        discoveredDevices.size()) + "\n");
                return;
            }
            if (device == null || pendingSppDevice == null
                    || !device.getAddress().equals(pendingSppDevice.getAddress())) return;

            if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(action)) {
                submitPairingPin(device);
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,
                        BluetoothDevice.ERROR);
                int previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                        BluetoothDevice.ERROR);
                if (state == BluetoothDevice.BOND_BONDED) {
                    BluetoothDevice pairedDevice = pendingSppDevice;
                    pendingSppDevice = null;
                    pendingSppPin = null;
                    connectSpp(pairedDevice);
                } else if (state == BluetoothDevice.BOND_NONE
                        && previous == BluetoothDevice.BOND_BONDING) {
                    pendingSppDevice = null;
                    pendingSppPin = null;
                    setConnectedState(false);
                    setConnectionUiState(ConnectionUiState.ERROR);
                    showScanErrorFeedback();
                    appendReceived(getString(R.string.spp_pairing_failed) + "\n");
                    toast(R.string.spp_pairing_failed);
                }
            }
        }
    };

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean granted = true;
                for (Map.Entry<String, Boolean> entry : result.entrySet()) {
                    granted &= Boolean.TRUE.equals(entry.getValue());
                }
                if (granted && hasBluetoothPermissions()) {
                    ensureBluetoothAndChooseDevice();
                } else {
                    toast(R.string.permission_denied);
                }
            });

    private final ActivityResultLauncher<Intent> enableBluetoothLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (hasBluetoothPermissions() && bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                    showDeviceScanner();
                } else {
                    toast(R.string.bluetooth_disabled);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        View content = getWindow().getDecorView();
        ViewTreeObserver.OnDrawListener firstDrawListener = new ViewTreeObserver.OnDrawListener() {
            @Override
            public void onDraw() {
                content.post(() -> {
                    if (content.getViewTreeObserver().isAlive()) {
                        content.getViewTreeObserver().removeOnDrawListener(this);
                    }
                    initializeAfterFirstFrame();
                });
            }
        };
        content.getViewTreeObserver().addOnDrawListener(firstDrawListener);
    }

    private void initializeAfterFirstFrame() {
        if (startupInitialized || isFinishing() || isDestroyed()) return;
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        transmitControlFrames = preferences.getBoolean(
                PREF_TRANSMIT_CONTROL_FRAMES, true);
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        bluetoothAdapter = manager == null ? null : manager.getAdapter();

        gamepadRelay = new GamepadRelay(this, this::onRelayControllerDisconnected);
        bindViews();
        ffe1Client = new BluetoothFfe1Client(this, new Ffe1Listener());
        sppClient = new BluetoothSppClient(bluetoothAdapter, new SppListener());
        registerClassicBluetoothReceiver();
        classicBluetoothReceiverRegistered = true;
        applyKeepScreenOn(preferences.getBoolean(PREF_KEEP_SCREEN_ON, true));
        setConnectedState(false);
        setConnectionUiState(ConnectionUiState.DISCONNECTED);
        startupInitialized = true;

        if (activityStarted) startRuntimeWork();

        if (preferences.getBoolean(PREF_AUTO_RECONNECT, false)) {
            reconnectSavedDevice();
        }
    }

    private void bindViews() {
        gamepad = findViewById(R.id.gamepad);
        commandText = findViewById(R.id.commandText);
        receivedText = findViewById(R.id.receivedText);
        receivedMeta = findViewById(R.id.receivedMeta);
        connectionIndicator = findViewById(R.id.connectionIndicator);
        connectionIndicatorDot = findViewById(R.id.connectionIndicatorDot);
        connectionStatusText = findViewById(R.id.connectionStatusText);
        debugButton = findViewById(R.id.debugButton);

        receivedText.setMovementMethod(new ScrollingMovementMethod());
        android.view.View.OnClickListener openDebugLog = view -> {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            showDebugLog();
        };
        findViewById(R.id.receivedPanel).setOnClickListener(openDebugLog);
        receivedText.setOnClickListener(openDebugLog);
        gamepad.setCommandListener((frame, label) -> {
            dispatchFrame(frame, label);
        });
        findViewById(R.id.settingsButton).setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            showSettings();
        });
        connectionIndicator.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            showSettings();
        });
        debugButton.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            showDebugSender();
        });
        for (int index = 0; index < switchIds.length; index++) {
            MaterialSwitch commandSwitch = findViewById(switchIds[index]);
            int switchNumber = index + 1;
            commandSwitch.setOnCheckedChangeListener((button, checked) -> {
                button.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                String frame = "SW" + switchNumber + "," + (checked ? "1" : "0") + "\n";
                dispatchFrame(frame, getString(R.string.switch_state, switchNumber,
                        checked ? "ON" : "OFF"));
            });
            commandSwitches.add(commandSwitch);
        }
        refreshDebugViews();
    }

    private void ensureBluetoothAndChooseDevice() {
        if (bluetoothAdapter == null) {
            toast(R.string.bluetooth_unavailable);
            return;
        }
        if (!hasBluetoothPermissions()) {
            permissionLauncher.launch(new String[] {
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            });
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            enableBluetoothLauncher.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return;
        }
        showDeviceScanner();
    }

    private void showDeviceScanner() {
        if (!hasBluetoothPermissions()) {
            return;
        }
        if (scanDialog != null) return;
        manualConnectionInProgress = true;
        cancelReconnectAfterDrop(true);
        if (connectionUiState == ConnectionUiState.CONNECTING) {
            if (activeSpp) sppClient.disconnect();
            else ffe1Client.disconnect();
        }
        try {
            discoveredDevices.clear();
            LinearLayout content = (LinearLayout) getLayoutInflater()
                    .inflate(R.layout.dialog_bluetooth_scan, null, false);
            scanProgress = content.findViewById(R.id.scanProgress);
            scanStateText = content.findViewById(R.id.scanStateText);
            scanEmptyState = content.findViewById(R.id.scanEmptyState);
            scanDeviceList = content.findViewById(R.id.deviceList);
            deviceListAdapter = new BluetoothDeviceListAdapter(this, discoveredDevices);
            scanDeviceList.setAdapter(deviceListAdapter);
            scanDeviceList.setEmptyView(scanEmptyState);
            scanDeviceList.setOnItemClickListener((parent, view, position, id) -> {
                Object selectedItem = parent.getItemAtPosition(position);
                if (!(selectedItem instanceof BluetoothDeviceListAdapter.Item)) return;
                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                scanDeviceList.setItemChecked(position, true);
                selectScannedDevice((BluetoothDeviceListAdapter.Item) selectedItem);
            });

            scanDialog = new MaterialAlertDialogBuilder(this)
                    .setTitle(isSppMode() ? R.string.scan_title_spp : R.string.scan_title)
                    .setView(content)
                    .setNeutralButton(R.string.scan_again, null)
                    .setNegativeButton(R.string.action_cancel, null)
                    .create();
            scanDialog.setOnShowListener(dialog -> scanDialog
                    .getButton(android.content.DialogInterface.BUTTON_NEUTRAL)
                    .setOnClickListener(view -> startDiscovery()));
            scanDialog.setOnDismissListener(dialog -> {
                cancelDiscovery();
                cancelScanResultsRefresh();
                scanDialog = null;
                scanProgress = null;
                scanDeviceList = null;
                scanStateText = null;
                scanEmptyState = null;
                deviceListAdapter = null;
                manualConnectionInProgress = false;
                scheduleReconnectAfterDrop();
            });
            showBlurredDialog(scanDialog);
            startDiscovery();
        } catch (SecurityException error) {
            manualConnectionInProgress = false;
            permissionLauncher.launch(new String[] {
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            });
        }
    }

    private void startDiscovery() {
        if (!hasBluetoothPermissions() || bluetoothAdapter == null) return;
        try {
            cancelDiscovery();
            cancelScanResultsRefresh();
            protocolHandler.removeCallbacks(scanTimeout);
            discoveredDevices.clear();
            if (deviceListAdapter != null) deviceListAdapter.notifyDataSetChanged();
            if (scanProgress != null) scanProgress.setVisibility(android.view.View.VISIBLE);
            if (scanStateText != null) scanStateText.setText(isSppMode()
                    ? R.string.scan_searching_spp : R.string.scan_searching);
            if (scanEmptyState != null) scanEmptyState.setText(R.string.scan_waiting_devices);
            if (scanDeviceList != null) scanDeviceList.setEnabled(true);
            if (scanDialog != null) scanDialog.setTitle(isSppMode()
                    ? R.string.scan_title_spp : R.string.scan_title);

            if (isSppMode()) {
                for (BluetoothDevice bonded : bluetoothAdapter.getBondedDevices()) {
                    addOrUpdateDevice(bonded, 0, false, true);
                }
                scanning = bluetoothAdapter.startDiscovery();
                if (scanning) {
                    appendReceived(getString(R.string.scan_started_spp_log) + "\n");
                    protocolHandler.postDelayed(scanTimeout, 15000L);
                } else {
                    handleScanStartFailure();
                }
            } else {
                bleScanner = bluetoothAdapter.getBluetoothLeScanner();
                if (bleScanner == null) {
                    handleScanStartFailure();
                } else {
                    ScanSettings settings = new ScanSettings.Builder()
                            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                            .build();
                    bleScanner.startScan(null, settings, bleScanCallback);
                    scanning = true;
                    appendReceived(getString(R.string.scan_started_log) + "\n");
                    protocolHandler.postDelayed(scanTimeout, 10000L);
                }
            }
        } catch (SecurityException error) {
            toast(R.string.permission_denied);
        }
    }

    private void handleScanStartFailure() {
        scanning = false;
        if (scanProgress != null) scanProgress.setVisibility(View.INVISIBLE);
        appendReceived(getString(R.string.scan_start_failed) + "\n");
        toast(R.string.scan_start_failed);
    }

    @SuppressLint("MissingPermission")
    private void cancelDiscovery() {
        protocolHandler.removeCallbacks(scanTimeout);
        scanning = false;
        if (bluetoothAdapter == null || !hasBluetoothPermissions()) return;
        BluetoothLeScanner scanner = bleScanner;
        bleScanner = null;
        try {
            if (scanner != null) scanner.stopScan(bleScanCallback);
            if (bluetoothAdapter.isDiscovering()) bluetoothAdapter.cancelDiscovery();
        } catch (RuntimeException error) {
            appendReceived(getString(R.string.scan_stop_failed_log,
                    conciseRuntimeMessage(error)) + "\n");
        }
    }

    private void selectScannedDevice(BluetoothDeviceListAdapter.Item item) {
        if (!hasBluetoothPermissions()) return;
        manualConnectionInProgress = true;
        reconnectSuppressed = false;
        currentSessionWasConnected = false;
        cancelReconnectAfterDrop(true);
        BluetoothDevice device = item.device;
        try {
            cancelDiscovery();
            if (scanDialog != null) scanDialog.setTitle(R.string.scan_connecting_title);
            if (scanProgress != null) scanProgress.setVisibility(View.VISIBLE);
            if (scanStateText != null) {
                scanStateText.setText(getString(R.string.scan_connecting_device, item.name));
            }
            if (scanDeviceList != null) scanDeviceList.setEnabled(false);
            if (isSppMode()) {
                beginSppConnection(item);
            } else {
                connectFfe1(device);
            }
        } catch (SecurityException error) {
            toast(R.string.permission_denied);
        }
    }

    @SuppressLint("MissingPermission")
    private void addOrUpdateDevice(BluetoothDevice device, int rssi, boolean hasRssi,
                                   boolean spp) {
        if (!hasBluetoothPermissions()) return;
        String address;
        try {
            address = device.getAddress();
        } catch (SecurityException error) {
            return;
        }
        BluetoothDeviceListAdapter.Item item = null;
        for (BluetoothDeviceListAdapter.Item candidate : discoveredDevices) {
            if (candidate.device.getAddress().equals(address)) {
                item = candidate;
                break;
            }
        }
        if (item == null) {
            item = new BluetoothDeviceListAdapter.Item(device, safeDeviceName(device), spp);
            item.bonded = device.getBondState() == BluetoothDevice.BOND_BONDED;
            discoveredDevices.add(item);
            // ArrayAdapter observes this list directly, so count changes must be synchronous.
            if (deviceListAdapter != null) deviceListAdapter.notifyDataSetChanged();
        } else if (item.name.equals(address)) {
            item.name = safeDeviceName(device);
        }
        if (hasRssi) {
            item.rssi = rssi;
            item.hasRssi = true;
        }
        scheduleScanResultsRefresh();
    }

    private void scheduleScanResultsRefresh() {
        if (scanResultsRefreshScheduled) return;
        scanResultsRefreshScheduled = true;
        protocolHandler.postDelayed(
                refreshScanResultsTask, SCAN_RESULTS_REFRESH_INTERVAL_MS);
    }

    private void refreshScanResultsNow() {
        cancelScanResultsRefresh();
        refreshScanResults();
    }

    private void refreshScanResults() {
        discoveredDevices.sort(Comparator.comparing(
                value -> value.name.toLowerCase(Locale.ROOT)));
        if (deviceListAdapter != null) deviceListAdapter.notifyDataSetChanged();
        updateScanDeviceCount();
    }

    private void cancelScanResultsRefresh() {
        protocolHandler.removeCallbacks(refreshScanResultsTask);
        scanResultsRefreshScheduled = false;
    }

    private void updateScanDeviceCount() {
        if (scanStateText == null || (scanDeviceList != null && !scanDeviceList.isEnabled())) return;
        if (discoveredDevices.isEmpty()) {
            scanStateText.setText(isSppMode()
                    ? R.string.scan_searching_spp : R.string.scan_searching);
        } else {
            scanStateText.setText(getString(R.string.scan_found_devices,
                    discoveredDevices.size()));
        }
    }

    @SuppressLint("MissingPermission")
    private void connectFfe1(BluetoothDevice device) {
        preferences.edit().putString(PREF_DEVICE, device.getAddress()).apply();
        activeSpp = false;
        connectedDeviceName = safeDeviceName(device);
        cancelDiscovery();
        ffe1Client.connect(device);
    }

    @SuppressLint("MissingPermission")
    private void beginSppConnection(BluetoothDeviceListAdapter.Item item) {
        if (item.device.getBondState() == BluetoothDevice.BOND_BONDED) {
            connectSpp(item.device);
            return;
        }

        int padding = Math.round(getResources().getDisplayMetrics().density * 20f);
        EditText pinInput = new EditText(this);
        pinInput.setHint(R.string.spp_pairing_hint);
        pinInput.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinInput.setText(R.string.spp_default_pin);
        pinInput.selectAll();
        LinearLayout inputContainer = new LinearLayout(this);
        inputContainer.setPadding(padding, 0, padding, 0);
        inputContainer.addView(pinInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.spp_pairing_title)
                .setMessage(getString(R.string.spp_pairing_message, item.name))
                .setView(inputContainer)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(R.string.action_cancel,
                        (ignored, which) -> restoreScanAfterPairingCancelled())
                .create();
        dialog.setOnShowListener(ignored -> dialog
                .getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String pin = pinInput.getText().toString().trim();
                    if (!pin.matches("[0-9]{1,16}")) {
                        pinInput.setError(getString(R.string.spp_pairing_invalid));
                        return;
                    }
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                    dialog.dismiss();
                    startSppPairing(item.device, pin);
                }));
        showBlurredDialog(dialog);
    }

    private void restoreScanAfterPairingCancelled() {
        setConnectedState(false);
        setConnectionUiState(ConnectionUiState.DISCONNECTED);
        if (scanDialog != null) scanDialog.setTitle(R.string.scan_title_spp);
        if (scanProgress != null) scanProgress.setVisibility(View.INVISIBLE);
        if (scanDeviceList != null) {
            scanDeviceList.setEnabled(true);
            scanDeviceList.clearChoices();
        }
        updateScanDeviceCount();
    }

    @SuppressLint("MissingPermission")
    private void startSppPairing(BluetoothDevice device, String pin) {
        activeSpp = true;
        connectedDeviceName = safeDeviceName(device);
        pendingSppDevice = device;
        pendingSppPin = pin;
        setConnectedState(false);
        setConnectionUiState(ConnectionUiState.CONNECTING);
        if (scanDialog != null) scanDialog.setTitle(R.string.scan_connecting_title);
        if (scanStateText != null) {
            scanStateText.setText(getString(R.string.spp_pairing_started, connectedDeviceName));
        }
        appendReceived(getString(R.string.spp_pairing_request_log, connectedDeviceName) + "\n");
        try {
            if (!device.createBond()) {
                pendingSppDevice = null;
                pendingSppPin = null;
                setConnectionUiState(ConnectionUiState.ERROR);
                showScanErrorFeedback();
                toast(R.string.spp_pairing_failed);
            }
        } catch (SecurityException error) {
            pendingSppDevice = null;
            pendingSppPin = null;
            setConnectionUiState(ConnectionUiState.ERROR);
            showScanErrorFeedback();
            toast(R.string.permission_denied);
        }
    }

    @SuppressLint("MissingPermission")
    private void submitPairingPin(BluetoothDevice device) {
        if (pendingSppPin == null) return;
        try {
            device.setPin(pendingSppPin.getBytes(StandardCharsets.UTF_8));
        } catch (SecurityException ignored) {
            // The system pairing dialog remains available when a vendor blocks app PIN submission.
        }
    }

    @SuppressLint("MissingPermission")
    private void connectSpp(BluetoothDevice device) {
        preferences.edit().putString(PREF_DEVICE, device.getAddress()).apply();
        activeSpp = true;
        connectedDeviceName = safeDeviceName(device);
        cancelDiscovery();
        sppClient.connect(device);
    }

    private boolean isSppMode() {
        return preferences.getBoolean(PREF_EXPERIMENTAL_SPP, false);
    }

    private void reconnectSavedDevice() {
        String address = preferences.getString(PREF_DEVICE, "");
        if (address.isEmpty() || bluetoothAdapter == null || !hasBluetoothPermissions()) {
            return;
        }
        try {
            if (bluetoothAdapter.isEnabled()) {
                BluetoothDevice saved = bluetoothAdapter.getRemoteDevice(address);
                if (isSppMode()) {
                    if (saved.getBondState() == BluetoothDevice.BOND_BONDED) connectSpp(saved);
                } else {
                    connectFfe1(saved);
                }
            }
        } catch (IllegalArgumentException | SecurityException ignored) {
            preferences.edit().remove(PREF_DEVICE).apply();
        }
    }

    private void showSettings() {
        int padding = Math.round(getResources().getDisplayMetrics().density * 20f);
        View settingsRoot = getLayoutInflater().inflate(
                R.layout.dialog_settings, null, false);
        LinearLayout content = settingsRoot.findViewById(R.id.settingsContent);
        content.setPadding(padding, 0, padding, 0);
        settingsRoot.setMinimumHeight(Math.round(
                getResources().getDisplayMetrics().heightPixels * 0.68f));

        MaterialButton confirmButton = settingsRoot.findViewById(
                R.id.settingsConfirmButton);

        MaterialSwitch gamepadRelaySwitch = settingSwitch(
                R.string.settings_gamepad_relay, gamepadRelay.isActive());
        settingsGamepadRelaySwitch = gamepadRelaySwitch;
        content.addView(gamepadRelaySwitch);

        MaterialSwitch transmitControlFrames = settingSwitch(
                R.string.settings_transmit_control_frames,
                this.transmitControlFrames);
        content.addView(transmitControlFrames);

        TextView connectionStatus = new TextView(this);
        settingsConnectionStatus = connectionStatus;
        updateSettingsConnectionStatus();
        connectionStatus.setPadding(0, 0, 0, padding / 2);
        content.addView(connectionStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        MaterialButton chooseDevice = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        chooseDevice.setText(isSppMode()
                ? R.string.settings_device_spp : R.string.settings_device);
        chooseDevice.setOnClickListener(view -> ensureBluetoothAndChooseDevice());
        content.addView(chooseDevice, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        MaterialButton disconnect = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        settingsDisconnectButton = disconnect;
        disconnect.setText(R.string.settings_disconnect);
        disconnect.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            disconnectActiveTransport();
        });
        content.addView(disconnect, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        updateSettingsConnectionStatus();

        MaterialSwitch experimentalSpp = settingSwitch(
                R.string.settings_experimental_spp, isSppMode());
        MaterialSwitch autoReconnect = settingSwitch(
                R.string.settings_auto_reconnect,
                preferences.getBoolean(PREF_AUTO_RECONNECT, false));
        MaterialSwitch reconnectOnDrop = settingSwitch(
                R.string.settings_reconnect_after_drop,
                preferences.getBoolean(PREF_RECONNECT_AFTER_DROP, false));
        MaterialSwitch appendNewline = settingSwitch(
                R.string.settings_append_newline,
                preferences.getBoolean(PREF_APPEND_NEWLINE, true));
        MaterialSwitch keepScreenOn = settingSwitch(
                R.string.settings_keep_screen_on,
                preferences.getBoolean(PREF_KEEP_SCREEN_ON, true));

        int selectedControlRate = getControlRateHz();
        TextView controlRateLabel = new TextView(this);
        controlRateLabel.setText(getString(R.string.settings_control_rate, selectedControlRate));
        LinearLayout.LayoutParams rateLabelLayout = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rateLabelLayout.topMargin = padding / 2;
        content.addView(controlRateLabel, rateLabelLayout);

        Slider controlRate = new Slider(this);
        controlRate.setValueFrom(ControlRate.MIN_HZ);
        controlRate.setValueTo(ControlRate.MAX_HZ);
        controlRate.setStepSize(ControlRate.STEP_HZ);
        controlRate.setTickVisible(false);
        controlRate.setValue(selectedControlRate);
        controlRate.setContentDescription(getString(
                R.string.settings_control_rate, selectedControlRate));
        controlRate.addOnChangeListener((slider, value, fromUser) -> {
            int rateHz = Math.round(value);
            controlRateLabel.setText(getString(R.string.settings_control_rate, rateHz));
            slider.setContentDescription(getString(R.string.settings_control_rate, rateHz));
            if (fromUser) slider.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        });
        content.addView(controlRate, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView controlRateHint = new TextView(this);
        controlRateHint.setText(R.string.settings_control_rate_hint);
        controlRateHint.setPadding(0, 0, 0, padding / 2);
        content.addView(controlRateHint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content.addView(experimentalSpp);
        content.addView(autoReconnect);
        content.addView(reconnectOnDrop);
        content.addView(appendNewline);
        content.addView(keepScreenOn);

        MaterialButton about = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        about.setText(R.string.settings_about);
        LinearLayout.LayoutParams aboutLayout = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aboutLayout.topMargin = padding / 2;
        about.setLayoutParams(aboutLayout);
        about.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            showAbout();
        });
        content.addView(about);

        experimentalSpp.setOnCheckedChangeListener((button, checked) -> {
            button.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            if (checked == isSppMode()) return;
            if (checked) {
                AlertDialog warningDialog = new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.spp_warning_title)
                        .setMessage(R.string.spp_warning_message)
                        .setPositiveButton(R.string.spp_warning_enable, (dialog, which) ->
                                applyExperimentalSppMode(true, chooseDevice))
                        .setNegativeButton(R.string.action_cancel, (dialog, which) ->
                                experimentalSpp.setChecked(false))
                        .setOnCancelListener(dialog -> experimentalSpp.setChecked(false))
                        .create();
                showBlurredDialog(warningDialog);
            } else {
                applyExperimentalSppMode(false, chooseDevice);
            }
        });

        gamepadRelaySwitch.setOnCheckedChangeListener((button, checked) -> {
            if (synchronizingGamepadRelaySwitch) return;
            button.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            if (checked) startGamepadRelay();
            else stopGamepadRelay();
        });

        AlertDialog settingsDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_title)
                .setView(settingsRoot)
                .create();
        settingsDialog.setCanceledOnTouchOutside(false);
        settingsDialog.setOnDismissListener(dialog -> {
            if (settingsConnectionStatus == connectionStatus) settingsConnectionStatus = null;
            if (settingsDisconnectButton == disconnect) settingsDisconnectButton = null;
            if (settingsGamepadRelaySwitch == gamepadRelaySwitch) {
                settingsGamepadRelaySwitch = null;
            }
        });
        showBlurredDialog(settingsDialog);
        confirmButton.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            this.transmitControlFrames = transmitControlFrames.isChecked();
            preferences.edit()
                    .putBoolean(PREF_AUTO_RECONNECT, autoReconnect.isChecked())
                    .putBoolean(PREF_RECONNECT_AFTER_DROP, reconnectOnDrop.isChecked())
                    .putBoolean(PREF_APPEND_NEWLINE, appendNewline.isChecked())
                    .putBoolean(PREF_KEEP_SCREEN_ON, keepScreenOn.isChecked())
                    .putBoolean(PREF_EXPERIMENTAL_SPP, experimentalSpp.isChecked())
                    .putBoolean(PREF_TRANSMIT_CONTROL_FRAMES,
                            this.transmitControlFrames)
                    .putInt(PREF_CONTROL_RATE_HZ, Math.round(controlRate.getValue()))
                    .apply();
            if (!reconnectOnDrop.isChecked()) cancelReconnectAfterDrop(true);
            applyKeepScreenOn(keepScreenOn.isChecked());
            restartControlHeartbeat();
            dismissBlurredDialog(settingsDialog);
        });
    }

    private void showAbout() {
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.about_title)
                .setMessage(getString(R.string.about_message,
                        BuildConfig.VERSION_NAME, BuildConfig.BUILD_DATE))
                .setPositiveButton(android.R.string.ok, null)
                .create();
        showBlurredDialog(dialog);
    }

    private void applyExperimentalSppMode(boolean enabled, MaterialButton chooseDevice) {
        if (connected || connectionUiState == ConnectionUiState.CONNECTING) {
            disconnectActiveTransport();
        }
        preferences.edit()
                .putBoolean(PREF_EXPERIMENTAL_SPP, enabled)
                .remove(PREF_DEVICE)
                .apply();
        pendingSppDevice = null;
        pendingSppPin = null;
        chooseDevice.setText(enabled
                ? R.string.settings_device_spp : R.string.settings_device);
        setConnectedState(false);
        setConnectionUiState(ConnectionUiState.DISCONNECTED);
    }

    private boolean shouldReconnectAfterDrop() {
        return !reconnectSuppressed
                && !manualConnectionInProgress
                && scanDialog == null
                && !connected
                && connectionUiState != ConnectionUiState.CONNECTING
                && currentSessionWasConnected
                && preferences.getBoolean(PREF_RECONNECT_AFTER_DROP, false)
                && !preferences.getString(PREF_DEVICE, "").isEmpty()
                && bluetoothAdapter != null
                && hasBluetoothPermissions();
    }

    private void scheduleReconnectAfterDrop() {
        if (!shouldReconnectAfterDrop()) return;
        protocolHandler.removeCallbacks(reconnectAfterDrop);
        int attempt = ++reconnectAttempt;
        int shift = Math.min(attempt - 1, 4);
        long delay = Math.min(RECONNECT_BASE_DELAY_MS << shift, RECONNECT_MAX_DELAY_MS);
        appendReceived(getString(R.string.reconnect_scheduled_log,
                (delay + 999L) / 1_000L, attempt) + "\n");
        protocolHandler.postDelayed(reconnectAfterDrop, delay);
    }

    private void cancelReconnectAfterDrop(boolean resetAttempt) {
        protocolHandler.removeCallbacks(reconnectAfterDrop);
        if (resetAttempt) reconnectAttempt = 0;
    }

    private void updateSettingsConnectionStatus() {
        if (settingsConnectionStatus != null) {
            String status;
            if (connectionUiState == ConnectionUiState.CONNECTED) {
                status = getString(R.string.transport_status, connectedDeviceName,
                        getString(activeSpp ? R.string.transport_spp : R.string.transport_ffe1));
            } else if (connectionUiState == ConnectionUiState.CONNECTING) {
                status = getString(R.string.status_connecting) + " · " + connectedDeviceName;
            } else if (connectionUiState == ConnectionUiState.ERROR) {
                status = getString(R.string.status_error) + " · " + getString(R.string.local_mode);
            } else {
                status = getString(R.string.status_disconnected) + " · " + getString(R.string.local_mode);
            }
            settingsConnectionStatus.setText(getString(R.string.settings_connection_status, status));
        }
        if (settingsDisconnectButton != null) {
            boolean canDisconnect = connectionUiState == ConnectionUiState.CONNECTED;
            settingsDisconnectButton.setVisibility(canDisconnect ? View.VISIBLE : View.GONE);
            settingsDisconnectButton.setEnabled(canDisconnect);
        }
    }

    private MaterialSwitch settingSwitch(int textResource, boolean checked) {
        MaterialSwitch setting = new MaterialSwitch(this);
        setting.setText(textResource);
        setting.setChecked(checked);
        int verticalPadding = Math.round(getResources().getDisplayMetrics().density * 8f);
        setting.setPadding(0, verticalPadding, 0, verticalPadding);
        setting.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return setting;
    }

    private void showDebugSender() {
        EditText input = new EditText(this);
        input.setHint(R.string.debug_command_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        int padding = Math.round(getResources().getDisplayMetrics().density * 20f);
        input.setPadding(padding, padding / 2, padding, padding / 2);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.debug_command_title)
                .setView(input)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_send, (ignoredDialog, which) -> {
                    String text = input.getText().toString();
                    if (text.isEmpty()) {
                        toast(R.string.debug_empty);
                        return;
                    }
                    if (preferences.getBoolean(PREF_APPEND_NEWLINE, true) && !text.endsWith("\n")) {
                        text += "\n";
                    }
                    try {
                        dispatchWireFrame(protocol.encodeDebug(text),
                                getString(R.string.debug_sent, text.trim()));
                    } catch (IllegalArgumentException error) {
                        toast(R.string.debug_too_long);
                    }
                })
                .create();
        showBlurredDialog(dialog);
    }

    private void showDebugLog() {
        View content = getLayoutInflater().inflate(R.layout.dialog_debug_log, null, false);
        expandedDebugText = content.findViewById(R.id.debugDialogText);
        expandedDebugSummary = content.findViewById(R.id.debugDialogSummary);
        expandedDebugScroll = content.findViewById(R.id.debugDialogScroll);
        MaterialSwitch autoScroll = content.findViewById(R.id.debugAutoScroll);
        MaterialButtonToggleGroup filterGroup = content.findViewById(R.id.debugFilterGroup);
        expandedDebugFilter = DEBUG_FILTER_ALL;
        expandedDebugAutoScroll = true;
        filterGroup.check(R.id.debugFilterAll);
        filterGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.debugFilterTransport) {
                expandedDebugFilter = DEBUG_FILTER_TRANSPORT;
            } else if (checkedId == R.id.debugFilterData) {
                expandedDebugFilter = DEBUG_FILTER_DATA;
            } else {
                expandedDebugFilter = DEBUG_FILTER_ALL;
            }
            refreshDebugViews();
        });
        autoScroll.setOnCheckedChangeListener((button, checked) -> {
            button.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            expandedDebugAutoScroll = checked;
            if (checked) scrollExpandedDebugToBottom();
        });
        refreshDebugViews();

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(content)
                .setNeutralButton(R.string.debug_log_clear, null)
                .setNegativeButton(R.string.debug_log_copy_current, null)
                .setPositiveButton(R.string.debug_log_close, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() != null) {
                android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
                dialog.getWindow().setLayout(
                        Math.round(metrics.widthPixels * 0.88f),
                        Math.round(metrics.heightPixels * 0.90f));
            }
            dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL)
                    .setOnClickListener(button -> clearDebugLog());
            dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)
                    .setOnClickListener(button -> copyDebugLog(buildFilteredDebugLog()));
            scrollExpandedDebugToBottom();
        });
        dialog.setOnDismissListener(ignored -> {
            expandedDebugText = null;
            expandedDebugSummary = null;
            expandedDebugScroll = null;
        });
        showBlurredDialog(dialog);
    }

    private void copyDebugLog(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(
                getString(R.string.debug_log_title), text));
        toast(R.string.debug_log_copied);
    }

    private void clearDebugLog() {
        debugEntries.clear();
        refreshDebugViews();
        toast(R.string.debug_log_cleared);
    }

    private void dispatchFrame(String frame, String label) {
        protocol.applyControl(frame);
        if (isContinuousStickFrame(frame)) {
            pendingCommandLabel = label;
            pendingLoopbackLog = !connected;
            scheduleControlFeedback();
            return;
        }

        pendingCommandLabel = null;
        commandText.setText(label);
        if (!connected) {
            pendingLoopbackLog = false;
            lastLoopbackLogAt = SystemClock.uptimeMillis();
            appendControlLoopback();
        }
    }

    private boolean isContinuousStickFrame(String frame) {
        return (frame.startsWith("JL,") || frame.startsWith("JR,"))
                && !"JL,0,0\n".equals(frame)
                && !"JR,0,0\n".equals(frame);
    }

    private void scheduleControlFeedback() {
        if (controlFeedbackScheduled
                || (pendingCommandLabel == null && (!pendingLoopbackLog || connected))) {
            return;
        }
        long delay = COMMAND_FEEDBACK_INTERVAL_MS;
        if (pendingLoopbackLog && !connected) {
            long elapsed = SystemClock.uptimeMillis() - lastLoopbackLogAt;
            delay = Math.min(delay, Math.max(0L, LOOPBACK_LOG_INTERVAL_MS - elapsed));
        }
        controlFeedbackScheduled = true;
        protocolHandler.postDelayed(flushControlFeedback, delay);
    }

    private void appendControlLoopback() {
        byte[] state = protocol.encodeControlState();
        appendReceived(getString(R.string.local_loopback, SrmProtocol.toHex(state)) + "\n");
    }

    private void dispatchWireFrame(byte[] frame, String label) {
        commandText.setText(label);
        if (connected) {
            sendWireFrame(frame);
        } else {
            appendReceived(getString(R.string.local_loopback, SrmProtocol.toHex(frame)) + "\n");
        }
    }

    private void sendWireFrame(byte[] frame) {
        // Do not allow queued callbacks to transmit after the Activity enters background.
        if (!activityStarted) return;
        if (activeSpp) sppClient.send(frame);
        else ffe1Client.send(frame);
    }

    private void sendControlFrame(boolean sppTransport) {
        if (!activityStarted) return;
        byte[] frame = gamepadRelay.isActive()
                ? protocol.encodeProControl(gamepadRelay.snapshot())
                : protocol.encodeControlState();
        if (sppTransport) sppClient.sendControl(frame);
        else ffe1Client.sendControl(frame);
    }

    private boolean startGamepadRelay() {
        InputDevice device = gamepadRelay.startFirstConnectedController();
        if (device == null) {
            updateGamepadRelayUi();
            toast(R.string.gamepad_relay_not_found);
            return false;
        }
        updateGamepadRelayUi();
        restartControlHeartbeat();
        commandText.setText(getString(R.string.gamepad_relay_active, device.getName()));
        appendReceived(getString(R.string.gamepad_relay_started_log, device.getName()) + "\n");
        if (!connected) toast(R.string.gamepad_relay_waiting_connection);
        else toast(getString(R.string.gamepad_relay_started, device.getName()));
        return true;
    }

    private void stopGamepadRelay() {
        boolean wasActive = gamepadRelay.isActive();
        if (!wasActive) {
            updateGamepadRelayUi();
            return;
        }
        stopControlHeartbeat();
        if (wasActive && connected) {
            sendWireFrame(protocol.encodeProControl(ProControlState.neutral()));
        }
        gamepadRelay.stop();
        updateGamepadRelayUi();
        restartControlHeartbeat();
        toast(R.string.gamepad_relay_stopped);
        appendReceived(getString(R.string.gamepad_relay_stopped_log) + "\n");
    }

    private void onRelayControllerDisconnected(String deviceName) {
        stopControlHeartbeat();
        if (connected) {
            sendWireFrame(protocol.encodeProControl(ProControlState.neutral()));
        }
        updateGamepadRelayUi();
        restartControlHeartbeat();
        toast(getString(R.string.gamepad_relay_disconnected, deviceName));
        appendReceived(getString(R.string.gamepad_relay_disconnected_log, deviceName) + "\n");
    }

    private void updateGamepadRelayUi() {
        boolean relayActive = gamepadRelay.isActive();
        if (settingsGamepadRelaySwitch != null
                && settingsGamepadRelaySwitch.isChecked() != relayActive) {
            synchronizingGamepadRelaySwitch = true;
            settingsGamepadRelaySwitch.setChecked(relayActive);
            synchronizingGamepadRelaySwitch = false;
        }
        gamepad.setControlsEnabled(!relayActive);
        for (MaterialSwitch commandSwitch : commandSwitches) {
            commandSwitch.setEnabled(!relayActive);
        }
        updateConnectionSummary();
    }

    private int getControlRateHz() {
        return ControlRate.clamp(preferences.getInt(
                PREF_CONTROL_RATE_HZ, ControlRate.DEFAULT_HZ));
    }

    private boolean shouldTransmitControlFrames() {
        return transmitControlFrames;
    }

    private void restartControlHeartbeat() {
        stopControlHeartbeat();
        boolean shouldStream = connected && activityStarted && shouldTransmitControlFrames();
        if (!activeSpp) ffe1Client.setControlStreaming(shouldStream);
        if (!shouldStream || controlScheduler.isShutdown()) return;

        boolean sppTransport = activeSpp;
        long periodNanos = ControlRate.periodNanos(getControlRateHz());
        try {
            controlHeartbeat = controlScheduler.scheduleAtFixedRate(() -> {
                try {
                    if (connected && activityStarted && activeSpp == sppTransport
                            && shouldTransmitControlFrames()) {
                        sendControlFrame(sppTransport);
                    }
                } catch (RuntimeException error) {
                    protocolHandler.post(() -> handleControlHeartbeatError(
                            sppTransport, error));
                }
            }, periodNanos, periodNanos, TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException ignored) {
            // A late transport callback can race Activity destruction.
        }
    }

    private void handleControlHeartbeatError(boolean sppTransport, RuntimeException error) {
        if (activeSpp != sppTransport || isFinishing() || isDestroyed()) return;
        stopControlHeartbeat();
        appendReceived(getString(R.string.control_stream_error_log,
                conciseRuntimeMessage(error)) + "\n");
    }

    private static String conciseRuntimeMessage(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private void stopControlHeartbeat() {
        if (controlHeartbeat != null) {
            controlHeartbeat.cancel(false);
            controlHeartbeat = null;
        }
        if (activeSpp && sppClient != null) sppClient.clearPendingControl();
        else if (ffe1Client != null) ffe1Client.setControlStreaming(false);
    }

    private void setConnectedState(boolean isConnected) {
        connected = isConnected;
        if (isConnected) pendingLoopbackLog = false;
        stopControlHeartbeat();
        if (isConnected) {
            boolean sppTransport = activeSpp;
            sendWireFrame(protocol.encodeHello(sppTransport));
            restartControlHeartbeat();
        }
        updateGamepadRelayUi();
        debugButton.setEnabled(true);
    }

    private void setConnectionUiState(ConnectionUiState state) {
        connectionUiState = state;
        if (connectionPulse != null) {
            connectionPulse.cancel();
            connectionPulse = null;
        }
        connectionIndicatorDot.setAlpha(1f);

        int color;
        switch (state) {
            case CONNECTING:
                color = R.color.connection_connecting;
                break;
            case CONNECTED:
                color = R.color.connection_connected;
                break;
            case ERROR:
                color = R.color.connection_error;
                break;
            case DISCONNECTED:
            default:
                color = R.color.connection_disconnected;
                break;
        }

        updateConnectionSummary();
        connectionIndicatorDot.setIndicatorColor(ContextCompat.getColor(this, color));
        connectionIndicator.setAlpha(0.55f);
        connectionIndicator.setScaleX(0.96f);
        connectionIndicator.setScaleY(0.96f);
        connectionIndicator.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(220L)
                .start();

        if (state == ConnectionUiState.CONNECTING) {
            connectionPulse = ObjectAnimator.ofFloat(connectionIndicatorDot,
                    View.ALPHA, 1f, 0.25f);
            connectionPulse.setDuration(650L);
            connectionPulse.setRepeatMode(ValueAnimator.REVERSE);
            connectionPulse.setRepeatCount(ValueAnimator.INFINITE);
            connectionPulse.start();
        }
        updateSettingsConnectionStatus();
        refreshDebugViews();
    }

    private void updateConnectionSummary() {
        if (connectionStatusText == null || connectionIndicator == null) return;
        int statusLabel;
        switch (connectionUiState) {
            case CONNECTING:
                statusLabel = R.string.status_connecting;
                break;
            case CONNECTED:
                statusLabel = R.string.status_connected;
                break;
            case ERROR:
                statusLabel = R.string.status_error;
                break;
            case DISCONNECTED:
            default:
                statusLabel = R.string.status_disconnected;
                break;
        }
        int inputSource = gamepadRelay != null && gamepadRelay.isActive()
                ? R.string.input_source_gamepad : R.string.input_source_screen;
        String summary = getString(R.string.status_with_input_source,
                getString(statusLabel), getString(inputSource));
        connectionStatusText.setText(summary);
        connectionIndicator.setContentDescription(summary + "，"
                + getString(R.string.connection_status_open_settings));
    }

    private void showScanConnectedFeedback() {
        if (scanDialog == null) return;
        AlertDialog completedDialog = scanDialog;
        completedDialog.setTitle(R.string.scan_connected_title);
        if (scanProgress != null) scanProgress.setVisibility(View.INVISIBLE);
        if (scanStateText != null) {
            scanStateText.setText(getString(R.string.scan_connected_device, connectedDeviceName));
        }
        protocolHandler.postDelayed(() -> {
            if (scanDialog == completedDialog && completedDialog.isShowing()) {
                completedDialog.dismiss();
            }
        }, 700L);
    }

    private void showScanErrorFeedback() {
        if (scanDialog == null) return;
        scanDialog.setTitle(R.string.scan_failed_title);
        if (scanProgress != null) scanProgress.setVisibility(View.INVISIBLE);
        if (scanStateText != null) scanStateText.setText(R.string.scan_connection_failed);
        if (scanDeviceList != null) {
            scanDeviceList.setEnabled(true);
            scanDeviceList.clearChoices();
        }
    }

    private boolean hasBluetoothPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void registerClassicBluetoothReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        filter.setPriority(1000);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(classicBluetoothReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(classicBluetoothReceiver, filter);
        }
    }

    @SuppressWarnings("deprecation")
    private BluetoothDevice bluetoothDeviceExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,
                    BluetoothDevice.class);
        }
        return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
    }

    private String safeDeviceName(@NonNull BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null || name.trim().isEmpty() ? device.getAddress() : name;
        } catch (SecurityException error) {
            return device.getAddress();
        }
    }

    private void appendReceived(String text) {
        String timestamp = LocalTime.now().format(DEBUG_TIME_FORMAT);
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        for (String line : normalized.split("\n", -1)) {
            if (line.isEmpty()) continue;
            debugEntries.addLast(new DebugEntry(timestamp, line, classifyDebugEntry(line)));
        }
        while (debugEntries.size() > MAX_DEBUG_ENTRIES) {
            debugEntries.removeFirst();
        }
        scheduleDebugRefresh();
    }

    private void scheduleDebugRefresh() {
        if (!activityStarted || debugRefreshScheduled) return;
        debugRefreshScheduled = true;
        protocolHandler.postDelayed(refreshDebugViewsTask, DEBUG_REFRESH_INTERVAL_MS);
    }

    private DebugCategory classifyDebugEntry(String message) {
        if (message.startsWith("[蓝牙]") || message.startsWith("[BLE")
                || message.startsWith("[SPP]") || message.startsWith("连接失败")) {
            return DebugCategory.TRANSPORT;
        }
        return DebugCategory.DATA;
    }

    private void refreshDebugViews() {
        if (receivedText != null) {
            if (debugEntries.isEmpty()) {
                receivedText.setText(R.string.received_empty);
            } else {
                StringBuilder preview = new StringBuilder();
                Iterator<DebugEntry> iterator = debugEntries.descendingIterator();
                DebugEntry latest = iterator.next();
                if (iterator.hasNext()) preview.append(iterator.next().formatted()).append('\n');
                preview.append(latest.formatted());
                receivedText.setText(preview);
            }
        }
        if (receivedMeta != null) {
            receivedMeta.setText(getString(R.string.debug_log_summary,
                    debugEntries.size(), currentDebugModeLabel()));
        }
        if (expandedDebugText != null) {
            expandedDebugText.setText(buildFilteredDebugLog());
        }
        if (expandedDebugSummary != null) {
            expandedDebugSummary.setText(getString(R.string.debug_log_filtered_summary,
                    filteredDebugEntryCount(), debugEntries.size(), currentDebugModeLabel()));
        }
        if (expandedDebugAutoScroll) scrollExpandedDebugToBottom();
    }

    private String buildFilteredDebugLog() {
        StringBuilder filtered = new StringBuilder();
        for (DebugEntry entry : debugEntries) {
            if (!matchesDebugFilter(entry)) continue;
            filtered.append(entry.formatted()).append('\n');
        }
        return filtered.length() == 0 ? getString(R.string.received_empty) : filtered.toString();
    }

    private int filteredDebugEntryCount() {
        int count = 0;
        for (DebugEntry entry : debugEntries) {
            if (matchesDebugFilter(entry)) count++;
        }
        return count;
    }

    private boolean matchesDebugFilter(DebugEntry entry) {
        if (expandedDebugFilter == DEBUG_FILTER_TRANSPORT) {
            return entry.category == DebugCategory.TRANSPORT;
        }
        if (expandedDebugFilter == DEBUG_FILTER_DATA) {
            return entry.category == DebugCategory.DATA;
        }
        return true;
    }

    private String currentDebugModeLabel() {
        if (!connected) return getString(R.string.debug_mode_local);
        return getString(activeSpp ? R.string.debug_mode_spp : R.string.debug_mode_ble);
    }

    private void scrollExpandedDebugToBottom() {
        if (expandedDebugScroll == null || !expandedDebugAutoScroll) return;
        expandedDebugScroll.post(() -> {
            if (expandedDebugScroll != null) {
                expandedDebugScroll.fullScroll(ScrollView.FOCUS_DOWN);
            }
        });
    }

    private void processIncoming(byte[] chunk) {
        for (SrmProtocol.Frame frame : incomingDecoder.feed(chunk)) {
            String type = SrmProtocol.typeName(frame.type);
            String payload = SrmProtocol.payloadText(frame);
            if (frame.type == SrmProtocol.TYPE_LOG && frame.payload.length > 0) {
                appendReceived("[LOG " + (frame.payload[0] & 0xFF) + "] " + payload + "\n");
            } else {
                appendReceived(getString(R.string.protocol_message, type, frame.version,
                        frame.sequence, payload) + "\n");
            }
        }
    }

    private void applyKeepScreenOn(boolean keepOn) {
        if (keepOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void showBlurredDialog(AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams initialAttributes = window.getAttributes();
            initialAttributes.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            initialAttributes.setBlurBehindRadius(0);
            initialAttributes.dimAmount = 0f;
            window.setAttributes(initialAttributes);
        }

        dialog.show();
        Window shownWindow = dialog.getWindow();
        if (shownWindow == null) return;

        WindowManager windowManager = getSystemService(WindowManager.class);
        boolean systemBlurEnabled = windowManager != null
                && windowManager.isCrossWindowBlurEnabled();
        int targetBlurRadius = systemBlurEnabled
                ? Math.round(getResources().getDisplayMetrics().density
                        * DIALOG_BLUR_RADIUS_DP)
                : 0;

        ValueAnimator blurAnimator = ValueAnimator.ofFloat(0f, 1f);
        blurAnimator.setDuration(DIALOG_BLUR_ENTER_DURATION_MS);
        blurAnimator.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        blurAnimator.addUpdateListener(animation -> {
            if (!dialog.isShowing()) return;
            float progress = (float) animation.getAnimatedValue();
            WindowManager.LayoutParams animatedAttributes = shownWindow.getAttributes();
            animatedAttributes.setBlurBehindRadius(Math.round(targetBlurRadius * progress));
            animatedAttributes.dimAmount = DIALOG_DIM_AMOUNT * progress;
            shownWindow.setAttributes(animatedAttributes);
        });
        dialogBlurAnimators.put(dialog, blurAnimator);
        dialog.getOnBackPressedDispatcher().addCallback(
                dialog, new OnBackPressedCallback(true) {
            private int gestureStartBlurRadius = targetBlurRadius;
            private float gestureStartDimAmount = DIALOG_DIM_AMOUNT;
            private boolean gestureInProgress;

            @Override
            public void handleOnBackStarted(@NonNull BackEventCompat backEvent) {
                cancelDialogBlurAnimator(dialog);
                Window gestureWindow = dialog.getWindow();
                if (gestureWindow == null) return;
                WindowManager.LayoutParams attributes = gestureWindow.getAttributes();
                gestureStartBlurRadius = attributes.getBlurBehindRadius();
                gestureStartDimAmount = attributes.dimAmount;
                gestureInProgress = true;
            }

            @Override
            public void handleOnBackProgressed(@NonNull BackEventCompat backEvent) {
                if (!gestureInProgress) return;
                float remaining = 1f - Math.max(0f, Math.min(1f, backEvent.getProgress()));
                setDialogBlur(dialog,
                        Math.round(gestureStartBlurRadius * remaining),
                        gestureStartDimAmount * remaining);
            }

            @Override
            public void handleOnBackCancelled() {
                if (!gestureInProgress) return;
                gestureInProgress = false;
                restoreBlurredDialog(dialog, targetBlurRadius);
            }

            @Override
            public void handleOnBackPressed() {
                gestureInProgress = false;
                dismissBlurredDialog(dialog);
            }
        });
        shownWindow.getDecorView().addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View view) {
                    }

                    @Override
                    public void onViewDetachedFromWindow(View view) {
                        ValueAnimator animator = dialogBlurAnimators.remove(dialog);
                        if (animator != null && animator.isRunning()) animator.cancel();
                        view.removeOnAttachStateChangeListener(this);
                    }
                });

        // 等待弹窗的零模糊首帧提交后再启动，避免窗口创建时直接跳到最终半径。
        shownWindow.getDecorView().postOnAnimation(blurAnimator::start);
    }

    private void cancelDialogBlurAnimator(AlertDialog dialog) {
        ValueAnimator animator = dialogBlurAnimators.remove(dialog);
        if (animator != null && animator.isRunning()) animator.cancel();
    }

    private void setDialogBlur(AlertDialog dialog, int blurRadius, float dimAmount) {
        if (!dialog.isShowing()) return;
        Window window = dialog.getWindow();
        if (window == null) return;
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.setBlurBehindRadius(Math.max(0, blurRadius));
        attributes.dimAmount = Math.max(0f, Math.min(1f, dimAmount));
        window.setAttributes(attributes);
    }

    private void restoreBlurredDialog(AlertDialog dialog, int targetBlurRadius) {
        if (!dialog.isShowing()) return;
        Window window = dialog.getWindow();
        if (window == null) return;

        cancelDialogBlurAnimator(dialog);
        WindowManager.LayoutParams startAttributes = window.getAttributes();
        int startBlurRadius = startAttributes.getBlurBehindRadius();
        float startDimAmount = startAttributes.dimAmount;
        ValueAnimator restoreAnimator = ValueAnimator.ofFloat(0f, 1f);
        restoreAnimator.setDuration(DIALOG_BLUR_RESTORE_DURATION_MS);
        restoreAnimator.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        restoreAnimator.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            setDialogBlur(dialog,
                    Math.round(startBlurRadius
                            + (targetBlurRadius - startBlurRadius) * progress),
                    startDimAmount + (DIALOG_DIM_AMOUNT - startDimAmount) * progress);
        });
        restoreAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                dialogBlurAnimators.remove(dialog, restoreAnimator);
            }
        });
        dialogBlurAnimators.put(dialog, restoreAnimator);
        restoreAnimator.start();
    }

    private void dismissBlurredDialog(AlertDialog dialog) {
        if (!dialog.isShowing()) return;

        cancelDialogBlurAnimator(dialog);

        Window window = dialog.getWindow();
        if (window == null) {
            dialog.dismiss();
            return;
        }

        WindowManager.LayoutParams startAttributes = window.getAttributes();
        int startBlurRadius = startAttributes.getBlurBehindRadius();
        float startDimAmount = startAttributes.dimAmount;
        float remainingFraction = DIALOG_DIM_AMOUNT <= 0f
                ? 1f : Math.max(0f, Math.min(1f, startDimAmount / DIALOG_DIM_AMOUNT));
        View decorView = window.getDecorView();
        float startDecorAlpha = decorView.getAlpha();
        ValueAnimator exitAnimator = ValueAnimator.ofFloat(0f, 1f);
        exitAnimator.setDuration(Math.max(1L,
                Math.round(DIALOG_BLUR_EXIT_DURATION_MS * remainingFraction)));
        exitAnimator.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        exitAnimator.addUpdateListener(animation -> {
            if (!dialog.isShowing()) return;
            float remaining = 1f - (float) animation.getAnimatedValue();
            decorView.setAlpha(startDecorAlpha * remaining);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.setBlurBehindRadius(Math.round(startBlurRadius * remaining));
            attributes.dimAmount = startDimAmount * remaining;
            window.setAttributes(attributes);
        });
        exitAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean dismissed;

            @Override
            public void onAnimationEnd(Animator animation) {
                if (dismissed) return;
                dismissed = true;
                dialogBlurAnimators.remove(dialog, exitAnimator);
                if (dialog.isShowing()) {
                    window.setWindowAnimations(0);
                    dialog.dismiss();
                }
            }
        });
        dialogBlurAnimators.put(dialog, exitAnimator);
        exitAnimator.start();
    }

    private void toast(int messageResource) {
        Toast.makeText(this, messageResource, Toast.LENGTH_SHORT).show();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityStarted = true;
        if (startupInitialized) startRuntimeWork();
    }

    private void startRuntimeWork() {
        gamepadRelay.register();
        if (!gamepadRelay.validateActiveDevice()) {
            String deviceName = gamepadRelay.getActiveDeviceName();
            gamepadRelay.stop();
            onRelayControllerDisconnected(deviceName);
        }
        refreshDebugViews();
        restartControlHeartbeat();
        if (wallpaperReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(Intent.ACTION_WALLPAPER_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(wallpaperChangedReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(wallpaperChangedReceiver, filter);
        }
        wallpaperReceiverRegistered = true;
    }

    @Override
    protected void onStop() {
        activityStarted = false;
        if (gamepadRelay != null) gamepadRelay.unregister();
        if (startupInitialized) restartControlHeartbeat();
        protocolHandler.removeCallbacks(refreshDebugViewsTask);
        debugRefreshScheduled = false;
        protocolHandler.removeCallbacks(refreshWallpaperColors);
        if (wallpaperReceiverRegistered) {
            unregisterReceiver(wallpaperChangedReceiver);
            wallpaperReceiverRegistered = false;
        }
        cancelDiscovery();
        if (scanDialog != null) scanDialog.dismiss();
        super.onStop();
    }

    private void disconnectActiveTransport() {
        manualConnectionInProgress = false;
        reconnectSuppressed = true;
        currentSessionWasConnected = false;
        cancelReconnectAfterDrop(true);
        if (activeSpp) sppClient.disconnect();
        else ffe1Client.disconnect();
    }

    @Override
    protected void onDestroy() {
        activityStarted = false;
        protocolHandler.removeCallbacksAndMessages(null);
        stopControlHeartbeat();
        controlScheduler.shutdownNow();
        if (ffe1Client != null) ffe1Client.close();
        if (sppClient != null) sppClient.close();
        if (gamepadRelay != null) gamepadRelay.unregister();
        if (classicBluetoothReceiverRegistered) {
            unregisterReceiver(classicBluetoothReceiver);
            classicBluetoothReceiverRegistered = false;
        }
        super.onDestroy();
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (gamepadRelay != null && gamepadRelay.handleMotionEvent(event)) return true;
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (gamepadRelay != null && gamepadRelay.handleKeyEvent(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    private final class Ffe1Listener implements BluetoothFfe1Client.Listener {
        @Override
        @SuppressLint("MissingPermission")
        public void onConnecting(BluetoothDevice device) {
            activeSpp = false;
            setConnectedState(false);
            connectedDeviceName = safeDeviceName(device);
            setConnectionUiState(ConnectionUiState.CONNECTING);
            appendReceived(getString(R.string.ffe1_connecting_log,
                    connectedDeviceName, device.getAddress()) + "\n");
        }

        @Override
        public void onConnected(BluetoothDevice device) {
            manualConnectionInProgress = false;
            reconnectSuppressed = false;
            currentSessionWasConnected = true;
            cancelReconnectAfterDrop(true);
            connectedDeviceName = safeDeviceName(device);
            setConnectedState(true);
            setConnectionUiState(ConnectionUiState.CONNECTED);
            connectionIndicator.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            toast(getString(R.string.connection_success, connectedDeviceName));
            appendReceived(getString(R.string.connection_success_log, connectedDeviceName) + "\n");
            showScanConnectedFeedback();
        }

        @Override
        public void onDisconnected() {
            if (scanDialog == null) {
                manualConnectionInProgress = false;
                currentSessionWasConnected = false;
            }
            setConnectedState(false);
            setConnectionUiState(ConnectionUiState.DISCONNECTED);
            connectedDeviceName = "";
        }

        @Override
        public void onError(String message) {
            if (scanDialog == null) manualConnectionInProgress = false;
            activeSpp = false;
            setConnectedState(false);
            setConnectionUiState(ConnectionUiState.ERROR);
            connectedDeviceName = "";
            appendReceived(getString(R.string.connection_failed_detail, message) + "\n");
            toast(R.string.connection_failed_short);
            showScanErrorFeedback();
            scheduleReconnectAfterDrop();
        }

        @Override
        public void onReceived(byte[] bytes) {
            processIncoming(bytes);
        }
    }

    private final class SppListener implements BluetoothSppClient.Listener {
        @Override
        @SuppressLint("MissingPermission")
        public void onConnecting(BluetoothDevice device) {
            activeSpp = true;
            setConnectedState(false);
            connectedDeviceName = safeDeviceName(device);
            setConnectionUiState(ConnectionUiState.CONNECTING);
            appendReceived(getString(R.string.spp_connecting_log,
                    connectedDeviceName, device.getAddress()) + "\n");
        }

        @Override
        public void onConnected(BluetoothDevice device) {
            manualConnectionInProgress = false;
            reconnectSuppressed = false;
            currentSessionWasConnected = true;
            cancelReconnectAfterDrop(true);
            activeSpp = true;
            connectedDeviceName = safeDeviceName(device);
            setConnectedState(true);
            setConnectionUiState(ConnectionUiState.CONNECTED);
            connectionIndicator.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            toast(getString(R.string.connection_success, connectedDeviceName));
            appendReceived(getString(R.string.spp_connected_log, connectedDeviceName) + "\n");
            showScanConnectedFeedback();
        }

        @Override
        public void onDisconnected() {
            if (scanDialog == null) {
                manualConnectionInProgress = false;
                currentSessionWasConnected = false;
            }
            setConnectedState(false);
            setConnectionUiState(ConnectionUiState.DISCONNECTED);
            connectedDeviceName = "";
        }

        @Override
        public void onError(String message) {
            if (scanDialog == null) manualConnectionInProgress = false;
            setConnectedState(false);
            setConnectionUiState(ConnectionUiState.ERROR);
            connectedDeviceName = "";
            appendReceived(getString(R.string.connection_failed_detail, message) + "\n");
            toast(R.string.spp_connection_failed_short);
            showScanErrorFeedback();
            scheduleReconnectAfterDrop();
        }

        @Override
        public void onReceived(byte[] bytes) {
            processIncoming(bytes);
        }
    }

}
