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

        // Парсим "UnitsAll KPa MPa" → ["KPa", "MPa"]
        String[] parts = unitsAll.trim().split("\\s+");
        Log.d(TAG, "Split parts: " + java.util.Arrays.toString(parts));

        if (parts.length > 1) {
            availableUnits = new String[parts.length - 1];
            System.arraycopy(parts, 1, availableUnits, 0, parts.length - 1);
            Log.d(TAG, "Available units: " + java.util.Arrays.toString(availableUnits));
        } else {
            availableUnits = new String[0];
        }

        // Парсим "Units 0" → индекс 0
        // Устройство возвращает ИНДЕКС, а не название!
        if (currentUnits != null && !currentUnits.equals("TIMEOUT")) {
            String[] currentParts = currentUnits.trim().split("\\s+");
            Log.d(TAG, "Current parts: " + java.util.Arrays.toString(currentParts));

            if (currentParts.length > 1) {
                try {
                    // Пытаемся распарсить как индекс
                    int index = Integer.parseInt(currentParts[1]);
                    Log.d(TAG, "Parsed index: " + index);

                    if (index >= 0 && index < availableUnits.length) {
                        currentUnitsIndex = index;
                        selectedUnitsIndex = index;
                        Log.d(TAG, "Set currentUnitsIndex to: " + index + " (" + availableUnits[index] + ")");
                    }
                } catch (NumberFormatException e) {
                    Log.e(TAG, "Failed to parse as index, trying as name", e);
                    // Если не число, ищем по названию (старый формат)
                    String currentUnit = currentParts[1];
                    for (int i = 0; i < availableUnits.length; i++) {
                        if (availableUnits[i].equals(currentUnit)) {
                            currentUnitsIndex = i;
                            selectedUnitsIndex = i;
                            Log.d(TAG, "Found by name at index: " + i);
                            break;
                        }
                    }
                }
            }
        }

        Log.d(TAG, "Final currentUnitsIndex: " + currentUnitsIndex);
        Log.d(TAG, "Final selectedUnitsIndex: " + selectedUnitsIndex);
        addUnitsSelectionCard();
    }

    private void parseAndDisplayRanges(String rangesAll, String currentRanges) {
        Log.d(TAG, "=== parseAndDisplayRanges ===");
        Log.d(TAG, "rangesAll: " + rangesAll);
        Log.d(TAG, "currentRanges: " + currentRanges);

        // Парсим "RangesAll 0 1000, 0 1600, 0 2500"
        // Индекс 0 → "0 1000", индекс 1 → "0 1600", индекс 2 → "0 2500"
        String rangesData = rangesAll.replaceFirst("RangesAll\\s*", "").trim();
        Log.d(TAG, "Ranges data after cleanup: " + rangesData);

        String[] rangePairs = rangesData.split(",");
        Log.d(TAG, "Split range pairs: " + java.util.Arrays.toString(rangePairs));

        availableRanges = new String[rangePairs.length];

        for (int i = 0; i < rangePairs.length; i++) {
            availableRanges[i] = rangePairs[i].trim();
        }
        Log.d(TAG, "Available ranges: " + java.util.Arrays.toString(availableRanges));

        // Парсим "Ranges 0" → индекс 0
        if (currentRanges != null && !currentRanges.equals("TIMEOUT")) {
            String[] currentParts = currentRanges.trim().split("\\s+");
            Log.d(TAG, "Current range parts: " + java.util.Arrays.toString(currentParts));

            if (currentParts.length > 1) {
                try {
                    currentRangeIndex = Integer.parseInt(currentParts[1]);
                    selectedRangeIndex = currentRangeIndex;
                    Log.d(TAG, "Set currentRangeIndex to: " + currentRangeIndex + " (" + availableRanges[currentRangeIndex] + ")");
                } catch (NumberFormatException e) {
                    Log.e(TAG, "Error parsing range index", e);
                }
            }
        }

        Log.d(TAG, "Final currentRangeIndex: " + currentRangeIndex);
        Log.d(TAG, "Final selectedRangeIndex: " + selectedRangeIndex);
        addRangesSelectionCard();
    }

    private void addUnitsSelectionCard() {
        View cardView = LayoutInflater.from(getContext())
                .inflate(R.layout.item_mt_selection_card, infoContainer, false);

        TextView titleText = cardView.findViewById(R.id.selection_title);
        TextView currentText = cardView.findViewById(R.id.current_value_text);
        LinearLayout optionsContainer = cardView.findViewById(R.id.options_container);

        titleText.setText("Единицы измерения");

        if (currentUnitsIndex >= 0 && currentUnitsIndex < availableUnits.length) {
            currentText.setText("Текущие: " + availableUnits[currentUnitsIndex] + " (индекс " + currentUnitsIndex + ")");
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
                selectedUnitsIndex = index;
                updateUnitsButtons(optionsContainer);
                saveButton.setEnabled(true);
            });

            optionsContainer.addView(button);
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

        if (currentRangeIndex >= 0 && currentRangeIndex < availableRanges.length) {
            currentText.setText("Текущий: [" + availableRanges[currentRangeIndex] + "] (индекс " + currentRangeIndex + ")");
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
                selectedRangeIndex = index;
                updateRangesButtons(optionsContainer);
                saveButton.setEnabled(true);
            });

            optionsContainer.addView(button);
        }

        infoContainer.addView(cardView);
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
        if (mtDeviceHandler != null) {
            boolean hasChanges = false;
            StringBuilder message = new StringBuilder("Применяю настройки:\n");

            // Проверяем единицы
            if (selectedUnitsIndex != -1 && selectedUnitsIndex != currentUnitsIndex) {
                mtDeviceHandler.setUnits(selectedUnitsIndex);
                message.append("• Единицы: ").append(availableUnits[selectedUnitsIndex]).append("\n");
                hasChanges = true;
            }

            // Проверяем диапазон
            if (selectedRangeIndex != -1 && selectedRangeIndex != currentRangeIndex) {
                mtDeviceHandler.setRange(selectedRangeIndex);
                message.append("• Диапазон: ").append(availableRanges[selectedRangeIndex]).append("\n");
                hasChanges = true;
            }

            if (hasChanges) {
                statusText.setText(message.toString());
                Toast.makeText(getContext(), "Настройки отправлены", Toast.LENGTH_SHORT).show();
                saveButton.setEnabled(false);

                // Обновляем через 2 секунды
                new android.os.Handler().postDelayed(() -> connectAndGetInfo(), 2000);
            } else {
                Toast.makeText(getContext(), "Нет изменений для применения", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(getContext(), "Сначала подключитесь к устройству", Toast.LENGTH_SHORT).show();
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
        saveButton.setEnabled(!show);
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