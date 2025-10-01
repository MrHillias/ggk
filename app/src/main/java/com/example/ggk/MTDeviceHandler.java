package com.example.ggk;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MTDeviceHandler {
    private static final String TAG = "MTDeviceHandler";

    // UUIDs
    private static final UUID SERVICE_UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_UUID_FFF1 = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_UUID_FFF2 = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb");

    // Константы
    private static final long COMMAND_TIMEOUT = 5000; // 5 секунд
    private static final long COMMAND_DELAY = 1000; // 1 секунда между командами
    private static final long INITIAL_DELAY = 1000; // Задержка перед началом команд
    private static final String COMMAND_TERMINATOR = "\r";

    // Список команд для опроса
    private static final String[] BASIC_COMMANDS = {
            "Idn?",
            "DataSize?",
            "WorkTime?",
            "PmaxAllTime?",
            "Pminmax24?"
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

        // КРИТИЧЕСКИ ВАЖНО: Включаем режим чистого текста для MT устройств
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
                    notifyError("Не удалось настроить чтение");
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

    private void checkBothReady() {
        if (writeReady && readReady && !isProcessing) {
            Log.d(TAG, "=== BOTH SERVICES READY ===");
            notifyConnectionState(true);

            Log.d(TAG, "Waiting " + INITIAL_DELAY + "ms before starting commands...");
            mainHandler.postDelayed(this::startCommandSequence, INITIAL_DELAY);
        }
    }

    public void connect(String deviceAddress) {
        this.deviceAddress = deviceAddress;
        this.writeReady = false;
        this.readReady = false;
        this.isProcessing = false;
        this.deviceInfo.clear();

        Log.d(TAG, "=== MT DEVICE CONNECTION START ===");
        Log.d(TAG, "Device address: " + deviceAddress);
        Log.d(TAG, "Write UUID: " + CHAR_UUID_FFF2);
        Log.d(TAG, "Read UUID: " + CHAR_UUID_FFF1);
        Log.d(TAG, "Command terminator: " + COMMAND_TERMINATOR.replace("\r", "\\r").replace("\n", "\\n"));
        Log.d(TAG, "Commands to send: " + Arrays.toString(BASIC_COMMANDS));

        // Пробуем FFF2 для записи команд (стандарт)
        Log.d(TAG, "Connecting write service to FFF2...");
        writeService.connectForCommands(deviceAddress, SERVICE_UUID, CHAR_UUID_FFF2);

        // Читаем ответы с FFF1
        Log.d(TAG, "Connecting read service to FFF1...");
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

    private void sendNextCommand() {
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
        Log.d(TAG, "With terminator: [" + commandWithTerminator.replace("\r", "\\r").replace("\n", "\\n") + "]");
        Log.d(TAG, "Bytes: " + Arrays.toString(commandWithTerminator.getBytes()));

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

    private void startCommandTimeout() {
        cancelCommandTimeout();

        timeoutRunnable = () -> {
            Log.w(TAG, "=== TIMEOUT ===");
            Log.w(TAG, "Command: " + currentCommand);
            Log.w(TAG, "Buffer content: [" + responseBuffer.toString().replace("\r", "\\r").replace("\n", "\\n") + "]");
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
        Log.d(TAG, "isProcessing: " + isProcessing);
        Log.d(TAG, "currentCommand: " + currentCommand);
        Log.d(TAG, "Received data: [" + data.replace("\r", "\\r").replace("\n", "\\n") + "]");

        if (!isProcessing || currentCommand == null) {
            Log.w(TAG, "Ignoring data - not processing or no current command");
            Log.d(TAG, "=== handleReceivedData END (ignored) ===");
            return;
        }

        responseBuffer.append(data);
        String bufferContent = responseBuffer.toString();

        Log.d(TAG, "Buffer now: [" + bufferContent.replace("\r", "\\r").replace("\n", "\\n") + "]");
        Log.d(TAG, "Buffer length: " + bufferContent.length());

        // Проверяем окончание ответа
        boolean hasTerminator = bufferContent.contains("\r") || bufferContent.contains("\n");
        boolean hasEnoughData = bufferContent.length() > 3;
        boolean looksComplete = bufferContent.length() > 0 &&
                (bufferContent.contains(" ") || bufferContent.matches(".*[A-Za-z0-9]+.*"));

        Log.d(TAG, "hasTerminator: " + hasTerminator);
        Log.d(TAG, "hasEnoughData: " + hasEnoughData);
        Log.d(TAG, "looksComplete: " + looksComplete);

        if (hasTerminator || (hasEnoughData && looksComplete)) {
            cancelCommandTimeout();

            // Очищаем ответ от управляющих символов
            String fullResponse = bufferContent
                    .replace("\r", "\n")
                    .trim();

            Log.d(TAG, "Full response with newlines: [" + fullResponse + "]");

            // ВАЖНО: Извлекаем только последнюю строку (текущий ответ)
            String response = extractLastResponse(fullResponse, currentCommand);

            Log.d(TAG, "=== RESPONSE COMPLETE ===");
            Log.d(TAG, "Command: " + currentCommand);
            Log.d(TAG, "Extracted response: [" + response + "]");

            saveResponse(currentCommand, response);
            moveToNextCommand();
        } else {
            Log.d(TAG, "Waiting for more data...");

            // Таймер для принудительного завершения
            mainHandler.postDelayed(() -> {
                if (isProcessing && currentCommand != null && responseBuffer.length() > 0) {
                    Log.d(TAG, "=== FORCE COMPLETING RESPONSE ===");
                    cancelCommandTimeout();

                    String fullResponse = responseBuffer.toString()
                            .replace("\r", "\n")
                            .trim();

                    String response = extractLastResponse(fullResponse, currentCommand);

                    if (!response.isEmpty()) {
                        Log.d(TAG, "Forced response: [" + response + "]");
                        saveResponse(currentCommand, response);
                        moveToNextCommand();
                    }
                }
            }, 500);
        }

        Log.d(TAG, "=== handleReceivedData END ===");
    }

    /**
     * Извлекает последний ответ из буфера, игнорируя мусор
     */
    private String extractLastResponse(String fullResponse, String command) {
        // Убираем "?" из команды для поиска
        String commandBase = command.replace("?", "");

        Log.d(TAG, "Extracting response for command: " + commandBase);

        // Разбиваем на строки
        String[] lines = fullResponse.split("\n");

        // Ищем строку, которая начинается с нашей команды
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            Log.d(TAG, "Checking line " + i + ": [" + line + "]");

            if (line.startsWith(commandBase)) {
                // Нашли! Извлекаем значение после команды
                String value = line.substring(commandBase.length()).trim();
                Log.d(TAG, "Found matching line, extracted value: [" + value + "]");
                return value;
            }
        }

        // Если не нашли по точному совпадению, попробуем найти в последней строке
        if (lines.length > 0) {
            String lastLine = lines[lines.length - 1].trim();
            Log.d(TAG, "No exact match found, using last line: [" + lastLine + "]");

            // Убираем мусор из начала (AT команды и т.д.)
            if (lastLine.contains(commandBase)) {
                int cmdIndex = lastLine.indexOf(commandBase);
                String value = lastLine.substring(cmdIndex + commandBase.length()).trim();
                Log.d(TAG, "Extracted from last line: [" + value + "]");
                return value;
            }

            return lastLine;
        }

        Log.d(TAG, "Could not extract response, returning empty string");
        return "";
    }

    private void saveResponse(String command, String response) {
        deviceInfo.put(command, response);
        notifyCommandResponse(command, response);
    }

    private void moveToNextCommand() {
        commandIndex++;
        responseBuffer.setLength(0);

        Log.d(TAG, "Moving to next command after " + COMMAND_DELAY + "ms delay");
        mainHandler.postDelayed(this::sendNextCommand, COMMAND_DELAY);
    }

    private void finishCommandSequence() {
        isProcessing = false;
        cancelCommandTimeout();

        Log.d(TAG, "=== COMMAND SEQUENCE FINISHED ===");
        Log.d(TAG, "Total responses: " + deviceInfo.size());

        // Выводим все результаты
        for (Map.Entry<String, String> entry : deviceInfo.entrySet()) {
            String status = "TIMEOUT".equals(entry.getValue()) ? "✗" : "✓";
            Log.d(TAG, status + " " + entry.getKey() + ": " + entry.getValue());
        }

        notifyDeviceInfoReady();
    }

    private void handleDisconnection() {
        if (writeReady || readReady) {
            return;
        }

        Log.d(TAG, "=== DISCONNECTED ===");
        isProcessing = false;
        cancelCommandTimeout();
        notifyConnectionState(false);
    }

    public void requestData() {
        if (writeService != null && writeService.isConnected()) {
            Log.d(TAG, "Requesting data with Data? command");
            writeService.sendCommand("Data?" + COMMAND_TERMINATOR);
        } else {
            notifyError("Устройство не подключено");
        }
    }

    public void setRange(int rangeValue) {
        if (writeService != null && writeService.isConnected()) {
            String command = "Ranges " + rangeValue + COMMAND_TERMINATOR;
            Log.d(TAG, "Sending range command: " + command);
            writeService.sendCommand(command);
        } else {
            notifyError("Устройство не подключено");
        }
    }

    public void disconnect() {
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