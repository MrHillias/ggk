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

    private void startDataTransfer() {
        if (bluetoothService == null || !bluetoothService.isConnected()) {
            // Подключаемся к устройству
            statusTextView.setText("Подключение...");
            progressIndicator.setVisibility(View.VISIBLE);
            bluetoothService.connectForCommands(deviceAddress, SERVICE_UUID, WRITE_UUID);

            // Ждем подключения и отправляем команду
            mainHandler.postDelayed(() -> {
                if (bluetoothService.isConnected()) {
                    sendDataCommand();
                }
            }, 2000);
        } else {
            sendDataCommand();
        }
    }

    private void sendDataCommand() {
        isReceivingData = true;
        dataStartTime = System.currentTimeMillis();
        statusTextView.setText("Запрос данных...");

        // Отправляем команду для получения данных
        boolean sent = bluetoothService.sendCommand("Data?\r");

        if (sent) {
            statusTextView.setText("Получение данных...");
            startButton.setEnabled(false);
            stopButton.setEnabled(true);
            progressIndicator.setVisibility(View.VISIBLE);

            // Переключаемся на режим чтения
            bluetoothService.disconnect();
            mainHandler.postDelayed(() -> {
                bluetoothService.connect(deviceAddress, SERVICE_UUID, READ_UUID);
            }, 500);
        } else {
            statusTextView.setText("Ошибка отправки команды");
            isReceivingData = false;
        }
    }

    private void processReceivedData(String data) {
        mainHandler.post(() -> {
            dataBuffer.append(data);

            // Парсим числовые данные
            String[] lines = data.split("\\s+");
            for (String line : lines) {
                try {
                    double value = Double.parseDouble(line.trim());
                    receivedData.add(value);
                } catch (NumberFormatException e) {
                    // Игнорируем нечисловые данные
                }
            }

            // Обновляем UI
            updateDataDisplay();

            // Проверяем на окончание передачи
            if (data.contains("End") || data.contains("END")) {
                stopDataTransfer();
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
            // Конвертируем List<Double> в double[]
            double[] values = new double[receivedData.size()];
            for (int i = 0; i < receivedData.size(); i++) {
                values[i] = receivedData.get(i);
            }

            // Используем helper для сохранения данных в совместимом формате
            MTDeviceDataHelper.saveMTData(requireContext(), deviceName, values, dataStartTime);

            Toast.makeText(getContext(),
                    "Данные сохранены (" + receivedData.size() + " точек)",
                    Toast.LENGTH_LONG).show();

            statusTextView.setText("Данные сохранены");

            // Обновляем график если он открыт
            MTDeviceActivity activity = (MTDeviceActivity) getActivity();
            if (activity != null) {
                MTDeviceActivity.MTPagerAdapter adapter = activity.getPagerAdapter();
                if (adapter != null && adapter.getGraphFragment() != null) {
                    adapter.getGraphFragment().loadAndDisplayGraph();
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Ошибка сохранения файла", e);
            Toast.makeText(getContext(),
                    "Ошибка сохранения: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

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
}