package com.example.ggk;

import static androidx.core.app.PendingIntentCompat.getActivity;
import static androidx.core.content.ContentProviderCompat.requireContext;

import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MTDeviceHandler {
    boolean firstTime = true;
    private static final String TAG = "MTDeviceHandler";
    private static final UUID SERVICE_UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    private static final UUID WRITE_UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb");
    private static final UUID READ_UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");

    private static final long COMMAND_TIMEOUT = 3000; // 3 секунды на ответ
    private static final String COMMAND_TERMINATOR = "\r";

    private final Context context;
    private final BluetoothService bluetoothService;
    private final Handler mainHandler;
    private MTDeviceCallback callback;

    private List<String> supportedCommands = new ArrayList<>();
    private Map<String, String> deviceInfo = new HashMap<>();
    private StringBuilder responseBuffer = new StringBuilder();
    private String currentCommand = null;
    private int commandIndex = 0;
    private boolean isProcessing = false;

    private Runnable timeoutRunnable;

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
        this.bluetoothService = new BluetoothService(context);

        setupBluetoothCallbacks();
    }

    private void setupBluetoothCallbacks() {
        bluetoothService.setCallback(new BluetoothService.BluetoothCallback() {
            @Override
            public void onConnectionStateChange(boolean connected) {
                if (connected && !isProcessing) {
                    // Начинаем с команды Help
                    startCommandSequence();
                }
                callback.onConnectionStateChanged(connected);
            }

            @Override
            public void onServicesDiscovered(boolean success) {
                if (!success) {
                    callback.onError("Не удалось обнаружить сервисы");
                }
            }

            @Override
            public void onDataReceived(byte[] data, String formattedData) {
                processReceivedData(new String(data));
            }

            @Override
            public void onDataBatch(String formattedBatch, long totalBytes, double kbPerSecond) {
                // Не используется для командного режима
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }

            @Override
            public void onReconnectAttempt(int attempt, int maxAttempts) {
                // Обработка переподключения при необходимости
            }
        });
    }

    public void connect(String deviceAddress) {
        bluetoothService.connectForCommands(deviceAddress, SERVICE_UUID, WRITE_UUID);
    }

    private void startCommandSequence() {
        isProcessing = true;
        responseBuffer.setLength(0);
        sendCommand("Help");
    }

    private void sendCommand(String command) {
        currentCommand = command;
        responseBuffer.setLength(0);

        Log.d(TAG, "Sending command: " + command);
        boolean sent = bluetoothService.sendCommand(command + COMMAND_TERMINATOR);

        if (sent) {
            startTimeout();
        } else {
            callback.onError("Не удалось отправить команду: " + command);
            processNextCommand();
        }
    }

    private void startTimeout() {
        cancelTimeout();
        timeoutRunnable = () -> {
            Log.w(TAG, "Timeout for command: " + currentCommand);
            handleCommandResponse(currentCommand, "TIMEOUT");
            processNextCommand();
        };
        mainHandler.postDelayed(timeoutRunnable, COMMAND_TIMEOUT);
    }

    private void cancelTimeout() {
        if (timeoutRunnable != null) {
            mainHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    private void processReceivedData(String data) {
        responseBuffer.append(data);

        // Проверяем, получен ли полный ответ
        if (responseBuffer.toString().contains(COMMAND_TERMINATOR)) {
            cancelTimeout();
            String response = responseBuffer.toString().replace(COMMAND_TERMINATOR, "").trim();

            Log.d(TAG, "Received response for " + currentCommand + ": " + response);

            if ("Help".equals(currentCommand)) {
                parseHelpResponse(response);
            } else {
                handleCommandResponse(currentCommand, response);
            }

            processNextCommand();
        }
    }

    private void parseHelpResponse(String response) {
        // Парсим список поддерживаемых команд
        supportedCommands.clear();
        String[] commands = response.split("\\s+");

        for (String cmd : commands) {
            if (cmd.endsWith("?")) {
                supportedCommands.add(cmd);
            }
        }

        Log.d(TAG, "Supported commands: " + supportedCommands);
        commandIndex = 0;
    }

    private void processNextCommand() {
        if (commandIndex < supportedCommands.size()) {
            String nextCommand = supportedCommands.get(commandIndex);
            commandIndex++;

            // Обновляем прогресс
            callback.onProgress(commandIndex, supportedCommands.size());

            // Отправляем следующую команду
            sendCommand(nextCommand);
        } else {
            // Все команды обработаны
            isProcessing = false;
            callback.onDeviceInfoReady(deviceInfo);
        }
    }

    private void handleCommandResponse(String command, String response) {
        // Сохраняем ответ
        deviceInfo.put(command, response);

        // Уведомляем о получении ответа
        callback.onCommandResponse(command, response);

        // Специальная обработка для известных команд
        parseSpecialCommands(command, response);
    }

    private void parseSpecialCommands(String command, String response) {
        switch (command) {
            case "DataSize?":
                // Парсим размер данных
                try {
                    int dataSize = Integer.parseInt(response);
                    deviceInfo.put("dataSize", String.valueOf(dataSize));
                } catch (NumberFormatException e) {
                    Log.e(TAG, "Failed to parse DataSize: " + response);
                }
                break;

            case "Time?":
                // Парсим время устройства
                deviceInfo.put("deviceTime", response);
                break;

            case "Units?":
                // Парсим текущие единицы измерения
                deviceInfo.put("currentUnits", response);
                break;

            case "Range?":
                // Парсим текущий диапазон
                deviceInfo.put("currentRange", response);
                break;

            case "Ranges?":
                // Парсим диапазоны (формат: "Ranges X")
                deviceInfo.put("ranges", response);
                // Извлекаем числовое значение
                String rangeValue = extractNumericValue(response);
                if (rangeValue != null) {
                    deviceInfo.put("rangesValue", rangeValue);
                }
                break;

            case "MeasureFreq?":
                // Парсим частоту измерений
                deviceInfo.put("measureFrequency", response);
                break;

            case "Idn?":
                // Парсим идентификатор устройства
                deviceInfo.put("deviceId", response);
                break;

            // Добавьте обработку других команд по мере необходимости
        }
    }

    // Вспомогательный метод для извлечения числового значения из ответа
    private String extractNumericValue(String response) {
        String[] parts = response.trim().split("\\s+");
        for (String part : parts) {
            try {
                Integer.parseInt(part);
                return part;
            } catch (NumberFormatException e) {
                // Продолжаем поиск числа
            }
        }
        return null;
    }

    public void disconnect() {
        cancelTimeout();
        if (bluetoothService != null) {
            bluetoothService.disconnect();
        }
    }

    public void cleanup() {
        cancelTimeout();
        if (bluetoothService != null) {
            bluetoothService.close();
        }
    }

    // Метод для получения данных после настройки
    public void requestData() {
        if (bluetoothService != null && bluetoothService.isConnected()) {
            sendCommand("Ranges 1");
        } else {
            callback.onError("Устройство не подключено");
        }
    }

    // Метод для отправки команды изменения диапазона
    public void setRange(int rangeValue, MTDeviceCallback callback) {
        if (bluetoothService != null && bluetoothService.isConnected()) {
            String command = "Ranges " + rangeValue;
            Log.d(TAG, "Sending range command: " + command);
            bluetoothService.sendCommand(command + COMMAND_TERMINATOR);

            if (callback != null) {
                // Временный callback для обработки ответа на команду изменения
                this.callback = callback;
            }
        } else {
            if (callback != null) {
                callback.onError("Устройство не подключено");
            }
        }
    }

    // Вспомогательный класс для хранения информации о команде
    public static class CommandInfo {
        public final String command;
        public final String displayName;
        public final String description;
        public final boolean isRequired;

        public CommandInfo(String command, String displayName, String description, boolean isRequired) {
            this.command = command;
            this.displayName = displayName;
            this.description = description;
            this.isRequired = isRequired;
        }
    }

    // Предопределенные команды с описаниями
    private static final Map<String, CommandInfo> KNOWN_COMMANDS = new HashMap<>();
    static {
        KNOWN_COMMANDS.put("DataSize?", new CommandInfo("DataSize?", "Размер данных", "Количество сохраненных измерений", true));
        KNOWN_COMMANDS.put("Time?", new CommandInfo("Time?", "Время", "Текущее время устройства", true));
        KNOWN_COMMANDS.put("Broadcast?", new CommandInfo("Broadcast?", "Режим вещания", "Статус режима передачи", false));
        KNOWN_COMMANDS.put("Units?", new CommandInfo("Units?", "Единицы измерения", "Текущие единицы измерения", true));
        KNOWN_COMMANDS.put("UnitsAll?", new CommandInfo("UnitsAll?", "Все единицы", "Поддерживаемые единицы измерения", false));
        KNOWN_COMMANDS.put("Range?", new CommandInfo("Range?", "Диапазон", "Текущий диапазон измерений", true));
        KNOWN_COMMANDS.put("Ranges?", new CommandInfo("Ranges?", "Диапазоны", "Управление диапазонами измерений", true));
        KNOWN_COMMANDS.put("RangeAll?", new CommandInfo("RangeAll?", "Все диапазоны", "Поддерживаемые диапазоны", false));
        KNOWN_COMMANDS.put("MeasureFreq?", new CommandInfo("MeasureFreq?", "Частота измерений", "Частота снятия показаний", true));
        KNOWN_COMMANDS.put("RecordFreq?", new CommandInfo("RecordFreq?", "Частота записи", "Частота сохранения данных", false));
        KNOWN_COMMANDS.put("Filter?", new CommandInfo("Filter?", "Фильтр", "Настройки фильтрации", false));
        KNOWN_COMMANDS.put("Idn?", new CommandInfo("Idn?", "Идентификатор", "Модель и серийный номер", true));
        KNOWN_COMMANDS.put("PmaxAllTime?", new CommandInfo("PmaxAllTime?", "Макс. давление", "Максимальное давление за все время", false));
        KNOWN_COMMANDS.put("Pminmax24?", new CommandInfo("Pminmax24?", "Мин/Макс 24ч", "Минимальное и максимальное за 24 часа", false));
    }

    public static CommandInfo getCommandInfo(String command) {
        return KNOWN_COMMANDS.get(command);
    }
}