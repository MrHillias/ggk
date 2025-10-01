package com.example.ggk;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;

import java.util.Map;

public class MTDeviceInfoFragment extends Fragment {
    private static final String TAG = "MTDeviceInfoFragment";

    private String deviceAddress;
    private String deviceName;

    private LinearLayout infoContainer;
    private ProgressBar progressBar;
    private TextView statusText;
    private TextView progressText;
    private MaterialButton refreshButton;
    private MaterialButton dataButton;

    private MTDeviceHandler mtDeviceHandler;

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
        refreshButton = view.findViewById(R.id.refresh_button);
        dataButton = view.findViewById(R.id.data_button);

        refreshButton.setOnClickListener(v -> connectAndGetInfo());
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
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    // Можно показывать промежуточные результаты
                    addInfoCard(command, response, false);
                });
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
                    progressText.setText(String.format("Обработка команд: %d/%d", current, total));
                    progressBar.setMax(total);
                    progressBar.setProgress(current);
                });
            }
        });

        mtDeviceHandler.connect(deviceAddress);
    }

    private void displayDeviceInfo(Map<String, String> deviceInfo) {
        infoContainer.removeAllViews();

        // Группируем информацию по категориям
        addCategoryHeader("Основная информация");
        addInfoIfExists(deviceInfo, "Idn?", "Идентификатор");
        addInfoIfExists(deviceInfo, "deviceTime", "Время устройства");
        addInfoIfExists(deviceInfo, "DataSize?", "Размер данных");

        addCategoryHeader("Настройки измерений");
        addInfoIfExists(deviceInfo, "Units?", "Единицы измерения");
        addInfoIfExists(deviceInfo, "UnitsAll?", "Доступные единицы");
        addInfoIfExists(deviceInfo, "Range?", "Текущий диапазон");
        addInfoIfExists(deviceInfo, "RangeAll?", "Доступные диапазоны");

        // Специальная обработка для Ranges с кнопкой
        String rangesResponse = deviceInfo.get("Ranges?");
        if (rangesResponse != null && !rangesResponse.equals("TIMEOUT")) {
            addRangesCard(rangesResponse, deviceInfo.get("rangesValue"));
        }

        addCategoryHeader("Частота и фильтрация");
        addInfoIfExists(deviceInfo, "MeasureFreq?", "Частота измерений");
        addInfoIfExists(deviceInfo, "RecordFreq?", "Частота записи");
        addInfoIfExists(deviceInfo, "Filter?", "Настройки фильтра");

        addCategoryHeader("Статистика");
        addInfoIfExists(deviceInfo, "PmaxAllTime?", "Максимальное давление");
        addInfoIfExists(deviceInfo, "Pminmax24?", "Мин/Макс за 24ч");

        addCategoryHeader("Режимы работы");
        addInfoIfExists(deviceInfo, "Broadcast?", "Режим вещания");

        // Показываем неизвестные команды в отдельной секции
        addCategoryHeader("Дополнительная информация");
        for (Map.Entry<String, String> entry : deviceInfo.entrySet()) {
            String command = entry.getKey();
            if (!isKnownCommand(command) && !isInternalKey(command)) {
                addInfoCard(command, entry.getValue(), true);
            }
        }
    }

    private void addRangesCard(String rangesResponse, String rangesValue) {
        View cardView = LayoutInflater.from(getContext())
                .inflate(R.layout.item_device_ranges_card, infoContainer, false);

        TextView titleText = cardView.findViewById(R.id.info_title);
        TextView valueText = cardView.findViewById(R.id.info_value);
        TextView rangesValueText = cardView.findViewById(R.id.ranges_value_text);
        com.google.android.material.button.MaterialButton changeButton =
                cardView.findViewById(R.id.change_range_button);

        titleText.setText("Диапазоны");
        valueText.setText(rangesResponse);

        if (rangesValue != null) {
            rangesValueText.setVisibility(View.VISIBLE);
            rangesValueText.setText("Текущее значение: " + rangesValue);
        } else {
            rangesValueText.setVisibility(View.GONE);
        }

        changeButton.setOnClickListener(v -> {
            MTRangesDialog dialog = MTRangesDialog.newInstance(deviceAddress, rangesResponse);
            dialog.setTargetFragment(this, 0);
            dialog.show(getParentFragmentManager(), "ranges_dialog");
        });

        infoContainer.addView(cardView);
    }

    public void refreshInfo() {
        // Метод для обновления информации после изменения диапазона
        connectAndGetInfo();
    }

    private boolean isKnownCommand(String command) {
        return MTDeviceHandler.getCommandInfo(command) != null;
    }

    private boolean isInternalKey(String key) {
        return key.equals("deviceTime") || key.equals("dataSize") ||
                key.equals("currentUnits") || key.equals("currentRange") ||
                key.equals("measureFrequency") || key.equals("deviceId") ||
                key.equals("rangesValue") || key.equals("ranges");
    }

    private void addInfoIfExists(Map<String, String> deviceInfo, String key, String displayName) {
        String value = deviceInfo.get(key);
        if (value != null && !value.equals("TIMEOUT")) {
            addInfoCard(displayName, formatValue(key, value), false);
        }
    }

    private String formatValue(String key, String value) {
        // Форматируем значения в зависимости от типа команды
        switch (key) {
            case "DataSize?":
                try {
                    int size = Integer.parseInt(value);
                    return size + " измерений";
                } catch (NumberFormatException e) {
                    return value;
                }

            case "MeasureFreq?":
            case "RecordFreq?":
                if (value.matches("\\d+")) {
                    return value + " Гц";
                }
                return value;

            case "Units?":
                return translateUnits(value);

            case "Broadcast?":
                return value.equals("1") || value.equalsIgnoreCase("on") ? "Включен" : "Выключен";

            default:
                return value;
        }
    }

    private String translateUnits(String units) {
        // Переводим единицы измерения если нужно
        switch (units.toUpperCase()) {
            case "PA": return "Паскали";
            case "KPA": return "Килопаскали";
            case "BAR": return "Бары";
            case "PSI": return "PSI";
            case "MMHG": return "мм рт.ст.";
            default: return units;
        }
    }

    private void addCategoryHeader(String title) {
        TextView header = new TextView(getContext());
        header.setText(title);
        header.setTextSize(16);
        header.setTextColor(getResources().getColor(R.color.md_theme_primary));
        header.setPadding(0, 24, 0, 8);
        header.setTypeface(header.getTypeface(), android.graphics.Typeface.BOLD);
        infoContainer.addView(header);
    }

    private void addInfoCard(String title, String value, boolean isUnknown) {
        View cardView = LayoutInflater.from(getContext())
                .inflate(R.layout.item_device_info_card, infoContainer, false);

        TextView titleText = cardView.findViewById(R.id.info_title);
        TextView valueText = cardView.findViewById(R.id.info_value);
        Chip statusChip = cardView.findViewById(R.id.status_chip);

        titleText.setText(title);
        valueText.setText(value);

        if (value.equals("TIMEOUT")) {
            valueText.setText("Нет ответа");
            valueText.setTextColor(getResources().getColor(R.color.bluetooth_disconnected));
            statusChip.setVisibility(View.VISIBLE);
            statusChip.setText("Timeout");
            statusChip.setChipBackgroundColorResource(R.color.bluetooth_disconnected);
        } else if (isUnknown) {
            statusChip.setVisibility(View.VISIBLE);
            statusChip.setText("Новое");
            statusChip.setChipBackgroundColorResource(R.color.md_theme_tertiary);
        } else {
            statusChip.setVisibility(View.GONE);
        }

        infoContainer.addView(cardView);
    }

    private void showProgress(boolean show) {
        progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        progressText.setVisibility(show ? View.VISIBLE : View.GONE);
        refreshButton.setEnabled(!show);
    }

    private void requestData() {
        if (mtDeviceHandler != null) {
            statusText.setText("Запрос данных...");
            mtDeviceHandler.requestData();
        } else {
            Toast.makeText(getContext(), "Сначала подключитесь к устройству", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mtDeviceHandler != null) {
            mtDeviceHandler.cleanup();
        }
    }
}