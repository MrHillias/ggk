package com.example.ggk;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;


import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.File;
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
    private static final long DATA_TIMEOUT = 3000; // 3 секунды без данных = конец передачи

    private Integer pendingByte = null;  // Непарный байт из предыдущего chunk'а

    private String deviceAddress;
    private String deviceName;

    private TextView statusTextView;
    private TextView dataCountTextView;
    private LinearProgressIndicator progressIndicator;
    private MaterialButton startButton;
    private MaterialButton stopButton;
    private MaterialButton saveButton;
    private MaterialButton clearButton;
    private RecyclerView dataRecyclerView;
    private View emptyState;
    private MTPacketAdapter packetAdapter;

    private BluetoothService bluetoothService;
    private Handler mainHandler;
    private List<Double> receivedData = new ArrayList<>();
    private StringBuilder dataBuffer = new StringBuilder();
    private boolean isReceivingData = false;
    private boolean appendMode = false;
    private long dataStartTime;
    private int pendingDelayValue = 0;
    private boolean waitingToSendCommands = false;
    private long lastDataReceivedTime = 0;

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

        statusTextView = view.findViewById(R.id.status_text_view);
        dataCountTextView = view.findViewById(R.id.data_count_text_view);
        progressIndicator = view.findViewById(R.id.progress_indicator);
        startButton = view.findViewById(R.id.start_button);
        stopButton = view.findViewById(R.id.stop_button);
        saveButton = view.findViewById(R.id.save_button);
        clearButton = view.findViewById(R.id.clear_button);
        dataRecyclerView = view.findViewById(R.id.data_recycler_view);
        emptyState = view.findViewById(R.id.empty_state);

        setupRecyclerView();
        setupButtons();
        initializeBluetooth();

        return view;
    }

    private void setupRecyclerView() {
        packetAdapter = new MTPacketAdapter();
        dataRecyclerView.setAdapter(packetAdapter);
        dataRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

        updateEmptyState();
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
                    Log.d(TAG, "Services ready, enabling numeric mode...");

                    // КРИТИЧНО: Включаем numeric mode ДЛЯ получения сырых байтов
                    if (bluetoothService != null) {
                        bluetoothService.forceNumericMode(true);
                        Log.d(TAG, "✓ Numeric mode forced ON");
                    }

                    statusTextView.setText("Готово, отправка команды...");

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
        packetAdapter.submitList(new ArrayList<>());
        updateEmptyState();
        dataCountTextView.setText("Получено точек: 0");
        statusTextView.setText("Данные очищены");
        saveButton.setEnabled(false);
        pendingByte = null;
        lastDataReceivedTime = 0;
        mainHandler.removeCallbacks(dataTimeoutRunnable);
    }

    private void updateEmptyState() {
        boolean isEmpty = (packetAdapter.getItemCount() == 0);
        emptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        dataRecyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    private String sanitizeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9.-]", "_");
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mainHandler.removeCallbacks(dataTimeoutRunnable);
        if (bluetoothService != null) {
            bluetoothService.close();
        }
    }

    private boolean autoMode = false;

    @Override
    public void onResume() {
        super.onResume();

        MTDeviceActivity activity = (MTDeviceActivity) getActivity();
        if (activity != null && activity.shouldAutoStartDataDownload()) {
            autoMode = true;
            activity.clearAutoStartFlag();

            new android.os.Handler().postDelayed(() -> {
                Log.d(TAG, "AUTO MODE: Starting data transfer automatically");
                startDataTransfer();
            }, 500);
        }
    }

    private void startDataTransfer() {
        /*
        Log.d(TAG, "=== startDataTransfer CALLED ===");

        File deviceFolder = new File(requireContext().getFilesDir(), sanitizeFileName(deviceName));
        File dataFile = new File(deviceFolder, "data.txt");

        if (dataFile.exists() && dataFile.length() > 0) {
            Log.d(TAG, "Found existing data file, size: " + dataFile.length());

            int existingPoints = countDataPoints(dataFile);

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
        */
        showDelayInputDialog();
    }

    /**
     * Показывает диалог для ввода задержки перед получением данных
     */
    private void showDelayInputDialog() {
        // Создаем диалог с полем ввода
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Задержка перед получением данных");

        // Создаем EditText для ввода числа
        final EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("Введите задержку (мс)");
        input.setText("1000"); // Значение по умолчанию 1000 мс

        // Добавляем отступы вокруг поля ввода
        LinearLayout container = new LinearLayout(requireContext());
        container.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(50, 20, 50, 20);
        input.setLayoutParams(params);
        container.addView(input);

        builder.setView(container);

        // Кнопка OK
        builder.setPositiveButton("OK", (dialog, which) -> {
            String delayStr = input.getText().toString().trim();

            if (delayStr.isEmpty()) {
                Toast.makeText(requireContext(), "Введите значение задержки", Toast.LENGTH_SHORT).show();
                return;
            }

            try {
                int delayValue = Integer.parseInt(delayStr);

                if (delayValue < 0) {
                    Toast.makeText(requireContext(), "Задержка не может быть отрицательной", Toast.LENGTH_SHORT).show();
                    return;
                }

                // ВАЖНО: Сначала подключаемся к устройству, потом отправляем команды
                connectAndSendCommands(delayValue);

            } catch (NumberFormatException e) {
                Toast.makeText(requireContext(), "Неверный формат числа", Toast.LENGTH_SHORT).show();
            }
        });

        // Кнопка Отмена
        builder.setNegativeButton("Отмена", (dialog, which) -> dialog.cancel());

        AlertDialog dialog = builder.create();
        dialog.show();

        // Автоматически показываем клавиатуру
        input.requestFocus();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    }

    /**
     * Подключается к устройству и отправляет команды
     */
    private void connectAndSendCommands(int delayValue) {
        isReceivingData = true;
        dataStartTime = System.currentTimeMillis();
        statusTextView.setText("Подключение к устройству...");
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        progressIndicator.setVisibility(View.VISIBLE);

        // Сохраняем значение задержки для использования после подключения
        this.pendingDelayValue = delayValue;
        this.waitingToSendCommands = true;

        Log.d(TAG, "Connecting to device for sending commands...");

        // Подключаемся к устройству для записи команд (WRITE_UUID)
        if (bluetoothService != null) {
            bluetoothService.connect(deviceAddress, SERVICE_UUID, WRITE_UUID);
        } else {
            Log.e(TAG, "BluetoothService is null!");
            statusTextView.setText("Ошибка: сервис Bluetooth недоступен");
            resetState();
        }
    }

    /**
     * Отправляет команду Delay, ждет 100мс, затем отправляет SendData
     * Вызывается после успешного подключения
     */
    private void sendDelayAndDataCommands(int delayValue) {
        statusTextView.setText("Установка задержки...");

        // Шаг 1: Отправляем команду Delay
        String delayCommand = "Delay " + delayValue + "\r";
        Log.d(TAG, "Sending delay command: " + delayCommand);

        boolean delaySent = bluetoothService.sendCommand(delayCommand);

        if (!delaySent) {
            Log.e(TAG, "Failed to send Delay command");
            statusTextView.setText("Ошибка отправки команды Delay");
            resetState();
            return;
        }

        Log.d(TAG, "Delay command sent successfully, waiting 100ms...");
        statusTextView.setText("Задержка установлена, запрос данных...");

        // Шаг 2: Ждем 100мс и отправляем SendData
        mainHandler.postDelayed(() -> {
            Log.d(TAG, "Sending SendData command...");
            sendDataCommand();
        }, 100); // 100 миллисекунд
    }

    private void resetState() {
        isReceivingData = false;
        waitingToSendCommands = false;
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        progressIndicator.setVisibility(View.GONE);
    }

    private void continueDataTransfer() {
        Log.d(TAG, "=== continueDataTransfer ===");
        Log.d(TAG, "autoMode: " + autoMode);

        if (bluetoothService != null) {
            bluetoothService.close();
            bluetoothService = null;
        }

        bluetoothService = new BluetoothService(requireContext());
        bluetoothService.setCallback(bluetoothCallback);

        Log.d(TAG, "BluetoothService created, callback set");

        receivedData.clear();
        dataBuffer.setLength(0);
        packetAdapter.submitList(new ArrayList<>());
        updateEmptyState();
        lastDataReceivedTime = 0;

        statusTextView.setText("Подключение...");
        progressIndicator.setVisibility(View.VISIBLE);
        startButton.setEnabled(false);

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

                if (readByte == 0xA) {
                    if (filePointer < fileLength) {
                        lastLine = sb.reverse().toString();
                        break;
                    }
                } else if (readByte != 0xD) {
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
        /*
        if (!bluetoothService.isConnected()) {
            statusTextView.setText("Не подключено");
            return;
        }

        dataStartTime = System.currentTimeMillis();
        lastDataReceivedTime = dataStartTime;
        statusTextView.setText("Отправка команды SendData...");

        boolean sent = bluetoothService.sendCommand("SendData\r");

        if (sent) {
            Log.d(TAG, "SendData command sent, waiting for data stream (no Begin/End markers)...");
            statusTextView.setText("Получение данных...");
            stopButton.setEnabled(true);

            mainHandler.postDelayed(dataTimeoutRunnable, DATA_TIMEOUT);
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

         */
        statusTextView.setText("Запрос данных...");

        // Отправляем команду "SendData" для MT устройств
        boolean sent = bluetoothService.sendCommand("SendData\r");

        if (sent) {
            Log.d(TAG, "SendData command sent successfully");
            statusTextView.setText("Ожидание ответа устройства...");

            // Ждем перед отключением
            mainHandler.postDelayed(() -> {
                Log.d(TAG, "Disconnecting write service, switching to read mode...");
                statusTextView.setText("Переключение на режим чтения...");

                if (bluetoothService != null) {
                    bluetoothService.disconnect();
                }

                // Ждем полного отключения перед переподключением
                mainHandler.postDelayed(() -> {
                    Log.d(TAG, "Connecting in read mode...");
                    statusTextView.setText("Получение данных...");

                    // Включаем парсинг Start/End для MT
                    if (bluetoothService != null) {
                        bluetoothService.setRawTextMode(false); // false = парсить Start/End
                        bluetoothService.connect(deviceAddress, SERVICE_UUID, READ_UUID);
                    }
                }, 1000); // 1 секунда на полное отключение

            }, 2000); // 2 секунды ждем после SendData

        } else {
            Log.e(TAG, "Failed to send SendData command");
            statusTextView.setText("Ошибка отправки команды");
            resetState();
        }
    }

    private void processReceivedData(String data) {
        mainHandler.post(() -> {
            Log.d(TAG, "=== processReceivedData ===");
            Log.d(TAG, "Current receivedData.size BEFORE processing: " + receivedData.size());
            Log.d(TAG, "Pending byte from previous chunk: " + pendingByte);

            dataBuffer.append(data);

            String[] lines = data.split("\\s+");
            List<Integer> byteValues = new ArrayList<>();

            if (pendingByte != null) {
                byteValues.add(pendingByte);
                Log.d(TAG, "Added pending byte from previous chunk: " + pendingByte);
                pendingByte = null;
            }

            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) {
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

            if (byteValues.size() % 2 == 1) {
                pendingByte = byteValues.remove(byteValues.size() - 1);
                Log.d(TAG, "Saved last byte for next chunk: " + pendingByte);
            }

            if (byteValues.size() >= 20) {
                StringBuilder lastBytes = new StringBuilder("Last 20 bytes: ");
                for (int i = byteValues.size() - 20; i < byteValues.size(); i++) {
                    lastBytes.append(byteValues.get(i)).append(" ");
                }
                Log.d(TAG, lastBytes.toString());
            }

            int beforeSize = receivedData.size();
            int anomalyCount = 0;

            for (int i = 0; i < byteValues.size() - 1; i += 2) {
                int lowByte = byteValues.get(i);
                int highByte = byteValues.get(i + 1);

                short signedValue = (short)((highByte << 8) | lowByte);

                // КРИТИЧНО: Фильтруем аномальные значения
                // Паттерн: lowByte = 0x00 и |value| > 1000 = явный мусор
                boolean isAnomaly = (lowByte == 0 && Math.abs(signedValue) > 1000);

                if (isAnomaly) {
                    anomalyCount++;
                    Log.w(TAG, String.format("⚠️ FILTERED ANOMALY: [%3d, %3d] -> %6d (skipped)",
                            lowByte, highByte, signedValue));
                    continue;
                }

                receivedData.add((double)signedValue);

                if (receivedData.size() <= 5) {
                    Log.d(TAG, String.format("Pair: [%3d, %3d] -> int16: %6d",
                            lowByte, highByte, signedValue));
                }

                if (i >= byteValues.size() - 20 && i < byteValues.size() - 1) {
                    Log.d(TAG, String.format("LAST Pair %d: [%3d, %3d] -> int16: %6d",
                            i/2, lowByte, highByte, signedValue));
                }
            }

            if (anomalyCount > 0) {
                Log.w(TAG, "Filtered out " + anomalyCount + " anomalous values");
            }

            int addedCount = receivedData.size() - beforeSize;
            if (addedCount > 0) {
                Log.d(TAG, "Added " + addedCount + " values");
                Log.d(TAG, "Total receivedData.size AFTER processing: " + receivedData.size());
            }

            updateDataDisplay();

            lastDataReceivedTime = System.currentTimeMillis();

            mainHandler.removeCallbacks(dataTimeoutRunnable);
            mainHandler.postDelayed(dataTimeoutRunnable, DATA_TIMEOUT);
        });
    }

    private final Runnable dataTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (isReceivingData) {
                Log.d(TAG, "=== DATA TIMEOUT - NO NEW DATA FOR " + DATA_TIMEOUT + "ms ===");
                Log.d(TAG, "Final receivedData.size: " + receivedData.size());

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
        }
    };

    private void updateDataDisplay() {
        dataCountTextView.setText(String.format("Получено точек: %d", receivedData.size()));

        // Разбиваем данные на пакеты
        List<MTPacketAdapter.PacketData> packets = parsePackets(receivedData);

        // Обновляем адаптер
        packetAdapter.submitList(packets);
        updateEmptyState();

        // Автопрокрутка к последнему элементу
        if (!packets.isEmpty()) {
            dataRecyclerView.post(() ->
                    dataRecyclerView.smoothScrollToPosition(packets.size() - 1));
        }
    }

    private List<MTPacketAdapter.PacketData> parsePackets(List<Double> data) {
        List<MTPacketAdapter.PacketData> packets = new ArrayList<>();

        final int PACKET_SIZE = 133; // 132 значения + 1 контрольная сумма

        int packetNumber = 1;
        for (int i = 0; i < data.size(); i += PACKET_SIZE) {
            // Проверяем, хватает ли данных для полного пакета
            if (i + PACKET_SIZE > data.size()) {
                Log.w(TAG, "Incomplete packet at position " + i +
                        ", remaining values: " + (data.size() - i));
                break;
            }

            // Извлекаем значения пакета (первые 132 элемента)
            List<Integer> packetValues = new ArrayList<>();
            for (int j = i; j < i + 132; j++) {
                packetValues.add(data.get(j).intValue());
            }

            // Последний элемент - контрольная сумма
            int receivedChecksum = data.get(i + 132).intValue();

            // Создаем пакет
            MTPacketAdapter.PacketData packet = new MTPacketAdapter.PacketData(
                    packetNumber,
                    packetValues,
                    receivedChecksum
            );

            packets.add(packet);

            // Логируем информацию о пакете
            if (packet.isChecksumValid()) {
                Log.d(TAG, String.format("Packet #%d: checksum OK (%d)",
                        packetNumber, receivedChecksum));
            } else {
                Log.w(TAG, String.format("Packet #%d: checksum ERROR! Received: %d, Calculated: %d",
                        packetNumber, receivedChecksum, packet.calculatedChecksum));
            }

            packetNumber++;
        }

        Log.d(TAG, "Parsed " + packets.size() + " packets from " + data.size() + " values");

        return packets;
    }

    private void stopDataTransfer() {
        Log.d(TAG, "=== stopDataTransfer ===");
        Log.d(TAG, "receivedData.size at stop: " + receivedData.size());

        isReceivingData = false;
        mainHandler.removeCallbacks(dataTimeoutRunnable);
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

        if (bluetoothService != null && bluetoothService.isConnected()) {
            bluetoothService.sendCommand("Stop\r");
        }
    }

    private void saveDataToFile() {
        Log.d(TAG, "▶▶▶ saveDataToFile() CALLED");

        try {
            Log.d(TAG, "=== SAVING DATA TO FILE ===");
            Log.d(TAG, "receivedData.size(): " + receivedData.size());
            Log.d(TAG, "appendMode: " + appendMode);

            StringBuilder firstVals = new StringBuilder("First 10 values: ");
            for (int i = 0; i < Math.min(10, receivedData.size()); i++) {
                firstVals.append(receivedData.get(i)).append(" ");
            }
            Log.d(TAG, firstVals.toString());

            StringBuilder lastVals = new StringBuilder("Last 10 values: ");
            int startIdx = Math.max(0, receivedData.size() - 10);
            for (int i = startIdx; i < receivedData.size(); i++) {
                lastVals.append(receivedData.get(i)).append(" ");
            }
            Log.d(TAG, lastVals.toString());

            double[] values = new double[receivedData.size()];
            for (int i = 0; i < receivedData.size(); i++) {
                values[i] = receivedData.get(i);
            }

            Log.d(TAG, "Converted to array, length: " + values.length);
            Log.d(TAG, "Device name: " + deviceName);
            Log.d(TAG, "Device address: " + deviceAddress);

            long startTime;
            if (appendMode) {
                File deviceFolder = new File(requireContext().getFilesDir(), sanitizeFileName(deviceName));
                File dataFile = new File(deviceFolder, "data.txt");
                long lastTimestamp = getLastTimestamp(dataFile);

                if (lastTimestamp > 0) {
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

            Log.d(TAG, "About to call saveMTData...");
            MTDeviceDataHelper.saveMTData(requireContext(), deviceName, deviceAddress,
                    values, startTime, appendMode);

            String message = appendMode ?
                    "Добавлено " + receivedData.size() + " точек" :
                    "Данные сохранены (" + receivedData.size() + " точек)";

            Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
            statusTextView.setText(appendMode ? "Данные добавлены" : "Данные сохранены");

            // Только обновление графика и переключение вкладки
            MTDeviceActivity activity = (MTDeviceActivity) getActivity();
            if (activity != null) {
                MTDeviceActivity.MTPagerAdapter adapter = activity.getPagerAdapter();
                if (adapter != null && adapter.getGraphFragment() != null) {
                    adapter.getGraphFragment().loadAndDisplayGraph();
                }

                activity.runOnUiThread(() -> {
                    androidx.viewpager2.widget.ViewPager2 viewPager = activity.findViewById(R.id.view_pager);
                    if (viewPager != null) {
                        viewPager.setCurrentItem(2, true);
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