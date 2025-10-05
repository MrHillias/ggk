package com.example.ggk;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class MTDeviceHandler {
    private static final String TAG = "MTDeviceHandler";

    // UUIDs
    private static final UUID SERVICE_UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_UUID_FFF1 = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_UUID_FFF2 = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb");

    // Константы
    private static final long COMMAND_TIMEOUT = 5000;
    private static final long COMMAND_DELAY = 500;
    private static final long INITIAL_DELAY = 500;
    private static final String COMMAND_TERMINATOR = "\r";

    // Список команд для опроса
    private static final String[] BASIC_COMMANDS = {
            "Idn?",
            "DataSize?",
            "WorkTime?",
            "PmaxAllTime?",
            "Pminmax24?",
            "UnitsAll?",
            "RangesAll?",
            "Units?",
            "Ranges?"
    };

    // Поля класса
    private final Context context;
    private final Handler mainHandler;
    private MTDeviceCallback callback;

    private BluetoothService writeService;
    private BluetoothService readService;

    private String deviceAddress;
    private Map<String, String> deviceInfo;
    private StringBuilder responseBuffer;

    private int commandIndex;
    private String currentCommand;
    private boolean isProcessing;
    private volatile boolean writeReady;
    private volatile boolean readReady;

    private Runnable timeoutRunnable;

    // Интерфейс callback
    public interface MTDeviceCallback {
        void onConnectionStateChanged(boolean connected);
        void onDeviceInfoReady(Map<String, String> deviceInfo);
        void onCommandResponse(String command, String response);
        void onError(String error);
        void onProgress(int current, int total);
    }

    public MTDeviceHandler(Context context, MTDeviceCallback callback) {
        this.context = context;
        this.callback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.deviceInfo = new HashMap<>();
        this.responseBuffer = new StringBuilder();

        initializeBluetoothServices();
    }

    private void initializeBluetoothServices() {
        // Сервис для ЗАПИСИ команд
        writeService = new BluetoothService(context);
        writeService.setCallback(new BluetoothService.BluetoothCallback() {
            @Override
            public void onConnectionStateChange(boolean connected) {
                Log.d(TAG, "Write service connection: " + connected);
                if (!connected) {
                    writeReady = false;
                    handleDisconnection();
                }
            }

            @Override
            public void onServicesDiscovered(boolean success) {
                Log.d(TAG, "Write services discovered: " + success);
                if (success) {
                    Log.d(TAG, "Write service ready");
                    writeReady = true;
                    checkBothReady();
                } else {
                    notifyError("Не удалось настроить запись");
                }
            }

            @Override
            public void onDataReceived(byte[] data, String formattedData) {}

            @Override
            public void onDataBatch(String formattedBatch, long totalBytes, double kbPerSecond) {}

            @Override
            public void onError(String message) {
                if (!message.contains("MTU") && !message.contains("Notifications")) {
                    Log.e(TAG, "Write error: " + message);
                }
            }

            @Override
            public void onReconnectAttempt(int attempt, int maxAttempts) {}
        });

        // Сервис для ЧТЕНИЯ ответов
        readService = new BluetoothService(context);
        readService.setRawTextMode(true);

        readService.setCallback(new BluetoothService.BluetoothCallback() {
            @Override
            public void onConnectionStateChange(boolean connected) {
                Log.d(TAG, "Read service connection: " + connected);
                if (!connected) {
                    readReady = false;
                    handleDisconnection();
                }
            }

            @Override
            public void onServicesDiscovered(boolean success) {
                Log.d(TAG, "Read services discovered: " + success);
                if (success) {
                    Log.d(TAG, "Read service ready");
                    readReady = true;
                    checkBothReady();
                } else {
                    // Не показываем ошибку при нормальном отключении
                    if (isProcessing) {
                        notifyError("Не удалось настроить чтение");
                    }
                }
            }

            @Override
            public void onDataReceived(byte[] data, String formattedData) {}

            @Override
            public void onDataBatch(String formattedBatch, long totalBytes, double kbPerSecond) {
                Log.d(TAG, "=== onDataBatch START ===");
                Log.d(TAG, "Received data length: " + formattedBatch.length());
                Log.d(TAG, "Data: [" + formattedBatch.replace("\r", "\\r").replace("\n", "\\n") + "]");
                handleReceivedData(formattedBatch);
                Log.d(TAG, "=== onDataBatch END ===");
            }

            @Override
            public void onError(String message) {
                if (!message.contains("MTU") && !message.contains("Notifications")) {
                    Log.e(TAG, "Read error: " + message);
                }
            }

            @Override
            public void onReconnectAttempt(int attempt, int maxAttempts) {}
        });
    }

    private volatile boolean shouldStopPolling = false;

    private void sendNextCommand() {
        // КРИТИЧНО: Проверяем, нужно ли остановить опрос
        if (shouldStopPolling) {
            Log.d(TAG, "Polling stopped by request");
            isProcessing = false;
            return;
        }

        if (commandIndex >= BASIC_COMMANDS.length) {
            finishCommandSequence();
            return;
        }

        currentCommand = BASIC_COMMANDS[commandIndex];
        responseBuffer.setLength(0);

        if (!writeService.isConnected()) {
            Log.e(TAG, "✗ Write service not connected!");
            notifyError("Устройство отключилось");
            isProcessing = false;
            return;
        }

        Log.d(TAG, "=== SENDING COMMAND [" + (commandIndex + 1) + "/" + BASIC_COMMANDS.length + "] ===");
        Log.d(TAG, "Command: " + currentCommand);

        String commandWithTerminator = currentCommand + COMMAND_TERMINATOR;
        boolean sent = writeService.sendCommand(commandWithTerminator);

        if (sent) {
            Log.d(TAG, "✓ Command sent to device");
            notifyProgress(commandIndex + 1, BASIC_COMMANDS.length);
            startCommandTimeout();
        } else {
            Log.e(TAG, "✗ Failed to send command!");
            saveResponse(currentCommand, "ERROR");
            moveToNextCommand();
        }
    }

    // Новый метод для остановки опроса
    public void stopPolling() {
        Log.d(TAG, "Stopping polling sequence");
        shouldStopPolling = true;
        isProcessing = false;
        cancelCommandTimeout();
    }

    public void connect(String deviceAddress) {
        this.deviceAddress = deviceAddress;
        this.writeReady = false;
        this.readReady = false;
        this.isProcessing = false;
        this.deviceInfo.clear();

        shouldStopPolling = false;

        Log.d(TAG, "=== MT DEVICE CONNECTION START ===");
        Log.d(TAG, "Device address: " + deviceAddress);

        writeService.connectForCommands(deviceAddress, SERVICE_UUID, CHAR_UUID_FFF2);
        readService.connect(deviceAddress, SERVICE_UUID, CHAR_UUID_FFF1);
    }

    private void startCommandSequence() {
        isProcessing = true;
        commandIndex = 0;
        deviceInfo.clear();

        Log.d(TAG, "=== STARTING COMMAND SEQUENCE ===");
        Log.d(TAG, "Total commands: " + BASIC_COMMANDS.length);
        sendNextCommand();
    }

    private void startCommandTimeout() {
        cancelCommandTimeout();

        timeoutRunnable = () -> {
            Log.w(TAG, "=== TIMEOUT ===");
            Log.w(TAG, "Command: " + currentCommand);
            saveResponse(currentCommand, "TIMEOUT");
            moveToNextCommand();
        };

        mainHandler.postDelayed(timeoutRunnable, COMMAND_TIMEOUT);
    }

    private void cancelCommandTimeout() {
        if (timeoutRunnable != null) {
            mainHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    private void handleReceivedData(String data) {
        Log.d(TAG, "=== handleReceivedData START ===");

        if (!isProcessing || currentCommand == null) {
            Log.w(TAG, "Ignoring data - not processing or no current command");
            return;
        }

        responseBuffer.append(data);
        String bufferContent = responseBuffer.toString();

        // Ищем имя команды без "?"
        String commandBase = currentCommand.replace("?", "");

        // КРИТИЧНО: Ищем ПОСЛЕДНЕЕ вхождение команды в буфере
        int lastCommandIndex = bufferContent.lastIndexOf(commandBase);

        if (lastCommandIndex == -1) {
            Log.d(TAG, "Command base not found yet in buffer");
            return; // Команда еще не пришла
        }

        // Проверяем, есть ли перенос строки после команды
        int nextLineIndex = bufferContent.indexOf("\r", lastCommandIndex);
        if (nextLineIndex == -1) {
            nextLineIndex = bufferContent.indexOf("\n", lastCommandIndex);
        }

        if (nextLineIndex == -1) {
            Log.d(TAG, "No line terminator after command yet");
            return; // Ответ еще не полный
        }

        // Нашли полный ответ
        cancelCommandTimeout();

        // Извлекаем строку с ответом: "CommandName value\r"
        String responseLine = bufferContent.substring(lastCommandIndex, nextLineIndex).trim();

        Log.d(TAG, "=== RESPONSE COMPLETE ===");
        Log.d(TAG, "Command: " + currentCommand);
        Log.d(TAG, "Full response line: [" + responseLine + "]");

        // Убираем имя команды, оставляем только значение
        String response;
        if (responseLine.startsWith(commandBase)) {
            response = responseLine.substring(commandBase.length()).trim();
        } else {
            response = responseLine;
        }

        Log.d(TAG, "Extracted response: [" + response + "]");

        saveResponse(currentCommand, response);
        moveToNextCommand();
    }

    private String extractLastResponse(String fullResponse, String command) {
        String commandBase = command.replace("?", "");
        String[] lines = fullResponse.split("\n");

        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.startsWith(commandBase)) {
                String value = line.substring(commandBase.length()).trim();
                return value;
            }
        }

        if (lines.length > 0) {
            return lines[lines.length - 1].trim();
        }

        return "";
    }

    private void saveResponse(String command, String response) {
        deviceInfo.put(command, response);
        notifyCommandResponse(command, response);
    }

    private void moveToNextCommand() {
        commandIndex++;
        responseBuffer.setLength(0);
        mainHandler.postDelayed(this::sendNextCommand, COMMAND_DELAY);
    }

    private void finishCommandSequence() {
        isProcessing = false;
        cancelCommandTimeout();

        Log.d(TAG, "=== COMMAND SEQUENCE FINISHED ===");
        Log.d(TAG, "Total responses: " + deviceInfo.size());

        notifyDeviceInfoReady();
    }

    private void handleDisconnection() {
        if (writeReady || readReady) {
            return;
        }

        Log.d(TAG, "=== DISCONNECTED ===");
        isProcessing = false;
        cancelCommandTimeout();
    }

    public void requestData() {
        if (writeService != null && writeService.isConnected()) {
            Log.d(TAG, "Requesting data with Data? command");
            writeService.sendCommand("Data?" + COMMAND_TERMINATOR);
        } else {
            notifyError("Устройство не подключено");
        }
    }

    public boolean setRange(int rangeIndex) {
        Log.d(TAG, "=== setRange CALLED ===");
        Log.d(TAG, "Range index: " + rangeIndex);
        Log.d(TAG, "writeService != null: " + (writeService != null));

        if (writeService != null) {
            Log.d(TAG, "writeService.isConnected(): " + writeService.isConnected());
        }

        if (writeService != null && writeService.isConnected()) {
            String command = "Ranges " + rangeIndex + COMMAND_TERMINATOR;
            Log.d(TAG, "Sending range command: [" + command.replace("\r", "\\r") + "]");
            boolean sent = writeService.sendCommand(command);
            Log.d(TAG, "Command send result: " + sent);
            return sent;
        } else {
            Log.e(TAG, "Cannot send range command - not connected!");
            notifyError("Устройство не подключено");
            return false;
        }
    }

    public boolean setUnits(int unitsIndex) {
        Log.d(TAG, "=== setUnits CALLED ===");
        Log.d(TAG, "Units index: " + unitsIndex);
        Log.d(TAG, "writeService != null: " + (writeService != null));

        if (writeService != null) {
            Log.d(TAG, "writeService.isConnected(): " + writeService.isConnected());
        }

        if (writeService != null && writeService.isConnected()) {
            String command = "Units " + unitsIndex + COMMAND_TERMINATOR;
            Log.d(TAG, "Sending units command: [" + command.replace("\r", "\\r") + "]");
            boolean sent = writeService.sendCommand(command);
            Log.d(TAG, "Command send result: " + sent);
            return sent;
        } else {
            Log.e(TAG, "Cannot send units command - not connected!");
            notifyError("Устройство не подключено");
            return false;
        }
    }

    private int pendingUnitsCommand = -1;
    private int pendingRangeCommand = -1;

    public void setPendingCommands(int units, int range) {
        this.pendingUnitsCommand = units;
        this.pendingRangeCommand = range;
        Log.d(TAG, "Set pending commands - units: " + units + ", range: " + range);
    }

    public boolean isConnected() {
        return writeService != null && writeService.isConnected() &&
                readService != null && readService.isConnected();
    }

    private void checkBothReady() {
        if (writeReady && readReady && !isProcessing) {
            Log.d(TAG, "=== BOTH SERVICES READY ===");
            notifyConnectionState(true);

            // Проверяем, есть ли отложенные команды
            if (pendingUnitsCommand != -1 || pendingRangeCommand != -1) {
                Log.d(TAG, "Found pending commands, sending now...");

                // Ждем немного, чтобы соединение стабилизировалось
                mainHandler.postDelayed(() -> {
                    if (pendingUnitsCommand != -1) {
                        Log.d(TAG, "Sending pending units: " + pendingUnitsCommand);
                        setUnits(pendingUnitsCommand);
                        pendingUnitsCommand = -1;
                    }

                    if (pendingRangeCommand != -1) {
                        Log.d(TAG, "Sending pending range: " + pendingRangeCommand);
                        setRange(pendingRangeCommand);
                        pendingRangeCommand = -1;
                    }
                }, 500); // Небольшая задержка для стабилизации
            } else {
                // Обычный режим - запускаем опрос
                Log.d(TAG, "Waiting " + INITIAL_DELAY + "ms before starting commands...");
                mainHandler.postDelayed(this::startCommandSequence, INITIAL_DELAY);
            }
        }
    }

    public Map<String, String> getDeviceInfo() {
        return new HashMap<>(deviceInfo);
    }

    private final AtomicBoolean isDisconnecting = new AtomicBoolean(false);

    public void disconnect() {
        if (!isDisconnecting.compareAndSet(false, true)) {
            Log.d(TAG, "Already disconnecting, skipping");
            return;
        }

        Log.d(TAG, "Disconnecting");
        cancelCommandTimeout();

        if (writeService != null) {
            writeService.disconnect();
        }
        if (readService != null) {
            readService.disconnect();
        }

        isProcessing = false;
        writeReady = false;
        readReady = false;

        isDisconnecting.set(false);
    }

    public void cleanup() {
        Log.d(TAG, "Cleanup");
        disconnect();

        if (writeService != null) {
            writeService.close();
            writeService = null;
        }
        if (readService != null) {
            readService.close();
            readService = null;
        }

        mainHandler.removeCallbacksAndMessages(null);
    }

    private void notifyConnectionState(boolean connected) {
        if (callback != null) {
            mainHandler.post(() -> callback.onConnectionStateChanged(connected));
        }
    }

    private void notifyDeviceInfoReady() {
        if (callback != null) {
            mainHandler.post(() -> callback.onDeviceInfoReady(new HashMap<>(deviceInfo)));
        }
    }

    private void notifyCommandResponse(String command, String response) {
        if (callback != null) {
            mainHandler.post(() -> callback.onCommandResponse(command, response));
        }
    }

    private void notifyError(String message) {
        if (callback != null) {
            mainHandler.post(() -> callback.onError(message));
        }
    }

    private void notifyProgress(int current, int total) {
        if (callback != null) {
            mainHandler.post(() -> callback.onProgress(current, total));
        }
    }
}