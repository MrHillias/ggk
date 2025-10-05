package com.example.ggk;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class MTDataFragment extends Fragment {
    private static final String TAG = "MTDataFragment";
    private static final UUID SERVICE_UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    private static final UUID WRITE_UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb");
    private static final UUID READ_UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");

    private String deviceAddress;
    private String deviceName;

    private TextView dataTextView;
    private TextView statusTextView;
    private TextView dataCountTextView;
    private LinearProgressIndicator progressIndicator;
    private MaterialButton startButton;
    private MaterialButton stopButton;
    private MaterialButton saveButton;
    private MaterialButton clearButton;
    private NestedScrollView scrollView;

    private BluetoothService bluetoothService;
    private Handler mainHandler;
    private List<Double> receivedData = new ArrayList<>();
    private StringBuilder dataBuffer = new StringBuilder();
    private boolean isReceivingData = false;
    private boolean appendMode = false;
    private long dataStartTime;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainHandler = new Handler(Looper.getMainLooper());

        MTDeviceActivity activity = (MTDeviceActivity) getActivity();
        if (activity != null) {
            deviceAddress = activity.getDeviceAddress();
            deviceName = activity.getDeviceName();
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mt_data, container, false);

        dataTextView = view.findViewById(R.id.data_text_view);
        statusTextView = view.findViewById(R.id.status_text_view);
        dataCountTextView = view.findViewById(R.id.data_count_text_view);
        progressIndicator = view.findViewById(R.id.progress_indicator);
        startButton = view.findViewById(R.id.start_button);
        stopButton = view.findViewById(R.id.stop_button);
        saveButton = view.findViewById(R.id.save_button);
        clearButton = view.findViewById(R.id.clear_button);
        scrollView = view.findViewById(R.id.scroll_view);

        setupButtons();
        initializeBluetooth();

        return view;
    }

    private void setupButtons() {
        startButton.setOnClickListener(v -> startDataTransfer());
        stopButton.setOnClickListener(v -> stopDataTransfer());
        saveButton.setOnClickListener(v -> saveDataToFile());
        clearButton.setOnClickListener(v -> clearData());

        stopButton.setEnabled(false);
        saveButton.setEnabled(false);
    }

    private void initializeBluetooth() {
        bluetoothService = new BluetoothService(requireContext());
        bluetoothService.setCallback(new BluetoothService.BluetoothCallback() {
            @Override
            public void onConnectionStateChange(boolean connected) {
                mainHandler.post(() -> {
                    if (connected) {
                        statusTextView.setText("Подключено. Готово к приему данных.");
                        startButton.setEnabled(true);
                    } else {
                        statusTextView.setText("Отключено");
                        startButton.setEnabled(false);
                        stopButton.setEnabled(false);
                        isReceivingData = false;
                    }
                });
            }

            @Override
            public void onServicesDiscovered(boolean success) {
                if (!success) {
                    mainHandler.post(() -> {
                        statusTextView.setText("Ошибка обнаружения сервисов");
                    });
                }
            }

            @Override
            public void onDataReceived(byte[] data, String formattedData) {
                // Не используется
            }

            @Override
            public void onDataBatch(String formattedBatch, long totalBytes, double kbPerSecond) {
                if (isReceivingData) {
                    processReceivedData(formattedBatch);
                }
            }

            @Override
            public void onError(String message) {
                mainHandler.post(() -> {
                    Toast.makeText(getContext(), "Ошибка: " + message, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onReconnectAttempt(int attempt, int maxAttempts) {
                mainHandler.post(() -> {
                    statusTextView.setText(String.format("Переподключение %d/%d", attempt, maxAttempts));
                });
            }
        });
    }


    private final BluetoothService.BluetoothCallback bluetoothCallback = new BluetoothService.BluetoothCallback() {
        @Override
        public void onConnectionStateChange(boolean connected) {
            Log.d(TAG, "=== CALLBACK: onConnectionStateChange: " + connected + " ===");
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (connected) {
                    statusTextView.setText("Подключено, ожидание готовности...");
                    Log.d(TAG, "Connected, waiting for services...");
                } else {
                    statusTextView.setText("Отключено");
                    progressIndicator.setVisibility(View.GONE);
                    startButton.setEnabled(true);
                    stopButton.setEnabled(false);
                    isReceivingData = false;
                }
            });
        }

        @Override
        public void onServicesDiscovered(boolean success) {
            Log.d(TAG, "=== CALLBACK: onServicesDiscovered: " + success + " ===");
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                Log.d(TAG, "onServicesDiscovered callback: success=" + success);
                if (success) {
                    // КРИТИЧНО: Этот блок должен выполниться!
                    Log.d(TAG, "Services ready, sending SendData command...");
                    statusTextView.setText("Готово, отправка команды...");

                    // Небольшая задержка для стабилизации
                    mainHandler.postDelayed(() -> {
                        sendDataCommand();
                    }, 500);
                } else {
                    statusTextView.setText("Ошибка обнаружения сервисов");
                    progressIndicator.setVisibility(View.GONE);
                    startButton.setEnabled(true);
                    isReceivingData = false;
                }
            });
        }

        @Override
        public void onDataReceived(byte[] data, String formattedData) {
            // Не используется
        }

        @Override
        public void onDataBatch(String formattedBatch, long totalBytes, double kbPerSecond) {
            if (isReceivingData) {
                Log.d(TAG, "Received data batch: " + formattedBatch.length() + " chars");
                processReceivedData(formattedBatch);
            }
        }

        @Override
        public void onError(String message) {
            Log.d(TAG, "=== CALLBACK: onError: " + message + " ===");
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                Toast.makeText(getContext(), "Ошибка: " + message, Toast.LENGTH_SHORT).show();
            });
        }

        @Override
        public void onReconnectAttempt(int attempt, int maxAttempts) {
            Log.d(TAG, "=== CALLBACK: onReconnectAttempt: " + attempt + "/" + maxAttempts + " ===");
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                statusTextView.setText(String.format("Переподключение %d/%d", attempt, maxAttempts));
            });
        }
    };

    private void clearData() {
        receivedData.clear();
        dataBuffer.setLength(0);
        dataTextView.setText("");
        dataCountTextView.setText("Получено точек: 0");
        statusTextView.setText("Данные очищены");
        saveButton.setEnabled(false);
    }

    private String sanitizeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9.-]", "_");
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (bluetoothService != null) {
            bluetoothService.close();
        }
    }

    private boolean autoMode = false; // Новый флаг для автоматического режима

    @Override
    public void onResume() {
        super.onResume();

        // Проверяем, нужно ли автоматически начать получение данных
        MTDeviceActivity activity = (MTDeviceActivity) getActivity();
        if (activity != null && activity.shouldAutoStartDataDownload()) {
            autoMode = true;
            activity.clearAutoStartFlag();

            // Автоматически начинаем получение данных
            new android.os.Handler().postDelayed(() -> {
                Log.d(TAG, "AUTO MODE: Starting data transfer automatically");
                startDataTransfer();
            }, 500);
        }
    }
    private void startDataTransfer() {
        Log.d(TAG, "=== startDataTransfer CALLED ===");

        // Проверяем, есть ли уже сохраненные данные
        File deviceFolder = new File(requireContext().getFilesDir(), sanitizeFileName(deviceName));
        File dataFile = new File(deviceFolder, "data.txt");

        if (dataFile.exists() && dataFile.length() > 0) {
            Log.d(TAG, "Found existing data file, size: " + dataFile.length());

            // Считаем количество существующих точек
            int existingPoints = countDataPoints(dataFile);

            // Спрашиваем пользователя
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Данные уже существуют")
                    .setMessage(String.format("Найдено %d точек данных. Что сделать?", existingPoints))
                    .setPositiveButton("Перезаписать", (dialog, which) -> {
                        Log.d(TAG, "User chose to overwrite existing data");
                        appendMode = false;
                        continueDataTransfer();
                    })
                    .setNeutralButton("Добавить", (dialog, which) -> {
                        Log.d(TAG, "User chose to append to existing data");
                        appendMode = true;
                        continueDataTransfer();
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
        } else {
            appendMode = false;
            continueDataTransfer();
        }
    }
    private void continueDataTransfer() {
        Log.d(TAG, "=== continueDataTransfer ===");
        Log.d(TAG, "autoMode: " + autoMode);

        // КРИТИЧНО: Создаем новый BluetoothService И устанавливаем callback ДО подключения
        if (bluetoothService != null) {
            bluetoothService.close();
            bluetoothService = null;
        }

        bluetoothService = new BluetoothService(requireContext());
        bluetoothService.setCallback(bluetoothCallback); // ВАЖНО: ДО connect()!

        Log.d(TAG, "BluetoothService created, callback set");

        // Сбрасываем данные
        receivedData.clear();
        dataBuffer.setLength(0);
        dataTextView.setText("");

        statusTextView.setText("Подключение...");
        progressIndicator.setVisibility(View.VISIBLE);
        startButton.setEnabled(false);

        // КРИТИЧНО: Подключаемся С NOTIFICATIONS сразу
        bluetoothService.setRawTextMode(false); // Парсим Start/End
        Log.d(TAG, "Connecting to device...");
        bluetoothService.connect(deviceAddress, SERVICE_UUID, READ_UUID);

        isReceivingData = true;
    }

    private int countDataPoints(File dataFile) {
        int count = 0;
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(dataFile))) {
            while (reader.readLine() != null) {
                count++;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error counting data points", e);
        }
        return count;
    }

    private long getLastTimestamp(File dataFile) {
        String lastLine = null;
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(dataFile, "r")) {
            long fileLength = file.length() - 1;
            StringBuilder sb = new StringBuilder();

            for (long filePointer = fileLength; filePointer != -1; filePointer--) {
                file.seek(filePointer);
                int readByte = file.readByte();

                if (readByte == 0xA) { // LF
                    if (filePointer < fileLength) {
                        lastLine = sb.reverse().toString();
                        break;
                    }
                } else if (readByte != 0xD) { // Ignore CR
                    sb.append((char) readByte);
                }
            }

            if (lastLine == null && sb.length() > 0) {
                lastLine = sb.reverse().toString();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading last timestamp", e);
            return 0;
        }

        if (lastLine != null && lastLine.contains(";")) {
            String[] parts = lastLine.split(";");
            if (parts.length >= 2) {
                try {
                    SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault());
                    Date date = sdf.parse(parts[1].trim());
                    return date != null ? date.getTime() : 0;
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing last timestamp", e);
                }
            }
        }

        return 0;
    }
    private void sendDataCommand() {
        if (!bluetoothService.isConnected()) {
            statusTextView.setText("Не подключено");
            return;
        }

        dataStartTime = System.currentTimeMillis();
        statusTextView.setText("Отправка команды SendData...");

        // Отправляем команду - notifications УЖЕ включены
        boolean sent = bluetoothService.sendCommand("SendData\r");

        if (sent) {
            Log.d(TAG, "SendData command sent, waiting for data stream...");
            statusTextView.setText("Получение данных...");
            stopButton.setEnabled(true);

            // Данные начнут приходить через onDataBatch
        } else {
            Log.e(TAG, "Failed to send SendData - writeCharacteristic not available");
            statusTextView.setText("Ошибка: не удалось отправить команду");

            Toast.makeText(getContext(),
                    "writeCharacteristic недоступна. Попробуйте отключиться и подключиться заново.",
                    Toast.LENGTH_LONG).show();

            isReceivingData = false;
            progressIndicator.setVisibility(View.GONE);
            startButton.setEnabled(true);
        }
    }

    private void processReceivedData(String data) {
        mainHandler.post(() -> {
            Log.d(TAG, "=== processReceivedData ===");
            Log.d(TAG, "Current receivedData.size BEFORE processing: " + receivedData.size());

            dataBuffer.append(data);

            // КРИТИЧНО: Убираем "End\r\n" из данных перед парсингом
            String processedData = data;
            int endIndex = processedData.indexOf("End");
            if (endIndex != -1) {
                processedData = processedData.substring(0, endIndex);
                Log.d(TAG, "Found 'End' in data, trimmed from " + data.length() +
                        " to " + processedData.length() + " chars");
            }

            // Собираем байтовые значения
            String[] lines = processedData.split("\\s+");
            List<Integer> byteValues = new ArrayList<>();

            for (String line : lines) {
                line = line.trim();
                if (line.equals("Start") || line.isEmpty()) {
                    continue;
                }

                try {
                    int value = Integer.parseInt(line);
                    byteValues.add(value);
                } catch (NumberFormatException e) {
                    // Игнорируем нечисловые данные
                }
            }

            Log.d(TAG, "Extracted " + byteValues.size() + " byte values from this chunk");

            // НОВОЕ: Логируем последние 20 байтов
            if (byteValues.size() > 20) {
                StringBuilder lastBytes = new StringBuilder("Last 20 bytes: ");
                for (int i = byteValues.size() - 20; i < byteValues.size(); i++) {
                    lastBytes.append(byteValues.get(i)).append(" ");
                }
                Log.d(TAG, lastBytes.toString());
            }

            // Обрабатываем попарно как signed int16
            int beforeSize = receivedData.size();
            for (int i = 0; i < byteValues.size() - 1; i += 2) {
                int lowByte = byteValues.get(i);
                int highByte = byteValues.get(i + 1);

                short signedValue = (short)((highByte << 8) | lowByte);
                receivedData.add((double)signedValue);

                if (receivedData.size() <= 5) {
                    Log.d(TAG, String.format("Pair: [%3d, %3d] -> int16: %6d",
                            lowByte, highByte, signedValue));
                }

                // НОВОЕ: Логируем последние 10 пар
                if (i >= byteValues.size() - 20 && i < byteValues.size() - 1) {
                    Log.d(TAG, String.format("LAST Pair %d: [%3d, %3d] -> int16: %6d",
                            i/2, lowByte, highByte, signedValue));
                }
            }

            int addedCount = receivedData.size() - beforeSize;
            if (addedCount > 0) {
                Log.d(TAG, "Added " + addedCount + " values");
                Log.d(TAG, "Total receivedData.size AFTER processing: " + receivedData.size());
            }

            updateDataDisplay();

            if (data.contains("End") || data.contains("END")) {
                Log.d(TAG, "=== END SEQUENCE DETECTED ===");
                Log.d(TAG, "Final receivedData.size: " + receivedData.size());

                // НОВОЕ: Выводим последние 15 значений из receivedData
                StringBuilder lastValues = new StringBuilder("Last 15 receivedData values: ");
                int startIdx = Math.max(0, receivedData.size() - 15);
                for (int i = startIdx; i < receivedData.size(); i++) {
                    lastValues.append(receivedData.get(i)).append(" ");
                }
                Log.d(TAG, lastValues.toString());

                stopDataTransfer();

                if (autoMode && !receivedData.isEmpty()) {
                    autoMode = false;
                    saveDataToFile();
                }
            }
        });
    }

    private void updateDataDisplay() {
        dataCountTextView.setText(String.format("Получено точек: %d", receivedData.size()));

        // Показываем последние 100 точек
        StringBuilder display = new StringBuilder();
        int startIndex = Math.max(0, receivedData.size() - 100);
        for (int i = startIndex; i < receivedData.size(); i++) {
            display.append(String.format("%.2f ", receivedData.get(i)));
            if ((i - startIndex + 1) % 10 == 0) {
                display.append("\n");
            }
        }

        dataTextView.setText(display.toString());

        // Автопрокрутка вниз
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

    private void stopDataTransfer() {
        Log.d(TAG, "=== stopDataTransfer ===");
        Log.d(TAG, "receivedData.size at stop: " + receivedData.size());

        isReceivingData = false;
        progressIndicator.setVisibility(View.GONE);
        startButton.setEnabled(true);
        stopButton.setEnabled(false);

        if (!receivedData.isEmpty()) {
            saveButton.setEnabled(true);
            long duration = System.currentTimeMillis() - dataStartTime;
            statusTextView.setText(String.format("Получено %d точек за %.1f сек",
                    receivedData.size(), duration / 1000.0));
        } else {
            statusTextView.setText("Данные не получены");
        }

        // Отправляем команду остановки если нужно
        if (bluetoothService != null && bluetoothService.isConnected()) {
            bluetoothService.sendCommand("Stop\r");
        }
    }

    private void saveDataToFile() {
        try {
            Log.d(TAG, "=== SAVING DATA TO FILE ===");
            Log.d(TAG, "receivedData.size(): " + receivedData.size());
            Log.d(TAG, "appendMode: " + appendMode);

            // Выводим первые 10 значений
            StringBuilder firstVals = new StringBuilder("First 10 values: ");
            for (int i = 0; i < Math.min(10, receivedData.size()); i++) {
                firstVals.append(receivedData.get(i)).append(" ");
            }
            Log.d(TAG, firstVals.toString());

            // НОВОЕ: Выводим последние 10 значений
            StringBuilder lastVals = new StringBuilder("Last 10 values: ");
            int startIdx = Math.max(0, receivedData.size() - 10);
            for (int i = startIdx; i < receivedData.size(); i++) {
                lastVals.append(receivedData.get(i)).append(" ");
            }
            Log.d(TAG, lastVals.toString());

            // Конвертируем List<Double> в double[]
            double[] values = new double[receivedData.size()];
            for (int i = 0; i < receivedData.size(); i++) {
                values[i] = receivedData.get(i);
            }

            Log.d(TAG, "Converted to array, length: " + values.length);
            Log.d(TAG, "Device name: " + deviceName);
            Log.d(TAG, "Device address: " + deviceAddress);

            // Определяем время начала
            long startTime;
            if (appendMode) {
                File deviceFolder = new File(requireContext().getFilesDir(), sanitizeFileName(deviceName));
                File dataFile = new File(deviceFolder, "data.txt");
                long lastTimestamp = getLastTimestamp(dataFile);

                if (lastTimestamp > 0) {
                    // Новые данные начинаются через 1 секунду после последней записи
                    startTime = lastTimestamp + 1000;
                    Log.d(TAG, "Append mode: starting from " + new Date(startTime));
                } else {
                    startTime = dataStartTime;
                    Log.d(TAG, "Append mode: but no last timestamp found, using dataStartTime");
                }
            } else {
                startTime = dataStartTime;
                Log.d(TAG, "Overwrite mode: using dataStartTime " + new Date(startTime));
            }

            // Используем helper для сохранения данных в совместимом формате
            MTDeviceDataHelper.saveMTData(requireContext(), deviceName, deviceAddress,
                    values, startTime, appendMode);

            Log.d(TAG, "Data saved successfully");

            String message = appendMode ?
                    "Добавлено " + receivedData.size() + " точек" :
                    "Данные сохранены (" + receivedData.size() + " точек)";

            Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();

            statusTextView.setText(appendMode ? "Данные добавлены" : "Данные сохранены");

            // Обновляем график
            MTDeviceActivity activity = (MTDeviceActivity) getActivity();
            if (activity != null) {
                MTDeviceActivity.MTPagerAdapter adapter = activity.getPagerAdapter();
                if (adapter != null && adapter.getGraphFragment() != null) {
                    adapter.getGraphFragment().loadAndDisplayGraph();
                }

                // Переключаемся на вкладку "График"
                activity.runOnUiThread(() -> {
                    androidx.viewpager2.widget.ViewPager2 viewPager = activity.findViewById(R.id.view_pager);
                    if (viewPager != null) {
                        viewPager.setCurrentItem(2, true); // Вкладка "График"
                    }
                });
            }

        } catch (Exception e) {
            Log.e(TAG, "Ошибка сохранения файла", e);
            Toast.makeText(getContext(),
                    "Ошибка сохранения: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

}