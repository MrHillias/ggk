package com.example.ggk;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.button.MaterialButton;

public class MTRangesDialog extends DialogFragment {
    private static final String TAG = "MTRangesDialog";

    private String deviceAddress;
    private String currentRange = "0";
    private TextView currentRangeText;
    private TextView rangesListText;
    private MaterialButton changeRangeButton;
    private BluetoothService bluetoothService;
    private Handler mainHandler;

    public static MTRangesDialog newInstance(String deviceAddress, String currentRange) {
        MTRangesDialog dialog = new MTRangesDialog();
        Bundle args = new Bundle();
        args.putString("deviceAddress", deviceAddress);
        args.putString("currentRange", currentRange);
        dialog.setArguments(args);
        return dialog;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainHandler = new Handler(Looper.getMainLooper());
        if (getArguments() != null) {
            deviceAddress = getArguments().getString("deviceAddress");
            currentRange = getArguments().getString("currentRange", "0");
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireActivity());
        LayoutInflater inflater = requireActivity().getLayoutInflater();
        View view = inflater.inflate(R.layout.dialog_mt_ranges, null);

        currentRangeText = view.findViewById(R.id.current_range_text);
        rangesListText = view.findViewById(R.id.ranges_list_text);
        changeRangeButton = view.findViewById(R.id.change_range_button);

        // Парсим текущий диапазон из ответа "Ranges X"
        String rangeValue = extractRangeValue(currentRange);
        currentRangeText.setText("Текущий диапазон: " + rangeValue);
        updateRangesList(rangeValue);

        changeRangeButton.setOnClickListener(v -> changeRange());

        initializeBluetooth();

        builder.setView(view)
                .setTitle("Управление диапазонами")
                .setPositiveButton("Закрыть", (dialog, id) -> {
                    if (bluetoothService != null) {
                        bluetoothService.disconnect();
                    }
                });

        return builder.create();
    }

    private void initializeBluetooth() {
        bluetoothService = new BluetoothService(requireContext());
        bluetoothService.setCallback(new BluetoothService.BluetoothCallback() {
            @Override
            public void onConnectionStateChange(boolean connected) {
                mainHandler.post(() -> changeRangeButton.setEnabled(connected));
            }

            @Override
            public void onServicesDiscovered(boolean success) {}

            @Override
            public void onDataReceived(byte[] data, String formattedData) {
                handleResponse(new String(data));
            }

            @Override
            public void onDataBatch(String formattedBatch, long totalBytes, double kbPerSecond) {}

            @Override
            public void onError(String message) {
                mainHandler.post(() -> {
                    Toast.makeText(getContext(), "Ошибка: " + message, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onReconnectAttempt(int attempt, int maxAttempts) {}
        });

        // Подключаемся для отправки команд
        bluetoothService.connectForCommands(deviceAddress,
                java.util.UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"),
                java.util.UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb"));
    }

    private void changeRange() {
        if (bluetoothService != null && bluetoothService.isConnected()) {
            // Определяем следующий диапазон
            String currentValue = extractRangeValue(currentRange);
            int nextRange = getNextRange(currentValue);

            // Отправляем команду смены диапазона
            String command = "Ranges " + nextRange;
            changeRangeButton.setEnabled(false);

            boolean sent = bluetoothService.sendCommand(command + "\r");
            if (sent) {
                Toast.makeText(getContext(), "Отправлена команда: " + command, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(getContext(), "Ошибка отправки команды", Toast.LENGTH_SHORT).show();
                changeRangeButton.setEnabled(true);
            }
        } else {
            Toast.makeText(getContext(), "Устройство не подключено", Toast.LENGTH_SHORT).show();
        }
    }

    private void handleResponse(String response) {
        mainHandler.post(() -> {
            // Обрабатываем ответ типа "Ranges 5 OK"
            if (response.contains("Ranges") && response.contains("OK")) {
                // Показываем полный ответ в Toast
                Toast.makeText(getContext(), "Ответ устройства: " + response.trim(),
                        Toast.LENGTH_LONG).show();

                // Извлекаем новое значение диапазона
                String newRange = extractRangeFromResponse(response);
                if (newRange != null) {
                    currentRange = "Ranges " + newRange;
                    currentRangeText.setText("Текущий диапазон: " + newRange);
                    updateRangesList(newRange);

                    // Обновляем информацию в родительском фрагменте
                    if (getTargetFragment() instanceof MTDeviceInfoFragment) {
                        ((MTDeviceInfoFragment) getTargetFragment()).refreshInfo();
                    }
                }

                changeRangeButton.setEnabled(true);
            } else if (response.contains("ERROR")) {
                Toast.makeText(getContext(), "Ошибка устройства: " + response,
                        Toast.LENGTH_LONG).show();
                changeRangeButton.setEnabled(true);
            }
        });
    }

    private String extractRangeValue(String rangeResponse) {
        // Извлекаем число из "Ranges X"
        if (rangeResponse != null && rangeResponse.contains("Ranges")) {
            String[] parts = rangeResponse.split("\\s+");
            if (parts.length >= 2) {
                return parts[1];
            }
        }
        return "0";
    }

    private String extractRangeFromResponse(String response) {
        // Извлекаем число из "Ranges X OK"
        String[] parts = response.trim().split("\\s+");
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].equals("Ranges") && i + 1 < parts.length) {
                try {
                    Integer.parseInt(parts[i + 1]);
                    return parts[i + 1];
                } catch (NumberFormatException e) {
                    // Не число, продолжаем поиск
                }
            }
        }
        return null;
    }

    private int getNextRange(String currentValue) {
        // Логика переключения диапазонов
        // 0 -> 1 -> 5 -> 10 -> 50 -> 100 -> 0
        try {
            int current = Integer.parseInt(currentValue);
            switch (current) {
                case 0: return 1;
                case 1: return 5;
                case 5: return 10;
                case 10: return 50;
                case 50: return 100;
                case 100: return 0;
                default: return 1;
            }
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private void updateRangesList(String currentValue) {
        // Показываем список доступных диапазонов
        String rangesList = "Доступные диапазоны:\n" +
                "0 - Автоматический\n" +
                "1 - 0-1 кПа\n" +
                "5 - 0-5 кПа\n" +
                "10 - 0-10 кПа\n" +
                "50 - 0-50 кПа\n" +
                "100 - 0-100 кПа\n\n" +
                "Текущее значение: " + currentValue;
        rangesListText.setText(rangesList);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (bluetoothService != null) {
            bluetoothService.close();
        }
    }
}