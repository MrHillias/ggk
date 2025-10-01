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
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

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
                // Можно показывать промежуточные результаты, но пока пропускаем
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

        // DataSize - Заполнено памяти
        String dataSize = deviceInfo.get("DataSize?");
        if (dataSize != null && !dataSize.equals("TIMEOUT")) {
            addInfoCard("Заполнено памяти",
                    formatDataSize(dataSize),
                    "Количество записанных измерений с момента включения");
        }

        // WorkTime - Время работы
        String workTime = deviceInfo.get("WorkTime?");
        if (workTime != null && !workTime.equals("TIMEOUT")) {
            addInfoCard("Время работы",
                    formatWorkTime(workTime),
                    "Время с момента включения прибора");
        }

        // Idn - Серийный номер
        String idn = deviceInfo.get("Idn?");
        if (idn != null && !idn.equals("TIMEOUT")) {
            addInfoCard("Серийный номер",
                    idn,
                    "Модель и идентификатор устройства");
        }

        // PmaxAllTime - Максимальное давление
        String pmaxAllTime = deviceInfo.get("PmaxAllTime?");
        if (pmaxAllTime != null && !pmaxAllTime.equals("TIMEOUT")) {
            addInfoCard("Максимальное давление",
                    formatPressure(pmaxAllTime),
                    "Максимальное значение за все время работы");
        }

        // Pminmax24 - Мин/Макс за 24 часа
        String pminmax24 = deviceInfo.get("Pminmax24?");
        if (pminmax24 != null && !pminmax24.equals("TIMEOUT")) {
            addInfoCard("Давление за 24 часа",
                    formatMinMax24(pminmax24),
                    "Минимальное и максимальное значения");
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
            // Предполагаем, что время в секундах
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
            // Предполагаем формат: "min max" или "min,max"
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