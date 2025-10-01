package com.example.ggk;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.UUID;

public class MTSettingsFragment extends Fragment {
    private static final String TAG = "MTSettingsFragment";
    private static final UUID SERVICE_UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    private static final UUID WRITE_UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb");

    private String deviceAddress;
    private String deviceName;

    private TextInputLayout unitsLayout;
    private AutoCompleteTextView unitsDropdown;
    private TextInputLayout rangeLayout;
    private AutoCompleteTextView rangeDropdown;
    private TextInputEditText measureFreqInput;
    private TextInputEditText recordFreqInput;
    private MaterialButton applyButton;
    private MaterialButton resetButton;

    private BluetoothService bluetoothService;

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
        View view = inflater.inflate(R.layout.fragment_mt_settings, container, false);

        unitsLayout = view.findViewById(R.id.units_layout);
        unitsDropdown = view.findViewById(R.id.units_dropdown);
        rangeLayout = view.findViewById(R.id.range_layout);
        rangeDropdown = view.findViewById(R.id.range_dropdown);
        measureFreqInput = view.findViewById(R.id.measure_freq_input);
        recordFreqInput = view.findViewById(R.id.record_freq_input);
        applyButton = view.findViewById(R.id.apply_button);
        resetButton = view.findViewById(R.id.reset_button);

        setupDropdowns();
        setupButtons();
        initializeBluetooth();

        return view;
    }

    private void setupDropdowns() {
        // Единицы измерения
        String[] units = {"Pa", "kPa", "Bar", "mBar", "PSI", "mmHg", "MPa", "kgf/cm²"};
        ArrayAdapter<String> unitsAdapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                units
        );
        unitsDropdown.setAdapter(unitsAdapter);

        // Диапазоны
        String[] ranges = {"0-1 kPa", "0-10 kPa", "0-100 kPa", "0-1000 kPa", "Auto"};
        ArrayAdapter<String> rangeAdapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                ranges
        );
        rangeDropdown.setAdapter(rangeAdapter);
    }

    private void setupButtons() {
        applyButton.setOnClickListener(v -> applySettings());
        resetButton.setOnClickListener(v -> resetToDefaults());
    }

    private void initializeBluetooth() {
        bluetoothService = new BluetoothService(requireContext());
        bluetoothService.setCallback(new BluetoothService.BluetoothCallback() {
            @Override
            public void onConnectionStateChange(boolean connected) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        applyButton.setEnabled(connected);
                        resetButton.setEnabled(connected);
                    });
                }
            }

            @Override
            public void onServicesDiscovered(boolean success) {
                // Не используется
            }

            @Override
            public void onDataReceived(byte[] data, String formattedData) {
                // Обработка ответов на команды настройки
                handleCommandResponse(formattedData);
            }

            @Override
            public void onDataBatch(String formattedBatch, long totalBytes, double kbPerSecond) {
                // Не используется
            }

            @Override
            public void onError(String message) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        Toast.makeText(getContext(), "Ошибка: " + message, Toast.LENGTH_SHORT).show();
                    });
                }
            }

            @Override
            public void onReconnectAttempt(int attempt, int maxAttempts) {
                // Не используется
            }
        });
    }

    private void applySettings() {
        if (bluetoothService == null || !bluetoothService.isConnected()) {
            // Подключаемся
            bluetoothService.connectForCommands(deviceAddress, SERVICE_UUID, WRITE_UUID);

            // Ждем подключения
            new android.os.Handler().postDelayed(this::sendSettings, 2000);
        } else {
            sendSettings();
        }
    }

    private void sendSettings() {
        boolean anyCommandSent = false;

        // Единицы измерения
        String units = unitsDropdown.getText().toString();
        if (!units.isEmpty()) {
            bluetoothService.sendCommand("Units=" + units + "\r");
            anyCommandSent = true;
        }

        // Диапазон
        String range = rangeDropdown.getText().toString();
        if (!range.isEmpty()) {
            // Извлекаем числовое значение из диапазона
            String rangeValue = range.replaceAll("[^0-9]", "");
            if (!rangeValue.isEmpty()) {
                bluetoothService.sendCommand("Range=" + rangeValue + "\r");
                anyCommandSent = true;
            }
        }

        // Частота измерений
        String measureFreq = measureFreqInput.getText().toString();
        if (!measureFreq.isEmpty()) {
            bluetoothService.sendCommand("MeasureFreq=" + measureFreq + "\r");
            anyCommandSent = true;
        }

        // Частота записи
        String recordFreq = recordFreqInput.getText().toString();
        if (!recordFreq.isEmpty()) {
            bluetoothService.sendCommand("RecordFreq=" + recordFreq + "\r");
            anyCommandSent = true;
        }

        if (anyCommandSent) {
            Toast.makeText(getContext(), "Настройки отправлены", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(getContext(), "Заполните хотя бы одно поле", Toast.LENGTH_SHORT).show();
        }
    }

    private void resetToDefaults() {
        // Отправляем команду сброса
        if (bluetoothService != null && bluetoothService.isConnected()) {
            bluetoothService.sendCommand("Reset\r");
            Toast.makeText(getContext(), "Команда сброса отправлена", Toast.LENGTH_SHORT).show();

            // Очищаем поля
            clearFields();
        } else {
            Toast.makeText(getContext(), "Устройство не подключено", Toast.LENGTH_SHORT).show();
        }
    }

    private void clearFields() {
        unitsDropdown.setText("");
        rangeDropdown.setText("");
        measureFreqInput.setText("");
        recordFreqInput.setText("");
    }

    private void handleCommandResponse(String response) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                if (response.contains("OK") || response.contains("ok")) {
                    Toast.makeText(getContext(), "Команда выполнена успешно", Toast.LENGTH_SHORT).show();
                } else if (response.contains("ERROR") || response.contains("error")) {
                    Toast.makeText(getContext(), "Ошибка выполнения команды", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // Подключаемся при открытии вкладки
        if (bluetoothService != null) {
            bluetoothService.connectForCommands(deviceAddress, SERVICE_UUID, WRITE_UUID);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        // Отключаемся при уходе с вкладки
        if (bluetoothService != null) {
            bluetoothService.disconnect();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (bluetoothService != null) {
            bluetoothService.close();
        }
    }
}