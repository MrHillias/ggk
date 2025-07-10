package com.example.ggk;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;

import com.example.ggk.BluetoothService;
import com.google.android.material.card.MaterialCardView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressLint("MissingPermission")
public class DataTransferFragment extends Fragment {
    private static final String TAG = "DataTransferFragment";
    private static final UUID SERVICE_UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    private static final UUID READ_UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");
    private static final long NO_DATA_TIMEOUT = 2000; // 2 секунды без данных

    private TextView textView;
    private TextView infoTextView;
    private TextView statusView;
    private View progressOverlay;
    private NestedScrollView scrollView;
    private NestedScrollView infoScrollView;
    private Button reconnectButton;
    private TextView bytesReceivedView;
    private TextView transferSpeedView;
    private View statsContainer;
    private View statusIndicator;
    private View lowerSection;

    private BluetoothService bluetoothService;
    private Handler mainHandler;

    private StringBuilder dataBuffer = new StringBuilder();
    private StringBuilder infoBuffer = new StringBuilder();
    private List<String> numericData = new ArrayList<>();

    private boolean isFromHistory;
    private String deviceAddress;
    private String deviceName;

    private AtomicBoolean autoScroll = new AtomicBoolean(true);
    private boolean dataStarted = false;
    private long lastDataTime = 0;
    private long dataStartTime = 0;

    private Runnable noDataRunnable = this::processReceivedData;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainHandler = new Handler(Looper.getMainLooper());

        DeviceActivity activity = (DeviceActivity) getActivity();
        if (activity != null) {
            deviceAddress = activity.getDeviceAddress();
            deviceName = activity.getDeviceName();
            isFromHistory = activity.isFromHistory();
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_data_transfer, container, false);

        textView = view.findViewById(R.id.text_view);
        infoTextView = view.findViewById(R.id.info_text_view);
        statusView = view.findViewById(R.id.status_view);
        progressOverlay = view.findViewById(R.id.progress_overlay);
        scrollView = view.findViewById(R.id.scroll_view);
        infoScrollView = view.findViewById(R.id.info_scroll_view);
        reconnectButton = view.findViewById(R.id.reconnect_button);
        bytesReceivedView = view.findViewById(R.id.bytes_received);
        transferSpeedView = view.findViewById(R.id.transfer_speed);
        statsContainer = view.findViewById(R.id.stats_container);
        statusIndicator = view.findViewById(R.id.status_indicator);
        lowerSection = view.findViewById(R.id.lower_section);

        reconnectButton.setOnClickListener(v -> connectToDevice());

        scrollView.setOnTouchListener((v, event) -> {
            autoScroll.set(false);
            mainHandler.removeCallbacks(autoScrollEnableRunnable);
            mainHandler.postDelayed(autoScrollEnableRunnable, 3000);
            return false;
        });

        if (isFromHistory) {
            // Загружаем данные из файлов
            loadDataFromFiles();
            progressOverlay.setVisibility(View.GONE);
            reconnectButton.setVisibility(View.GONE);
            statsContainer.setVisibility(View.GONE);
            lowerSection.setVisibility(View.GONE); // Скрываем нижнюю секцию

            // Увеличиваем шрифт для информации об устройстве
            infoTextView.setTextSize(16); // Увеличен с 12sp до 16sp
        } else {
            // Подключаемся к устройству
            initializeBluetooth();
        }

