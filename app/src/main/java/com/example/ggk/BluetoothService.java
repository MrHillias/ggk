package com.example.ggk;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@SuppressLint("MissingPermission")
public class BluetoothService {
    private static final String TAG = "BluetoothService";
    private static final long CONNECTION_TIMEOUT = 15000;
    private static final int BUFFER_SIZE_THRESHOLD = 8192;
    private static final int MAX_DATA_DISPLAY_SIZE = 16384;
    private static final String DATA_TAG = "BLE_DATA";
    private static final int MAX_RECONNECT_ATTEMPTS = 3;
    private static final long RECONNECT_DELAY = 2000;
    private static final long BUFFER_PROCESS_INTERVAL = 50;
    private static final int MAX_RECEPTION_BUFFER_SIZE = 1024 * 1024;
    private static final long UI_UPDATE_INTERVAL = 500;
    private static final int MAX_PENDING_NOTIFICATIONS = 10;
    private static final long NOTIFICATION_TIMEOUT = 5000;

    private final Context context;
    private final BluetoothAdapter bluetoothAdapter;
    private volatile BluetoothGatt bluetoothGatt;
    private BluetoothCallback callback;
    private final Handler mainHandler;
    private final Handler backgroundHandler;
    private final ExecutorService dataProcessingExecutor;

    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicBoolean isConnecting = new AtomicBoolean(false);
    private final AtomicBoolean saveToFile = new AtomicBoolean(false);
    private final AtomicBoolean asciiMode = new AtomicBoolean(true);
    private final AtomicBoolean numericMode = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);

    // Флаг для отключения автоматической обработки (для MT устройств)
    private final AtomicBoolean rawTextMode = new AtomicBoolean(false);

    private volatile FileOutputStream fileOutputStream;
    private volatile File outputFile;

    private final StringBuilder sequenceBuffer = new StringBuilder();
    private final java.util.List<Integer> numericBuffer = new java.util.ArrayList<>();
    private final Queue<byte[]> dataBuffer = new ConcurrentLinkedQueue<>();
    private final AtomicInteger bufferedBytesCount = new AtomicInteger(0);
    private final java.io.ByteArrayOutputStream receptionBuffer = new java.io.ByteArrayOutputStream(MAX_RECEPTION_BUFFER_SIZE);
    private volatile long lastUIUpdateTime = 0;
    private volatile boolean receptionComplete = false;

    private final AtomicLong totalBytesReceived = new AtomicLong(0);
    private final AtomicLong totalPacketsReceived = new AtomicLong(0);
    private final AtomicLong droppedPackets = new AtomicLong(0);
    private volatile long startReceivingTime = 0;
    private volatile long lastDataReceivedTime = 0;
    private volatile int lastSequenceNumber = -1;

    private final AtomicBoolean servicesDiscovered = new AtomicBoolean(false);
    private final AtomicBoolean notificationsEnabled = new AtomicBoolean(false);

    private volatile UUID currentServiceUuid;
    private volatile UUID currentCharacteristicUuid;
    private volatile String currentDeviceAddress;
    private volatile UUID currentWriteCharacteristicUuid;
    private volatile BluetoothGattCharacteristic writeCharacteristic;

    private boolean syncMode = false;
    private long lastSyncTime = 0;

    public interface BluetoothCallback {
        void onConnectionStateChange(boolean connected);
        void onServicesDiscovered(boolean success);
        void onDataReceived(byte[] data, String formattedData);
        void onDataBatch(String formattedBatch, long totalBytes, double kbPerSecond);
        void onError(String message);
        void onReconnectAttempt(int attempt, int maxAttempts);
    }

    public BluetoothService(Context context) {
        this.context = context;
        this.mainHandler = new Handler(Looper.getMainLooper());

        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();

        this.backgroundHandler = new Handler(Looper.getMainLooper());

        dataProcessingExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "BluetoothDataProcessor");
            t.setDaemon(true);
            return t;
        });

        scheduleBufferProcessing();
    }

    public void setSyncMode(boolean enabled, long lastSyncTime) {
        this.syncMode = enabled;
        this.lastSyncTime = lastSyncTime;
        Log.d(TAG, "Sync mode set to: " + enabled + ", last sync time: " + lastSyncTime);
    }

    // Установка режима чистого текста (для MT устройств)
    public void setRawTextMode(boolean enabled) {
        this.rawTextMode.set(enabled);
        Log.d(TAG, "Raw text mode set to: " + enabled);
    }

    private void scheduleBufferProcessing() {
        backgroundHandler.postDelayed(bufferProcessRunnable, BUFFER_PROCESS_INTERVAL);
    }

    private final Runnable bufferProcessRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                if (isConnected.get() && bufferedBytesCount.get() > 0) {
                    processDataBuffer();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in buffer processing", e);
            } finally {
                if (isConnected.get() || isConnecting.get()) {
                    backgroundHandler.postDelayed(this, BUFFER_PROCESS_INTERVAL);
                }
            }
        }
    };

    private void processDataBuffer() {
        dataProcessingExecutor.execute(() -> {
            try {
                List<byte[]> dataChunks = new ArrayList<>();
                int totalSize = 0;

                byte[] chunk;
                while ((chunk = dataBuffer.poll()) != null) {
                    dataChunks.add(chunk);
                    totalSize += chunk.length;
                }
                bufferedBytesCount.set(0);

                if (totalSize == 0) return;

                ByteBuffer combinedBuffer = ByteBuffer.allocate(totalSize);
                for (byte[] dataChunk : dataChunks) {
                    combinedBuffer.put(dataChunk);

                    if (saveToFile.get() && fileOutputStream != null) {
                        try {
                            synchronized (this) {
                                if (fileOutputStream != null) {
                                    fileOutputStream.write(dataChunk);
                                    fileOutputStream.flush();
                                }
                            }
                        } catch (IOException e) {
                            Log.e(TAG, "Error writing to file", e);
                            notifyError("Error writing to file: " + e.getMessage());
                        }
                    }
                }

                byte[] combinedData = combinedBuffer.array();
                String formattedBatch = formatDataBatch(combinedData);

                long currentTime = System.currentTimeMillis();
                double elapsedTimeSeconds = (currentTime - startReceivingTime) / 1000.0;
                long totalBytes = totalBytesReceived.get();
                double kbPerSecond = elapsedTimeSeconds > 0 ? (totalBytes / 1024.0) / elapsedTimeSeconds : 0;

                if (callback != null) {
                    mainHandler.post(() -> {
                        try {
                            callback.onDataBatch(formattedBatch, totalBytes, kbPerSecond);
                        } catch (Exception e) {
                            Log.e(TAG, "Error in callback", e);
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing data buffer", e);
                notifyError("Error processing data: " + e.getMessage());
            }
        });
    }

    private String formatDataBatch(byte[] data) {
        if (asciiMode.get()) {
            return formatDataBatchAsAscii(data);
        } else {
            return formatDataBatchAsHex(data);
        }
    }

    private String formatDataBatchAsAscii(byte[] data) {
        StringBuilder result = new StringBuilder();

        // Если включен режим чистого текста (для MT устройств), просто возвращаем текст как есть
        if (rawTextMode.get()) {
            for (byte b : data) {
                int value = b & 0xFF;
                if (value >= 32 && value <= 126) {
                    result.append((char) value);
                } else if (value == 10) {
                    result.append("\n");
                } else if (value == 13) {
                    result.append("\r");
                } else if (value == 9) {
                    result.append("\t");
                }
            }
            return result.toString();
        }

        Log.d(TAG, "formatDataBatchAsAscii called, initial numericMode: " + numericMode.get() + ", data length: " + data.length);

        for (int i = 0; i < data.length; i++) {
            byte currentByte = data[i];
            int value = currentByte & 0xFF;

            if (!numericMode.get()) {
                sequenceBuffer.append((char) value);

                String bufferContent = sequenceBuffer.toString();
                if (bufferContent.contains("Start")) {
                    int startIndex = bufferContent.lastIndexOf("Start");

                    if (startIndex + 5 < bufferContent.length()) {
                        String afterStart = bufferContent.substring(startIndex + 5);
                        Log.d(TAG, "Found 'Start', checking what follows: [" +
                                afterStart.replaceAll("\r", "\\\\r").replaceAll("\n", "\\\\n") + "]");

                        StringBuilder codes = new StringBuilder();
                        for (int k = 0; k < Math.min(afterStart.length(), 10); k++) {
                            codes.append((int)afterStart.charAt(k)).append(" ");
                        }
                        Log.d(TAG, "ASCII codes after 'Start': " + codes.toString());
                    }

                    if (startIndex + 5 < bufferContent.length()) {
                        String afterStart = bufferContent.substring(startIndex + 5);
                        boolean foundNewline = false;

                        for (int j = 0; j < afterStart.length(); j++) {
                            char c = afterStart.charAt(j);
                            int charCode = (int) c;

                            if (charCode == 10) {
                                foundNewline = true;
                                Log.d(TAG, "Found LF (10) at position " + j + " after 'Start'");
                                break;
                            } else if (charCode == 13 && j + 1 < afterStart.length()) {
                                char nextC = afterStart.charAt(j + 1);
                                if ((int) nextC == 10) {
                                    foundNewline = true;
                                    Log.d(TAG, "Found CR+LF (13+10) at position " + j + " after 'Start'");
                                    break;
                                }
                            }
                        }

                        if (!foundNewline && afterStart.startsWith(" ")) {
                            Log.d(TAG, "Alternative pattern: 'Start' followed by space and data");
                            foundNewline = true;
                        }

                        if (foundNewline) {
                            numericMode.set(true);
                            Log.d(TAG, "Switching to numeric mode at byte " + i + " after 'Start' + newline (real \\r\\n)");

                            result.append("\n");

                            if (callback != null) {
                                mainHandler.post(() -> callback.onError("AUTO: Switched to numeric mode after 'Start' + newline"));
                            }

                            sequenceBuffer.setLength(0);

                            if (value == 13 || value == 10) {
                                continue;
                            }

                            result.append(value).append(" ");
                            continue;
                        }
                    }
                }

                if (sequenceBuffer.length() > 100) {
                    String keepEnd = sequenceBuffer.substring(sequenceBuffer.length() - 50);
                    sequenceBuffer.setLength(0);
                    sequenceBuffer.append(keepEnd);
                }

                if (value >= 32 && value <= 126) {
                    result.append((char) value);
                } else if (value == 10) {
                    result.append("\n");
                } else if (value == 13) {
                    if (i + 1 < data.length && (data[i + 1] & 0xFF) != 10) {
                        result.append("\r");
                    }
                } else if (value == 9) {
                    result.append("\t");
                } else {
                    result.append(".");
                }
            } else {
                numericBuffer.add(value);

                if (numericBuffer.size() > 10) {
                    numericBuffer.remove(0);
                }

                if (numericBuffer.size() >= 5) {
                    int size = numericBuffer.size();
                    if (numericBuffer.get(size - 5) == 69 &&
                            numericBuffer.get(size - 4) == 110 &&
                            numericBuffer.get(size - 3) == 100 &&
                            numericBuffer.get(size - 2) == 13 &&
                            numericBuffer.get(size - 1) == 10) {

                        Log.d(TAG, "Found 'End\\r\\n' sequence in numeric mode");

                        String currentResult = result.toString();
                        String[] parts = currentResult.trim().split("\\s+");

                        if (parts.length >= 5) {
                            StringBuilder newResult = new StringBuilder();
                            for (int j = 0; j < parts.length - 5; j++) {
                                newResult.append(parts[j]).append(" ");
                            }
                            newResult.append("End\n");

                            result.setLength(0);
                            result.append(newResult.toString());

                            numericBuffer.clear();

                            if (callback != null) {
                                mainHandler.post(() -> callback.onError("Found 'End' sequence - data transmission complete"));
                            }

                            continue;
                        }
                    }
                }

                result.append(value).append(" ");
            }
        }

        return result.toString();
    }

    private String formatDataBatchAsHex(byte[] data) {
        StringBuilder result = new StringBuilder();

        Log.d(TAG, "formatDataBatchAsHex called, initial numericMode: " + numericMode.get() + ", data length: " + data.length);

        for (int i = 0; i < data.length; i++) {
            byte currentByte = data[i];
            int value = currentByte & 0xFF;

            if (!numericMode.get()) {
                sequenceBuffer.append((char) value);

                String bufferContent = sequenceBuffer.toString();
                if (bufferContent.contains("Start")) {
                    int startIndex = bufferContent.lastIndexOf("Start");
                    String afterStart = bufferContent.substring(startIndex + 5);

                    if (afterStart.length() >= 2) {
                        boolean foundCRLF = false;
                        for (int j = 0; j < afterStart.length() - 1; j++) {
                            char c1 = afterStart.charAt(j);
                            char c2 = afterStart.charAt(j + 1);
                            if (((int)c1 == 13 && (int)c2 == 10) || (int)c2 == 10) {
                                foundCRLF = true;
                                break;
                            }
                        }

                        if (foundCRLF) {
                            numericMode.set(true);
                            Log.d(TAG, "HEX mode - Switching to numeric mode at byte " + i + " after 'Start' + CR LF");

                            if (callback != null) {
                                mainHandler.post(() -> callback.onError("AUTO: Switched to numeric mode after 'Start'"));
                            }

                            sequenceBuffer.setLength(0);
                            result.append(value).append(" ");
                            continue;
                        }
                    }
                }

                if (sequenceBuffer.length() > 100) {
                    String keepEnd = sequenceBuffer.substring(sequenceBuffer.length() - 50);
                    sequenceBuffer.setLength(0);
                    sequenceBuffer.append(keepEnd);
                }

                if (value >= 32 && value <= 126) {
                    result.append("'").append((char) value).append("' ");
                } else if (value == 10) {
                    result.append("\\n ");
                } else if (value == 13) {
                    result.append("\\r ");
                } else if (value == 9) {
                    result.append("\\t ");
                } else if (value == 0) {
                    result.append("NULL ");
                } else {
                    result.append(value).append(" ");
                }
            } else {
                result.append(value).append(" ");
            }
        }

        return result.toString();
    }

    public void setSaveToFile(boolean save) {
        saveToFile.set(save);

        if (save && fileOutputStream == null) {
            createOutputFile();
        } else if (!save && fileOutputStream != null) {
            closeOutputFile();
        }
    }

    private synchronized void createOutputFile() {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
            String timestamp = sdf.format(new Date());

            File directory = new File(context.getExternalFilesDir(null), "BLEData");
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IOException("Cannot create directory: " + directory.getAbsolutePath());
            }

            outputFile = new File(directory, "ble_data_" + timestamp + ".bin");
            fileOutputStream = new FileOutputStream(outputFile);

            Log.d(TAG, "Created output file: " + outputFile.getAbsolutePath());
            notifyError("Started saving to: " + outputFile.getName());
        } catch (IOException e) {
            Log.e(TAG, "Error creating output file", e);
            outputFile = null;
            fileOutputStream = null;
            notifyError("Error creating file: " + e.getMessage());
        }
    }

    private synchronized void closeOutputFile() {
        if (fileOutputStream != null) {
            try {
                fileOutputStream.flush();
                fileOutputStream.close();

                if (callback != null && outputFile != null) {
                    final String fileName = outputFile.getAbsolutePath();
                    mainHandler.post(() -> callback.onError("Data saved to: " + fileName));
                }
            } catch (IOException e) {
                Log.e(TAG, "Error closing output file", e);
                notifyError("Error closing file: " + e.getMessage());
            } finally {
                fileOutputStream = null;
                outputFile = null;
            }
        }
    }

    public void setCallback(BluetoothCallback callback) {
        this.callback = callback;
    }

    public boolean isConnected() {
        return isConnected.get();
    }

    public boolean isConnecting() {
        return isConnecting.get();
    }

    public void connect(String deviceAddress, UUID serviceUuid, UUID characteristicUuid) {
        if (bluetoothAdapter == null || deviceAddress == null) {
            notifyError("Bluetooth adapter not available or device address is null");
            return;
        }

        currentDeviceAddress = deviceAddress;
        currentServiceUuid = serviceUuid;
        currentCharacteristicUuid = characteristicUuid;

        if (currentWriteCharacteristicUuid == null) {
            writeCharacteristic = null;
        }

        reconnectAttempts.set(0);
        connectInternal();
    }

    private void connectInternal() {
        if (isConnecting.get()) {
            Log.w(TAG, "Connection already in progress");
            return;
        }

        closeConnection();

        isConnected.set(false);
        isConnecting.set(true);
        servicesDiscovered.set(false);
        notificationsEnabled.set(false);

        resetNumericMode();
        Log.d(TAG, "Connection started - reset to text mode, numeric mode: " + numericMode.get());

        totalBytesReceived.set(0);
        totalPacketsReceived.set(0);
        droppedPackets.set(0);
        startReceivingTime = System.currentTimeMillis();
        lastDataReceivedTime = 0;

        clearReceptionBuffer();
        dataBuffer.clear();
        bufferedBytesCount.set(0);

        final BluetoothDevice device = bluetoothAdapter.getRemoteDevice(currentDeviceAddress);
        if (device == null) {
            notifyError("Device not found. Unable to connect.");
            isConnecting.set(false);
            return;
        }

        mainHandler.postDelayed(() -> {
            if (isConnecting.get() && !isConnected.get()) {
                Log.e(TAG, "Connection timeout");
                handleConnectionFailure("Connection timeout");
            }
        }, CONNECTION_TIMEOUT);

        Log.d(TAG, "Attempting to connect to device: " + currentDeviceAddress);
        try {
            bluetoothGatt = device.connectGatt(context, false, gattCallback);
            if (bluetoothGatt == null) {
                throw new RuntimeException("Failed to create GATT connection");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error creating GATT connection", e);
            handleConnectionFailure("Error creating GATT connection: " + e.getMessage());
        }
    }

    private void handleConnectionFailure(String error) {
        isConnecting.set(false);
        isConnected.set(false);

        int attempts = reconnectAttempts.incrementAndGet();
        Log.w(TAG, "Connection failed (attempt " + attempts + "/" + MAX_RECONNECT_ATTEMPTS + "): " + error);

        if (callback != null) {
            mainHandler.post(() -> callback.onReconnectAttempt(attempts, MAX_RECONNECT_ATTEMPTS));
        }

        if (attempts < MAX_RECONNECT_ATTEMPTS) {
            mainHandler.postDelayed(() -> {
                Log.i(TAG, "Attempting to reconnect...");
                connectInternal();
            }, RECONNECT_DELAY);
        } else {
            notifyError("Failed to connect after " + MAX_RECONNECT_ATTEMPTS + " attempts: " + error);
            notifyConnectionStateChange(false);
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "GATT connection failed with status: " + status);
                handleConnectionFailure("GATT connection failed with status: " + status);
                return;
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected.set(true);
                isConnecting.set(false);
                reconnectAttempts.set(0);

                Log.i(TAG, "Connected to GATT server.");
                notifyConnectionStateChange(true);
                scheduleBufferProcessing();

                boolean mtuResult = gatt.requestMtu(517);
                if (!mtuResult) {
                    Log.w(TAG, "Failed to request MTU, proceeding with service discovery");
                    gatt.discoverServices();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected from GATT server.");
                handleDisconnection();
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Log.d(TAG, "MTU changed to: " + mtu + ", status: " + status);

            if (status == BluetoothGatt.GATT_SUCCESS) {
                notifyError("MTU increased to " + mtu + " bytes");
            } else {
                Log.w(TAG, "Failed to increase MTU, using default (23 bytes)");
            }

            boolean result = gatt.discoverServices();
            if (!result) {
                Log.e(TAG, "Failed to start service discovery");
                handleConnectionFailure("Failed to start service discovery");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered successfully");

                BluetoothGattService service = gatt.getService(currentServiceUuid);
                if (service != null) {
                    // ВАЖНО: Всегда ищем write характеристику для MT устройств
                    if (currentWriteCharacteristicUuid == null && currentCharacteristicUuid != null) {
                        // Если подключаемся для чтения (connect), автоматически ищем запись
                        UUID autoWriteUuid = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb");
                        BluetoothGattCharacteristic writeChar = service.getCharacteristic(autoWriteUuid);
                        if (writeChar != null) {
                            writeCharacteristic = writeChar;
                            Log.d(TAG, "✓ Auto-found write characteristic for MT device");
                        }
                    }
                    if (currentWriteCharacteristicUuid != null) {
                        writeCharacteristic = null;

                        // ВАЖНО: Сначала ищем ТОЧНО указанную характеристику
                        Log.d(TAG, "Looking for SPECIFIC write characteristic: " + currentWriteCharacteristicUuid);

                        for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                            UUID uuid = characteristic.getUuid();
                            int properties = characteristic.getProperties();

                            Log.d(TAG, "Found characteristic: " + uuid);
                            Log.d(TAG, "  Properties: Write=" + ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) +
                                    ", WriteNoResp=" + ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) +
                                    ", Read=" + ((properties & BluetoothGattCharacteristic.PROPERTY_READ) != 0) +
                                    ", Notify=" + ((properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0));

                            // Проверяем, является ли это нужной характеристикой
                            if (uuid.equals(currentWriteCharacteristicUuid)) {
                                if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                                        (properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                                    writeCharacteristic = characteristic;
                                    Log.d(TAG, "✓ Found SPECIFIED write characteristic: " + uuid);
                                    break;
                                } else {
                                    Log.w(TAG, "✗ Specified characteristic " + uuid + " does NOT support write!");
                                }
                            }
                        }

                        // Если не нашли указанную, ищем любую подходящую
                        if (writeCharacteristic == null) {
                            Log.w(TAG, "Specified write characteristic not found, searching for ANY writable characteristic...");
                            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                                UUID uuid = characteristic.getUuid();
                                int properties = characteristic.getProperties();

                                if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                                        (properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                                    writeCharacteristic = characteristic;
                                    Log.d(TAG, "✓ Found ALTERNATIVE write characteristic: " + uuid);
                                    break;
                                }
                            }
                        }

                        if (writeCharacteristic != null) {
                            servicesDiscovered.set(true);
                            notifyServicesDiscovered(true);
                        } else {
                            Log.e(TAG, "✗ NO write characteristic found AT ALL!");
                            handleConnectionFailure("No write characteristic found");
                        }
                    } else {
                        BluetoothGattCharacteristic characteristic = service.getCharacteristic(currentCharacteristicUuid);
                        if (characteristic != null) {
                            boolean success = enableNotifications(gatt, characteristic);
                            servicesDiscovered.set(true);
                            notifyServicesDiscovered(success);
                        } else {
                            Log.e(TAG, "Read characteristic not found");
                            handleConnectionFailure("Read characteristic not found");
                        }
                    }
                } else {
                    Log.e(TAG, "Service not found: " + currentServiceUuid);
                    handleConnectionFailure("Service not found");
                }
            } else {
                Log.e(TAG, "Service discovery failed: " + status);
                handleConnectionFailure("Service discovery failed");
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic.getValue();
            if (data != null && data.length > 0) {
                handleReceivedData(data);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Descriptor write successful");
                if (descriptor.getCharacteristic().getUuid().equals(currentCharacteristicUuid)) {
                    notificationsEnabled.set(true);
                    notifyError("Notifications enabled successfully");
                }
            } else {
                Log.e(TAG, "Descriptor write failed: " + status);
                handleConnectionFailure("Failed to enable notifications: status " + status);
            }
        }
    };

    // Новый метод для MT устройств - и запись И чтение
    public void connectForMTDevice(String deviceAddress, UUID serviceUuid,
                                   UUID readCharacteristicUuid, UUID writeCharacteristicUuid) {
        if (bluetoothAdapter == null || deviceAddress == null) {
            notifyError("Bluetooth adapter not available or device address is null");
            return;
        }

        currentDeviceAddress = deviceAddress;
        currentServiceUuid = serviceUuid;
        currentCharacteristicUuid = readCharacteristicUuid;
        currentWriteCharacteristicUuid = writeCharacteristicUuid;

        reconnectAttempts.set(0);
        connectInternal();
    }

    public void connectForCommands(String deviceAddress, UUID serviceUuid, UUID writeCharacteristicUuid) {
        this.currentWriteCharacteristicUuid = writeCharacteristicUuid;
        connect(deviceAddress, serviceUuid, null);
    }

    public boolean sendCommand(String command) {
        if (bluetoothGatt == null || writeCharacteristic == null || !isConnected.get()) {
            Log.e(TAG, "Cannot send command: not connected or characteristic not available");
            Log.e(TAG, "  bluetoothGatt: " + (bluetoothGatt != null));
            Log.e(TAG, "  writeCharacteristic: " + (writeCharacteristic != null));
            Log.e(TAG, "  isConnected: " + isConnected.get());
            return false;
        }

        try {
            byte[] commandBytes = command.getBytes("UTF-8");

            Log.d(TAG, "Sending command: [" + command.replace("\r", "\\r").replace("\n", "\\n") + "]");
            Log.d(TAG, "Command bytes: " + java.util.Arrays.toString(commandBytes));
            Log.d(TAG, "Using characteristic: " + writeCharacteristic.getUuid());

            writeCharacteristic.setValue(commandBytes);
            boolean result = bluetoothGatt.writeCharacteristic(writeCharacteristic);

            if (result) {
                Log.d(TAG, "✓ Command sent successfully");
            } else {
                Log.e(TAG, "✗ Failed to send command");
            }

            return result;
        } catch (Exception e) {
            Log.e(TAG, "✗ Error sending command", e);
            return false;
        }
    }

    private void handleDisconnection() {
        boolean wasConnected = isConnected.getAndSet(false);
        isConnecting.set(false);
        servicesDiscovered.set(false);
        notificationsEnabled.set(false);

        currentWriteCharacteristicUuid = null;
        writeCharacteristic = null;

        if (bufferedBytesCount.get() > 0) {
            processDataBuffer();
        }

        if (saveToFile.get()) {
            closeOutputFile();
        }

        if (wasConnected) {
            notifyConnectionStateChange(false);
        }

        closeConnection();
    }

    private void handleReceivedData(byte[] data) {
        try {
            synchronized (receptionBuffer) {
                receptionBuffer.write(data);
            }

            lastDataReceivedTime = System.currentTimeMillis();
            if (startReceivingTime == 0) {
                startReceivingTime = lastDataReceivedTime;
            }

            long totalBytes = totalBytesReceived.addAndGet(data.length);
            long totalPackets = totalPacketsReceived.incrementAndGet();

            if (totalPackets % 100 == 0) {
                Log.d(TAG, "Received " + totalPackets + " packets, " + totalBytes + " bytes");
            }

            checkForEndSequence(data);

            long currentTime = System.currentTimeMillis();
            if (currentTime - lastUIUpdateTime > UI_UPDATE_INTERVAL || receptionComplete) {
                lastUIUpdateTime = currentTime;

                dataProcessingExecutor.execute(() -> {
                    processDataForUI();
                });
            }

        } catch (Exception e) {
            Log.e(TAG, "Error handling received data", e);
        }
    }

    private void checkForEndSequence(byte[] data) {
        if (data.length >= 5) {
            int len = data.length;
            if (data[len-5] == 69 && data[len-4] == 110 && data[len-3] == 100 &&
                    data[len-2] == 13 && data[len-1] == 10) {
                receptionComplete = true;
                Log.d(TAG, "Reception complete - End sequence detected");
            }
        }
    }

    private void processDataForUI() {
        try {
            byte[] allData;
            synchronized (receptionBuffer) {
                allData = receptionBuffer.toByteArray();
            }

            if (allData.length == 0) return;

            int displayStart = Math.max(0, allData.length - MAX_DATA_DISPLAY_SIZE);
            byte[] displayData = new byte[allData.length - displayStart];
            System.arraycopy(allData, displayStart, displayData, 0, displayData.length);

            String formattedData = formatDataBatch(displayData);

            if (callback != null) {
                long totalBytes = totalBytesReceived.get();
                long duration = System.currentTimeMillis() - startReceivingTime;
                double kbPerSecond = duration > 0 ? (totalBytes / 1024.0) / (duration / 1000.0) : 0;

                mainHandler.post(() -> {
                    try {
                        callback.onDataBatch(formattedData, totalBytes, kbPerSecond);
                    } catch (Exception e) {
                        Log.e(TAG, "Error in UI callback", e);
                    }
                });
            }

        } catch (Exception e) {
            Log.e(TAG, "Error processing data for UI", e);
        }
    }

    private boolean enableNotifications(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        try {
            boolean success = gatt.setCharacteristicNotification(characteristic, true);
            if (!success) {
                Log.e(TAG, "Failed to set characteristic notification");
                return false;
            }

            Log.d(TAG, "setCharacteristicNotification returned true");

            UUID descriptorUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(descriptorUuid);

            if (descriptor != null) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                boolean writeSuccess = gatt.writeDescriptor(descriptor);
                Log.d(TAG, "Descriptor write initiated: " + writeSuccess);
                return writeSuccess;
            } else {
                Log.e(TAG, "Client configuration descriptor not found");
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error enabling notifications", e);
            return false;
        }
    }

    public void disconnect() {
        Log.d(TAG, "Disconnect requested");
        isConnecting.set(false);
        reconnectAttempts.set(MAX_RECONNECT_ATTEMPTS);

        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
        } else {
            handleDisconnection();
        }
    }

    private void closeConnection() {
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing GATT", e);
            } finally {
                bluetoothGatt = null;
            }
        }
    }

    public void close() {
        Log.d(TAG, "Closing BluetoothService");

        disconnect();

        if (bufferedBytesCount.get() > 0) {
            processDataBuffer();
        }

        if (saveToFile.get()) {
            closeOutputFile();
        }

        mainHandler.removeCallbacksAndMessages(null);
        backgroundHandler.removeCallbacksAndMessages(null);

        try {
            dataProcessingExecutor.shutdown();
            if (!dataProcessingExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                dataProcessingExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            dataProcessingExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public void setAsciiMode(boolean asciiMode) {
        this.asciiMode.set(asciiMode);

        if (bufferedBytesCount.get() > 0) {
            processDataBuffer();
        }
    }

    public void resetNumericMode() {
        boolean wasNumeric = numericMode.getAndSet(false);
        sequenceBuffer.setLength(0);
        numericBuffer.clear();
        Log.d(TAG, "Numeric mode reset");

        if (wasNumeric && callback != null) {
            mainHandler.post(() -> callback.onError("Reset to text mode - waiting for 'Start\\r\\n'"));
        }
    }

    public void forceNumericMode(boolean enable) {
        boolean wasNumeric = numericMode.getAndSet(enable);
        if (enable) {
            sequenceBuffer.setLength(0);
            numericBuffer.clear();
        }
        Log.d(TAG, "Numeric mode " + (enable ? "enabled" : "disabled") + " manually");

        if (wasNumeric != enable && callback != null) {
            String message = enable ? "Forced numeric mode enabled" : "Numeric mode disabled - waiting for 'Start\\r\\n'";
            mainHandler.post(() -> callback.onError(message));
        }
    }

    public boolean isNumericMode() {
        return numericMode.get();
    }

    private void notifyError(String message) {
        if (callback != null) {
            mainHandler.post(() -> {
                try {
                    callback.onError(message);
                } catch (Exception e) {
                    Log.e(TAG, "Error in error callback", e);
                }
            });
        }
    }

    private void notifyConnectionStateChange(boolean connected) {
        if (callback != null) {
            mainHandler.post(() -> {
                try {
                    callback.onConnectionStateChange(connected);
                } catch (Exception e) {
                    Log.e(TAG, "Error in connection state callback", e);
                }
            });
        }
    }

    private void notifyServicesDiscovered(boolean success) {
        Log.d(TAG, "notifyServicesDiscovered called with success=" + success);
        if (callback != null) {
            Log.d(TAG, "Callback exists, posting to main handler");
            mainHandler.post(() -> {
                try {
                    callback.onServicesDiscovered(success);
                    Log.d(TAG, "onServicesDiscovered callback executed");
                } catch (Exception e) {
                    Log.e(TAG, "Error in services discovered callback", e);
                }
            });
        } else {
            Log.w(TAG, "Callback is NULL!");
        }
    }

    public void forceReconnect() {
        Log.i(TAG, "Force reconnect requested");
        reconnectAttempts.set(0);
        disconnect();

        mainHandler.postDelayed(() -> {
            if (currentDeviceAddress != null && currentServiceUuid != null && currentCharacteristicUuid != null) {
                connectInternal();
            }
        }, 1000);
    }

    public byte[] getCompleteReceivedData() {
        synchronized (receptionBuffer) {
            return receptionBuffer.toByteArray();
        }
    }

    public void clearReceptionBuffer() {
        synchronized (receptionBuffer) {
            receptionBuffer.reset();
        }
        receptionComplete = false;
        lastUIUpdateTime = 0;
    }

    public boolean isReceptionComplete() {
        return receptionComplete;
    }

    public long getTotalPacketsReceived() {
        return totalPacketsReceived.get();
    }

    public long getDroppedPackets() {
        return droppedPackets.get();
    }

    public double getPacketLossRate() {
        long total = totalPacketsReceived.get();
        long dropped = droppedPackets.get();
        return total > 0 ? (dropped * 100.0 / total) : 0.0;
    }

    public long getConnectionDuration() {
        return startReceivingTime > 0 ? System.currentTimeMillis() - startReceivingTime : 0;
    }

    public boolean isNotificationsEnabled() {
        return notificationsEnabled.get();
    }

    public boolean isServicesDiscovered() {
        return servicesDiscovered.get();
    }
}