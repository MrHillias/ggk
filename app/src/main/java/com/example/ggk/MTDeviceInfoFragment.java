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

        boolean hasChanges = false;
        StringBuilder message = new StringBuilder("Применяю настройки:\n");

        // ВАЖНО: НЕ отключаемся от устройства перед отправкой команд!
        // Используем уже установленное соединение

        // Проверяем единицы
        if (selectedUnitsIndex != -1 && selectedUnitsIndex != currentUnitsIndex) {
            Log.d(TAG, "Setting units to index: " + selectedUnitsIndex);
            mtDeviceHandler.setUnits(selectedUnitsIndex);
            message.append("• Единицы: индекс ").append(selectedUnitsIndex);
            if (availableUnits != null && selectedUnitsIndex < availableUnits.length) {
                message.append(" (").append(availableUnits[selectedUnitsIndex]).append(")");
            }
            message.append("\n");
            hasChanges = true;
        }

        // Проверяем диапазон
        if (selectedRangeIndex != -1 && selectedRangeIndex != currentRangeIndex) {
            Log.d(TAG, "Setting range to index: " + selectedRangeIndex);
            mtDeviceHandler.setRange(selectedRangeIndex);
            message.append("• Диапазон: индекс ").append(selectedRangeIndex);
            if (availableRanges != null && selectedRangeIndex < availableRanges.length) {
                message.append(" (").append(availableRanges[selectedRangeIndex]).append(")");
            }
            message.append("\n");
            hasChanges = true;
        }

        if (hasChanges) {
            statusText.setText(message.toString());
            Toast.makeText(getContext(), "Настройки отправлены", Toast.LENGTH_LONG).show();
            saveButton.setEnabled(false);

            // Ждем применения настроек и переподключаемся
            new android.os.Handler().postDelayed(() -> {
                Log.d(TAG, "Disconnecting before refresh...");
                if (mtDeviceHandler != null) {
                    mtDeviceHandler.disconnect();
                }

                // Еще небольшая задержка перед переподключением
                new android.os.Handler().postDelayed(() -> {
                    Log.d(TAG, "Refreshing device info after settings change...");
                    connectAndGetInfo();
                }, 1000);
            }, 2000); // 2 секунды на применение
        } else {
            Toast.makeText(getContext(), "Нет изменений", Toast.LENGTH_SHORT).show();
        }
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
        if (mtDeviceHandler != null) {
            statusText.setText("Запрос данных...");
            mtDeviceHandler.requestData();
        } else {
            Toast.makeText(getContext(), "Сначала подключитесь к устройству", Toast.LENGTH_SHORT).show();
        }
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