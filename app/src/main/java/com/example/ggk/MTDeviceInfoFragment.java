package com.example.ggk;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;

import java.util.Map;

public class MTDeviceInfoFragment extends Fragment {
    private static final String TAG = "MTDeviceInfoFragment";

    private String deviceAddress;
    private String deviceName;

    private LinearLayout infoContainer;
    private ProgressBar progressBar;
    private TextView statusText;
    private TextView progressText;
    private MaterialButton saveButton;
    private MaterialButton dataButton;

    private MTDeviceHandler mtDeviceHandler;

    // Параметры для выбора
    private String[] availableUnits;
    private String[] availableRanges;
    private int selectedUnitsIndex = -1;
    private int selectedRangeIndex = -1;
    private int currentUnitsIndex = -1;
    private int currentRangeIndex = -1;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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
        View view = inflater.inflate(R.layout.fragment_mt_device_info, container, false);

        infoContainer = view.findViewById(R.id.info_container);
        progressBar = view.findViewById(R.id.progress_bar);
        statusText = view.findViewById(R.id.status_text);
        progressText = view.findViewById(R.id.progress_text);
        saveButton = view.findViewById(R.id.save_button);
        dataButton = view.findViewById(R.id.data_button);

        saveButton.setOnClickListener(v -> saveSettings());
        dataButton.setOnClickListener(v -> requestData());

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        connectAndGetInfo();
    }

    private void connectAndGetInfo() {
        showProgress(true);
        statusText.setText("Подключение к устройству...");
        infoContainer.removeAllViews();
        dataButton.setEnabled(false);
        saveButton.setEnabled(false);

        // ВАЖНО: Сбрасываем выбранные индексы при новом подключении
        selectedUnitsIndex = -1;
        selectedRangeIndex = -1;
        currentUnitsIndex = -1;
        currentRangeIndex = -1;

        mtDeviceHandler = new MTDeviceHandler(requireContext(), new MTDeviceHandler.MTDeviceCallback() {
            @Override
            public void onConnectionStateChanged(boolean connected) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    if (connected) {
                        statusText.setText("Получение информации...");
                    } else {
                        statusText.setText("Отключено");
                        showProgress(false);
                    }
                });
            }

            @Override
            public void onDeviceInfoReady(Map<String, String> deviceInfo) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    showProgress(false);
                    statusText.setText("Информация получена");
                    displayDeviceInfo(deviceInfo);
                    dataButton.setEnabled(true);

                    // Отключаемся после получения информации для экономии батареи
                    new android.os.Handler().postDelayed(() -> {
                        if (mtDeviceHandler != null) {
                            mtDeviceHandler.disconnect();
                        }
                    }, 500);
                });
            }


            @Override
            public void onCommandResponse(String command, String response) {
                // Можно показывать промежуточные результаты
            }

            @Override
            public void onError(String error) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    Toast.makeText(getContext(), "Ошибка: " + error, Toast.LENGTH_SHORT).show();
                    statusText.setText("Ошибка: " + error);
                    showProgress(false);
                });
            }

            @Override
            public void onProgress(int current, int total) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    progressText.setText(String.format("Обработка: %d/%d", current, total));
                    progressBar.setMax(total);
                    progressBar.setProgress(current);
                });
            }
        });

        mtDeviceHandler.connect(deviceAddress);
    }

    private void displayDeviceInfo(Map<String, String> deviceInfo) {
        infoContainer.removeAllViews();

        // DataSize
        String dataSize = deviceInfo.get("DataSize?");
        if (dataSize != null && !dataSize.equals("TIMEOUT")) {
            addInfoCard("Заполнено памяти",
                    formatDataSize(dataSize),
                    "Количество записанных измерений");
        }

        // WorkTime
        String workTime = deviceInfo.get("WorkTime?");
        if (workTime != null && !workTime.equals("TIMEOUT")) {
            addInfoCard("Время работы",
                    formatWorkTime(workTime),
                    "Время с момента включения");
        }

        // Idn
        String idn = deviceInfo.get("Idn?");
        if (idn != null && !idn.equals("TIMEOUT")) {
            addInfoCard("Серийный номер", idn, "Модель устройства");
        }

        // PmaxAllTime
        String pmaxAllTime = deviceInfo.get("PmaxAllTime?");
        if (pmaxAllTime != null && !pmaxAllTime.equals("TIMEOUT")) {
            addInfoCard("Максимальное давление",
                    formatPressure(pmaxAllTime),
                    "Максимум за все время");
        }

        // Pminmax24
        String pminmax24 = deviceInfo.get("Pminmax24?");
        if (pminmax24 != null && !pminmax24.equals("TIMEOUT")) {
            addInfoCard("Давление за 24 часа",
                    formatMinMax24(pminmax24),
                    "Мин/макс значения");
        }

        // UnitsAll и Units
        String unitsAll = deviceInfo.get("UnitsAll?");
        String currentUnits = deviceInfo.get("Units?");
        if (unitsAll != null && !unitsAll.equals("TIMEOUT")) {
            parseAndDisplayUnits(unitsAll, currentUnits);
        }

        // RangesAll и Ranges
        String rangesAll = deviceInfo.get("RangesAll?");
        String currentRanges = deviceInfo.get("Ranges?");
        if (rangesAll != null && !rangesAll.equals("TIMEOUT")) {
            parseAndDisplayRanges(rangesAll, currentRanges);
        }
    }

    private void parseAndDisplayUnits(String unitsAll, String currentUnits) {
        Log.d(TAG, "=== parseAndDisplayUnits ===");
        Log.d(TAG, "unitsAll: " + unitsAll);
        Log.d(TAG, "currentUnits: " + currentUnits);

        // Парсим единицы, убирая "UnitsAll"
        java.util.List<String> unitsList = new java.util.ArrayList<>();
        String[] parts = unitsAll.trim().split("\\s+");

        for (String part : parts) {
            part = part.trim();
            if (!part.isEmpty() && !part.equals("UnitsAll")) {
                unitsList.add(part);
            }
        }

        availableUnits = unitsList.toArray(new String[0]);
        Log.d(TAG, "Available units: " + java.util.Arrays.toString(availableUnits));

        // Парсим текущий индекс
        if (currentUnits != null && !currentUnits.equals("TIMEOUT")) {
            String[] currentParts = currentUnits.trim().split("\\s+");
            for (String part : currentParts) {
                try {
                    int index = Integer.parseInt(part.trim());
                    if (index >= 0 && index < availableUnits.length) {
                        currentUnitsIndex = index;
                        selectedUnitsIndex = index; // Инициализируем выбранный индекс
                        Log.d(TAG, "✓ Set units index to: " + index + " (" + availableUnits[index] + ")");
                        break;
                    }
                } catch (NumberFormatException e) {
                    // Не число, пропускаем
                }
            }
        }

        addUnitsSelectionCard();
    }

    private void parseAndDisplayRanges(String rangesAll, String currentRanges) {
        Log.d(TAG, "=== parseAndDisplayRanges ===");
        Log.d(TAG, "rangesAll: " + rangesAll);
        Log.d(TAG, "currentRanges: " + currentRanges);

        // Убираем "RangesAll" из начала
        String rangesData = rangesAll.replaceFirst("^RangesAll\\s*", "").trim();
        String[] rangePairs = rangesData.split(",");

        availableRanges = new String[rangePairs.length];
        for (int i = 0; i < rangePairs.length; i++) {
            availableRanges[i] = rangePairs[i].trim();
        }
        Log.d(TAG, "Available ranges: " + java.util.Arrays.toString(availableRanges));

        // Парсим текущий индекс
        if (currentRanges != null && !currentRanges.equals("TIMEOUT")) {
            String[] currentParts = currentRanges.trim().split("\\s+");
            for (String part : currentParts) {
                try {
                    int index = Integer.parseInt(part.trim());
                    if (index >= 0 && index < availableRanges.length) {
                        currentRangeIndex = index;
                        selectedRangeIndex = index; // Инициализируем выбранный индекс
                        Log.d(TAG, "✓ Set range index to: " + index + " (" + availableRanges[index] + ")");
                        break;
                    }
                } catch (NumberFormatException e) {
                    // Не число, пропускаем
                }
            }
        }

        addRangesSelectionCard();
    }

    private void addUnitsSelectionCard() {
        View cardView = LayoutInflater.from(getContext())
                .inflate(R.layout.item_mt_selection_card, infoContainer, false);

        TextView titleText = cardView.findViewById(R.id.selection_title);
        TextView currentText = cardView.findViewById(R.id.current_value_text);
        LinearLayout optionsContainer = cardView.findViewById(R.id.options_container);

        titleText.setText("Единицы измерения");

        if (availableUnits != null && availableUnits.length > 0) {
            if (currentUnitsIndex >= 0 && currentUnitsIndex < availableUnits.length) {
                currentText.setText("Текущие: " + availableUnits[currentUnitsIndex]);
            } else {
                currentText.setText("Текущие: не определены");
            }

            // Создаем кнопки
            for (int i = 0; i < availableUnits.length; i++) {
                final int index = i;
                MaterialButton button = new MaterialButton(getContext());
                button.setText(availableUnits[i]);
                button.setTextSize(16);

                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                params.setMargins(0, 0, 0, 12);
                button.setLayoutParams(params);

                updateButtonStyle(button, i == selectedUnitsIndex);

                button.setOnClickListener(v -> {
                    if (selectedUnitsIndex != index) {
                        selectedUnitsIndex = index;
                        updateUnitsButtons(optionsContainer);
                        checkAndEnableSaveButton();
                        Log.d(TAG, "User selected units: " + availableUnits[index] + " (index " + index + ")");
                    }
                });

                optionsContainer.addView(button);
            }
        } else {
            currentText.setText("Единицы не загружены");
        }

        infoContainer.addView(cardView);
    }

    private void addRangesSelectionCard() {
        View cardView = LayoutInflater.from(getContext())
                .inflate(R.layout.item_mt_selection_card, infoContainer, false);

        TextView titleText = cardView.findViewById(R.id.selection_title);
        TextView currentText = cardView.findViewById(R.id.current_value_text);
        LinearLayout optionsContainer = cardView.findViewById(R.id.options_container);

        titleText.setText("Диапазоны измерений");

        if (availableRanges != null && availableRanges.length > 0) {
            if (currentRangeIndex >= 0 && currentRangeIndex < availableRanges.length) {
                currentText.setText("Текущий: [" + availableRanges[currentRangeIndex] + "]");
            } else {
                currentText.setText("Текущий: не определен");
            }

            // Создаем кнопки
            for (int i = 0; i < availableRanges.length; i++) {
                final int index = i;
                MaterialButton button = new MaterialButton(getContext());
                button.setText("[" + availableRanges[i] + "]");
                button.setTextSize(16);

                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                params.setMargins(0, 0, 0, 12);
                button.setLayoutParams(params);

                updateButtonStyle(button, i == selectedRangeIndex);

                button.setOnClickListener(v -> {
                    if (selectedRangeIndex != index) {
                        selectedRangeIndex = index;
                        updateRangesButtons(optionsContainer);
                        checkAndEnableSaveButton();
                        Log.d(TAG, "User selected range: " + availableRanges[index] + " (index " + index + ")");
                    }
                });

                optionsContainer.addView(button);
            }
        } else {
            currentText.setText("Диапазоны не загружены");
        }

        infoContainer.addView(cardView);
    }

    private void checkAndEnableSaveButton() {
        // Включаем кнопку "Сохранить" только если что-то изменилось
        boolean hasChanges = (selectedUnitsIndex != currentUnitsIndex) ||
                (selectedRangeIndex != currentRangeIndex);
        saveButton.setEnabled(hasChanges);
    }

    private void updateButtonStyle(MaterialButton button, boolean isSelected) {
        if (isSelected) {
            button.setBackgroundColor(getResources().getColor(R.color.md_theme_primary));
            button.setTextColor(getResources().getColor(R.color.white));
        } else {
            button.setBackgroundColor(getResources().getColor(R.color.md_theme_surfaceVariant));
            button.setTextColor(getResources().getColor(R.color.black));
        }
    }

    private void updateUnitsButtons(LinearLayout container) {
        for (int i = 0; i < container.getChildCount(); i++) {
            MaterialButton button = (MaterialButton) container.getChildAt(i);
            updateButtonStyle(button, i == selectedUnitsIndex);
        }
    }

    private void updateRangesButtons(LinearLayout container) {
        for (int i = 0; i < container.getChildCount(); i++) {
            MaterialButton button = (MaterialButton) container.getChildAt(i);
            updateButtonStyle(button, i == selectedRangeIndex);
        }
    }

    private void saveSettings() {
        if (mtDeviceHandler == null) {
            Toast.makeText(getContext(), "Обработчик не инициализирован", Toast.LENGTH_SHORT).show();
            return;
        }

        // Проверяем, есть ли изменения
        boolean hasChanges = false;
        StringBuilder message = new StringBuilder("Применяю настройки:\n");

        int unitsToApply = -1;
        int rangeToApply = -1;

        if (selectedUnitsIndex != -1 && selectedUnitsIndex != currentUnitsIndex) {
            unitsToApply = selectedUnitsIndex;
            hasChanges = true;
            message.append("• Единицы: индекс ").append(selectedUnitsIndex);
            if (availableUnits != null && selectedUnitsIndex < availableUnits.length) {
                message.append(" (").append(availableUnits[selectedUnitsIndex]).append(")");
            }
            message.append("\n");
        }

        if (selectedRangeIndex != -1 && selectedRangeIndex != currentRangeIndex) {
            rangeToApply = selectedRangeIndex;
            hasChanges = true;
            message.append("• Диапазон: индекс ").append(selectedRangeIndex);
            if (availableRanges != null && selectedRangeIndex < availableRanges.length) {
                message.append(" (").append(availableRanges[selectedRangeIndex]).append(")");
            }
            message.append("\n");
        }

        if (!hasChanges) {
            Toast.makeText(getContext(), "Нет изменений", Toast.LENGTH_SHORT).show();
            return;
        }

        // Показываем прогресс
        statusText.setText("Шаг 1/4: Подключение...");
        showProgress(true);
        saveButton.setEnabled(false);
        dataButton.setEnabled(false);

        // Запускаем процесс отправки настроек
        startSettingsUpdateProcess(unitsToApply, rangeToApply);
    }

    private void startSettingsUpdateProcess(final int unitsIndex, final int rangeIndex) {
        Log.d(TAG, "=== STARTING SETTINGS UPDATE PROCESS ===");
        Log.d(TAG, "Units: " + unitsIndex + ", Range: " + rangeIndex);

        // ШАГ 1: Отключаемся (если подключены)
        if (mtDeviceHandler != null) {
            mtDeviceHandler.disconnect();
        }

        // ШАГ 2: Подключаемся заново
        new android.os.Handler().postDelayed(() -> {
            Log.d(TAG, "Step 1: Connecting to device...");
            statusText.setText("Шаг 1/4: Подключение...");

            connectForCommandsOnly(unitsIndex, rangeIndex);
        }, 500);
    }

    private void connectForCommandsOnly(final int unitsIndex, final int rangeIndex) {
        mtDeviceHandler = new MTDeviceHandler(requireContext(), new MTDeviceHandler.MTDeviceCallback() {
            @Override
            public void onConnectionStateChanged(boolean connected) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    if (connected) {
                        Log.d(TAG, "Step 1 complete: Connected");
                        statusText.setText("Шаг 2/4: Отправка команд...");

                        // ШАГ 3: Ждем секунду и отправляем команды
                        new android.os.Handler().postDelayed(() -> {
                            sendCommands(unitsIndex, rangeIndex);
                        }, 1000);
                    }
                });
            }

            @Override
            public void onDeviceInfoReady(Map<String, String> deviceInfo) {
                // Не используется в этом режиме
            }

            @Override
            public void onCommandResponse(String command, String response) {
                // Не используется
            }

            @Override
            public void onError(String error) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    Log.e(TAG, "Error during settings update: " + error);
                    showProgress(false);
                    statusText.setText("Ошибка: " + error);
                    Toast.makeText(getContext(), "Ошибка: " + error, Toast.LENGTH_LONG).show();
                    saveButton.setEnabled(true);
                });
            }

            @Override
            public void onProgress(int current, int total) {
                // Не используется
            }
        });

        mtDeviceHandler.connect(deviceAddress);
    }

    private void sendCommands(int unitsIndex, int rangeIndex) {
        Log.d(TAG, "Step 2: Sending commands...");

        // КРИТИЧНО: Сначала останавливаем опрос
        if (mtDeviceHandler != null) {
            mtDeviceHandler.stopPolling();
        }

        // Ждем немного, чтобы текущая команда завершилась
        new android.os.Handler().postDelayed(() -> {

            // Отправляем команды с задержками
            if (unitsIndex != -1) {
                Log.d(TAG, "Sending units command: " + unitsIndex);
                mtDeviceHandler.setUnits(unitsIndex);

                // Задержка перед следующей командой
                new android.os.Handler().postDelayed(() -> {
                    if (rangeIndex != -1) {
                        Log.d(TAG, "Sending range command: " + rangeIndex);
                        mtDeviceHandler.setRange(rangeIndex);
                    }

                    statusText.setText("Шаг 2/4: Команды отправлены");

                    // Ждем на применение команд
                    disconnectAndReconnect();
                }, 500); // 500ms между командами

            } else if (rangeIndex != -1) {
                Log.d(TAG, "Sending range command: " + rangeIndex);
                mtDeviceHandler.setRange(rangeIndex);

                statusText.setText("Шаг 2/4: Команды отправлены");

                // Ждем на применение команд
                disconnectAndReconnect();
            }

        }, 500); // 500ms на остановку текущей команды
    }

    private void disconnectAndReconnect() {
        // ШАГ 4: Ждем 2 секунды на применение, затем отключаемся
        new android.os.Handler().postDelayed(() -> {
            Log.d(TAG, "Step 3: Disconnecting...");
            statusText.setText("Шаг 3/4: Отключение...");

            if (mtDeviceHandler != null) {
                mtDeviceHandler.disconnect();
            }

            // ШАГ 5: Ждем секунду и переподключаемся для скачивания данных
            new android.os.Handler().postDelayed(() -> {
                Log.d(TAG, "Step 4: Reconnecting to download updated info...");
                statusText.setText("Шаг 4/4: Получение обновленных данных...");

                // Сбрасываем индексы, чтобы они обновились
                selectedUnitsIndex = -1;
                selectedRangeIndex = -1;
                currentUnitsIndex = -1;
                currentRangeIndex = -1;

                // Подключаемся и скачиваем все данные заново
                connectAndGetInfo();
            }, 1000);

        }, 2000); // 2 секунды на применение команд
    }

    private void addInfoCard(String title, String value, String description) {
        View cardView = LayoutInflater.from(getContext())
                .inflate(R.layout.item_mt_info_card, infoContainer, false);

        TextView titleText = cardView.findViewById(R.id.info_title);
        TextView valueText = cardView.findViewById(R.id.info_value);
        TextView descText = cardView.findViewById(R.id.info_description);

        titleText.setText(title);
        valueText.setText(value);
        descText.setText(description);

        infoContainer.addView(cardView);
    }

    private String formatDataSize(String dataSize) {
        try {
            int size = Integer.parseInt(dataSize.trim());
            return size + " измерений";
        } catch (NumberFormatException e) {
            return dataSize;
        }
    }

    private String formatWorkTime(String workTime) {
        try {
            long seconds = Long.parseLong(workTime.trim());
            long hours = seconds / 3600;
            long minutes = (seconds % 3600) / 60;
            long secs = seconds % 60;

            if (hours > 24) {
                long days = hours / 24;
                hours = hours % 24;
                return String.format("%d д. %02d:%02d:%02d", days, hours, minutes, secs);
            } else {
                return String.format("%02d:%02d:%02d", hours, minutes, secs);
            }
        } catch (NumberFormatException e) {
            return workTime;
        }
    }

    private String formatPressure(String pressure) {
        try {
            double value = Double.parseDouble(pressure.trim());
            return String.format("%.2f Па", value);
        } catch (NumberFormatException e) {
            return pressure;
        }
    }

    private String formatMinMax24(String minmax) {
        try {
            String[] values = minmax.trim().split("[\\s,]+");
            if (values.length >= 2) {
                double min = Double.parseDouble(values[0]);
                double max = Double.parseDouble(values[1]);
                return String.format("Мин: %.2f Па\nМакс: %.2f Па", min, max);
            } else {
                return minmax;
            }
        } catch (Exception e) {
            return minmax;
        }
    }

    private void showProgress(boolean show) {
        progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        progressText.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void requestData() {
        // Отключаемся от текущего соединения
        if (mtDeviceHandler != null) {
            mtDeviceHandler.disconnect();
            mtDeviceHandler.cleanup();
        }

        // Устанавливаем флаг автозапуска
        MTDeviceActivity activity = (MTDeviceActivity) getActivity();
        if (activity != null) {
            activity.requestAutoDataDownload();

            // Переключаемся на вкладку "Данные"
            activity.runOnUiThread(() -> {
                androidx.viewpager2.widget.ViewPager2 viewPager = activity.findViewById(R.id.view_pager);
                if (viewPager != null) {
                    viewPager.setCurrentItem(1, true); // Вкладка 1 = Данные
                }
            });
        }

        statusText.setText("Переключение на вкладку данных...");
        showProgress(false);
    }

    public void refreshInfo() {
        connectAndGetInfo();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mtDeviceHandler != null) {
            mtDeviceHandler.cleanup();
        }
    }
}