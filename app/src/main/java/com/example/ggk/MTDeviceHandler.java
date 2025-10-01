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
    private static final UUID CHAR_UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");

    // Константы - увеличенные значения для стабильности
    private static final long COMMAND_TIMEOUT = 5000; // Увеличено до 5 секунд
    private static final long COMMAND_DELAY = 1000; // Увеличено до 1 секунды
    private static final String COMMAND_TERMINATOR = "\r";

    // Список команд для опроса
    private static final String[] BASIC_COMMANDS = {
            "DataSize?",
            "WorkTime?",
            "Idn?",
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
                Log.d(TAG, "onDataBatch - length: " + formattedBatch.length());
                Log.d(TAG, "onDataBatch - content: [" + formattedBatch.replace("\r", "\\r").replace("\n", "\\n") + "]");
                handleReceivedData(formattedBatch);
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
            Log.d(TAG, "Both services ready!");
            notifyConnectionState(true);
            mainHandler.postDelayed(this::startCommandSequence, 1000);
        }
    }

    public void connect(String deviceAddress) {
        this.deviceAddress = deviceAddress;
        this.writeReady = false;
        this.readReady = false;
        this.isProcessing = false;
        this.deviceInfo.clear();

        Log.d(TAG, "Connecting to device: " + deviceAddress);

        writeService.connectForCommands(deviceAddress, SERVICE_UUID, CHAR_UUID);
        readService.connect(deviceAddress, SERVICE_UUID, CHAR_UUID);
    }

    private void startCommandSequence() {
        isProcessing = true;
        commandIndex = 0;
        deviceInfo.clear();

        Log.d(TAG, "Starting command sequence with " + BASIC_COMMANDS.length + " commands");
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
            Log.e(TAG, "Write service not connected");
            notifyError("Устройство отключилось");
            isProcessing = false;
            return;
        }

        Log.d(TAG, "Sending command [" + (commandIndex + 1) + "/" + BASIC_COMMANDS.length + "]: " + currentCommand);

        String commandWithTerminator = currentCommand + COMMAND_TERMINATOR;
        Log.d(TAG, "Command bytes: " + Arrays.toString(commandWithTerminator.getBytes()));

        boolean sent = writeService.sendCommand(commandWithTerminator);

        if (sent) {
            notifyProgress(commandIndex + 1, BASIC_COMMANDS.length);
            startCommandTimeout();
        } else {
            Log.e(TAG, "Failed to send command: " + currentCommand);
            saveResponse(currentCommand, "ERROR");
            moveToNextCommand();
        }
    }

    private void startCommandTimeout() {
        cancelCommandTimeout();

        timeoutRunnable = () -> {
            Log.w(TAG, "Timeout for command: " + currentCommand);
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
        Log.d(TAG, "handleReceivedData called with data length: " + data.length());
        Log.d(TAG, "Data content: [" + data.replace("\r", "\\r").replace("\n", "\\n") + "]");

        if (!isProcessing || currentCommand == null) {
            Log.w(TAG, "Ignoring data - not processing or no current command");
            return;
        }

        responseBuffer.append(data);
        String bufferContent = responseBuffer.toString();

        Log.d(TAG, "Buffer content: [" + bufferContent.replace("\r", "\\r").replace("\n", "\\n") + "]");
        Log.d(TAG, "Current buffer length: " + bufferContent.length());

        // Проверяем окончание ответа - ищем \r, \n или их комбинацию
        boolean hasTerminator = bufferContent.contains("\r") || bufferContent.contains("\n");

        // Также проверяем, не пришел ли весь ответ уже (некоторые устройства могут не отправлять терминатор)
        // Если прошло достаточно данных, считаем ответ полным
        boolean hasEnoughData = bufferContent.length() > 5;

        // Дополнительная проверка: если данные выглядят как законченный ответ (содержат пробелы и символы)
        boolean looksComplete = bufferContent.length() > 3 &&
                (bufferContent.contains(" ") || bufferContent.matches(".*[A-Za-z0-9]+.*"));

        if (hasTerminator || (hasEnoughData && looksComplete)) {
            cancelCommandTimeout();

            // Очищаем ответ от всех управляющих символов
            String response = bufferContent
                    .replace("\r", "")
                    .replace("\n", "")
                    .trim();

            Log.d(TAG, "Received complete response for " + currentCommand + ": " + response);

            saveResponse(currentCommand, response);
            moveToNextCommand();
        } else {
            Log.d(TAG, "Waiting for more data or terminator (current length: " + bufferContent.length() + ")");

            // Устанавливаем короткий таймер для завершения приема, если больше данных не будет
            mainHandler.postDelayed(() -> {
                if (isProcessing && currentCommand != null && responseBuffer.length() > 0) {
                    Log.d(TAG, "Force completing response after delay");
                    cancelCommandTimeout();

                    String response = responseBuffer.toString()
                            .replace("\r", "")
                            .replace("\n", "")
                            .trim();

                    if (!response.isEmpty()) {
                        Log.d(TAG, "Force completed response for " + currentCommand + ": " + response);
                        saveResponse(currentCommand, response);
                        moveToNextCommand();
                    }
                }
            }, 500); // Ждем еще 500мс для дополнительных данных
        }
    }

    private void saveResponse(String command, String response) {
        deviceInfo.put(command, response);
        notifyCommandResponse(command, response);
    }

    private void moveToNextCommand() {
        commandIndex++;
        responseBuffer.setLength(0); // Очищаем буфер перед следующей командой
        mainHandler.postDelayed(this::sendNextCommand, COMMAND_DELAY);
    }

    private void finishCommandSequence() {
        isProcessing = false;
        cancelCommandTimeout();

        Log.d(TAG, "Command sequence finished. Collected " + deviceInfo.size() + " responses");

        // Выводим все полученные ответы
        for (Map.Entry<String, String> entry : deviceInfo.entrySet()) {
            Log.d(TAG, "Final result - " + entry.getKey() + ": " + entry.getValue());
        }

        notifyDeviceInfoReady();
    }

    private void handleDisconnection() {
        if (writeReady || readReady) {
            return;
        }

        isProcessing = false;
        cancelCommandTimeout();
        notifyConnectionState(false);
    }

    public void requestData() {
        if (writeService != null && writeService.isConnected()) {
            Log.d(TAG, "Requesting data");
            writeService.sendCommand("Data?\r");
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