        return view;
    }

    private void initializeBluetooth() {
        bluetoothService = new BluetoothService(requireContext());
        bluetoothService.setCallback(bluetoothCallback);
        connectToDevice();
    }

    private void connectToDevice() {
        if (bluetoothService == null) {
            initializeBluetooth();
            return;
        }

        updateStatus("Подключение...");
        showProgress(true);
        reconnectButton.setVisibility(View.GONE);

        bluetoothService.connect(deviceAddress, SERVICE_UUID, READ_UUID);
    }

    private void showProgress(boolean show) {
        progressOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void updateStatusIndicator(int colorResId) {
        if (statusIndicator != null) {
            statusIndicator.setBackgroundColor(getResources().getColor(colorResId));
        }
    }

    private final BluetoothService.BluetoothCallback bluetoothCallback = new BluetoothService.BluetoothCallback() {
        @Override
        public void onConnectionStateChange(boolean connected) {
            if (connected) {
                updateStatus("Подключено");
                updateStatusIndicator(R.color.bluetooth_connected);
                showProgress(false);
                statsContainer.setVisibility(View.VISIBLE);
            } else {
                updateStatus("Отключено");
                updateStatusIndicator(R.color.bluetooth_disconnected);
                showProgress(false);
                reconnectButton.setVisibility(View.VISIBLE);
                statsContainer.setVisibility(View.GONE);
            }
        }

        @Override
        public void onServicesDiscovered(boolean success) {
            if (success) {
                updateStatus("Готов к приему данных");
                updateStatusIndicator(R.color.bluetooth_scanning);
            } else {
                updateStatus("Ошибка обнаружения сервисов");
                updateStatusIndicator(R.color.bluetooth_disconnected);
            }
        }

        @Override
        public void onDataReceived(byte[] data, String formattedData) {
            // Обрабатывается в onDataBatch
        }

        @Override
        public void onDataBatch(String formattedBatch, long totalBytes, double kbPerSecond) {
            lastDataTime = System.currentTimeMillis();

            // Отменяем таймер отсутствия данных
            mainHandler.removeCallbacks(noDataRunnable);

            // Добавляем данные в буфер
            dataBuffer.append(formattedBatch);

            // Проверяем, началась ли передача данных
            String currentData = dataBuffer.toString();
            int startIndex = currentData.indexOf("Start");

            if (!dataStarted && startIndex != -1) {
                dataStarted = true;
                dataStartTime = System.currentTimeMillis();

                // Сохраняем информацию до Start
                infoBuffer.append(currentData.substring(0, startIndex));

                // Отображаем информацию до Start в верхнем окне
                infoTextView.setText(translateAndFilterInfo(infoBuffer.toString()));

                // Очищаем нижнее окно для отображения данных после Start
                textView.setText("");

                // Удаляем из буфера всё до Start включительно
                dataBuffer = new StringBuilder(currentData.substring(startIndex + 5));
            }

            if (dataStarted) {
                // Отображаем только данные после Start в нижнем окне
                textView.setText(dataBuffer.toString());
            } else {
                // Пока не встретили Start, показываем всё в верхнем окне
                infoTextView.setText(currentData);
            }

            // Обновляем статистику
            bytesReceivedView.setText(String.format(Locale.US, "%.2f KB", totalBytes / 1024.0));
            transferSpeedView.setText(String.format(Locale.US, "%.2f KB/s", kbPerSecond));
            updateStatusIndicator(R.color.data_received);

            if (autoScroll.get()) {
                scrollToBottom();
            }

            // Запускаем таймер на случай отсутствия новых данных
            mainHandler.postDelayed(noDataRunnable, NO_DATA_TIMEOUT);
        }

        @Override
        public void onError(String message) {
            updateStatus("Ошибка: " + message);
        }

        @Override
        public void onReconnectAttempt(int attempt, int maxAttempts) {
            updateStatus("Переподключение... (" + attempt + "/" + maxAttempts + ")");
        }
    };

    private void processReceivedData() {
        if (dataBuffer.length() == 0) {
            return;
        }

        updateStatus("Обработка данных...");

        String data = dataBuffer.toString();

        // Удаляем End если есть
        int endIndex = data.lastIndexOf("End");
        if (endIndex != -1) {
            data = data.substring(0, endIndex);
        }

        // Парсим числовые данные
        parseNumericData(data);

        // Сохраняем в файлы
        saveDataToFiles();

        // Отключаемся
        if (bluetoothService != null) {
            bluetoothService.disconnect();
        }

        updateStatus("Данные сохранены");

        // Обновляем график в родительской активности
        DeviceActivity activity = (DeviceActivity) getActivity();
        if (activity != null) {
            activity.updateGraph();
        }
    }

    private void parseNumericData(String data) {
        numericData.clear();

        // Разбираем данные после Start
        String[] lines = data.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (!line.isEmpty()) {
                // Извлекаем числа из строки
                String[] parts = line.split("\\s+");
                for (String part : parts) {
                    if (isNumeric(part)) {
                        numericData.add(part);
                    }
                }
            }
        }
    }

    private boolean isNumeric(String str) {
        try {
            Double.parseDouble(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void saveDataToFiles() {
        try {
            // Создаем папку для устройства
            File deviceFolder = new File(requireContext().getFilesDir(), sanitizeFileName(deviceName));
            if (!deviceFolder.exists()) {
                deviceFolder.mkdirs();
            }

            // Сохраняем адрес устройства
            DeviceInfoHelper.saveDeviceAddress(requireContext(), deviceName, deviceAddress);

            // Сохраняем info.txt
            File infoFile = new File(deviceFolder, "info.txt");
            try (FileWriter writer = new FileWriter(infoFile)) {
                writer.write(infoBuffer.toString());
            }

            // Сохраняем data.txt в обратном порядке с временными метками
            File dataFile = new File(deviceFolder, "data.txt");
            try (FileWriter writer = new FileWriter(dataFile)) {
                // Реверсируем список данных
                Collections.reverse(numericData);

                // Вычисляем временные метки
                SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault());
                long endTime = dataStartTime - (numericData.size() * 1000L); // Вычитаем по секунде на значение

                for (int i = 0; i < numericData.size(); i++) {
                    long timestamp = endTime + (i * 1000L);
                    String time = sdf.format(new Date(timestamp));
                    writer.write(numericData.get(i) + ";" + time + "\n");
                }
            }

            Toast.makeText(getContext(), "Данные сохранены в папку " + deviceFolder.getName(),
                    Toast.LENGTH_LONG).show();

        } catch (IOException e) {
            Log.e(TAG, "Ошибка сохранения файлов", e);
            Toast.makeText(getContext(), "Ошибка сохранения: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void loadDataFromFiles() {
        try {
            File deviceFolder = new File(requireContext().getFilesDir(), sanitizeFileName(deviceName));

            // Загружаем info.txt в верхнее окно
            File infoFile = new File(deviceFolder, "info.txt");
            if (infoFile.exists()) {
                StringBuilder content = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new FileReader(infoFile))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        content.append(line).append("\n");
                    }
                }
                // Применяем перевод и фильтрацию
                String translatedContent = translateAndFilterInfo(content.toString());
                infoTextView.setText(translatedContent);
                updateStatus("Данные загружены из файла");
            } else {
                infoTextView.setText("Файл info.txt не найден");
                updateStatus("Ошибка загрузки");
            }

        } catch (IOException e) {
            Log.e(TAG, "Ошибка загрузки файлов", e);
            infoTextView.setText("Ошибка загрузки: " + e.getMessage());
            updateStatus("Ошибка");
        }
    }

    private String sanitizeFileName(String name) {
        // Заменяем недопустимые символы
        return name.replaceAll("[^a-zA-Z0-9.-]", "_");
    }

    private void updateStatus(String status) {
        statusView.setText("Статус: " + status);
    }

    private void scrollToBottom() {
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

    private final Runnable autoScrollEnableRunnable = () -> {
        autoScroll.set(true);
        scrollToBottom();
    };

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (bluetoothService != null) {
            bluetoothService.close();
        }
        mainHandler.removeCallbacksAndMessages(null);
    }

    private String translateAndFilterInfo(String info) {
        // Разбиваем на строки
        String[] lines = info.split("\n");
        StringBuilder result = new StringBuilder();

        for (String line : lines) {
            String translatedLine = line;

            // Пропускаем строки с Filter и MemoryPeriod
            if (line.contains("Filter") || line.contains("MemoryPeriod")) {
                continue;
            }

            // Переводим известные термины
            translatedLine = translatedLine.replace("SN", "Серийный номер");
            translatedLine = translatedLine.replace("Ranges", "Диапазоны");
            translatedLine = translatedLine.replace("CurrentRange", "Текущий диапазон");
            translatedLine = translatedLine.replace("SamplingPeriod", "Частота съема показаний");
            translatedLine = translatedLine.replace("BatteryLevel", "Уровень заряда батареи");
            translatedLine = translatedLine.replace("OperatingTime", "Время работы");
            translatedLine = translatedLine.replace("RemainingBatteryLife", "Ожидаемое время работы устройства");
            translatedLine = translatedLine.replace("Temp", "Температура");
            translatedLine = translatedLine.replace("MaximumAppliedForAllTime", "Максимум за все время");

            result.append(translatedLine).append("\n");
        }

        return result.toString().trim();
    }
}