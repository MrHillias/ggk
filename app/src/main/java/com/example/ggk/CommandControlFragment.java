package com.example.ggk;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.card.MaterialCardView;

import java.util.UUID;

public class CommandControlFragment extends Fragment {
    private static final String TAG = "CommandControlFragment";
    private static final UUID SERVICE_UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    private static final UUID WRITE_UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb"); // UUID для записи

    // Альтернативный UUID для записи, если первый не работает
    // private static final UUID WRITE_UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");

    private String deviceAddress;
    private String deviceName;
    private TextView statusTextView;
    private Button btnCommand1;
    private Button btnCommand2;
    private Button btnCommand3;

    private BluetoothService bluetoothService;
    private boolean isConnected = false;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        DeviceActivity activity = (DeviceActivity) getActivity();
        if (activity != null) {
            deviceAddress = activity.getDeviceAddress();
            deviceName = activity.getDeviceName();
        }

        // Инициализируем BluetoothService для отправки команд
        bluetoothService = new BluetoothService(requireContext());
        bluetoothService.setCallback(bluetoothCallback);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_command_control, container, false);

        statusTextView = view.findViewById(R.id.command_status_text);
        btnCommand1 = view.findViewById(R.id.btn_command_1);
        btnCommand2 = view.findViewById(R.id.btn_command_2);
        btnCommand3 = view.findViewById(R.id.btn_command_3);

        // Устанавливаем имя устройства в заголовок
        TextView deviceNameText = view.findViewById(R.id.device_name_text);
        deviceNameText.setText("Управление: " + deviceName);

        // Обработчики кнопок
        btnCommand1.setOnClickListener(v -> sendCommand("Zero\r"));
        btnCommand2.setOnClickListener(v -> sendCommand("CMD2"));
        btnCommand3.setOnClickListener(v -> sendCommand("CMD3"));

        // Подключаемся к устройству при создании фрагмента
        connectToDevice();

        return view;
    }

    private void connectToDevice() {
        if (bluetoothService != null && deviceAddress != null) {
            statusTextView.setText("Подключение к устройству...");
            statusTextView.setTextColor(getResources().getColor(R.color.bluetooth_scanning));
            // Передаем любой не-null UUID как маркер режима команд
            bluetoothService.connectForCommands(deviceAddress, SERVICE_UUID, SERVICE_UUID);
        }
    }

    private void sendCommand(String command) {
        if (!isConnected) {
            Toast.makeText(getContext(), "Устройство не подключено", Toast.LENGTH_SHORT).show();
            connectToDevice();
            return;
        }

        // Обновляем статус
        statusTextView.setText("Отправка команды: " + command + "...");
        statusTextView.setTextColor(getResources().getColor(R.color.bluetooth_scanning));

        // Отправляем команду через BluetoothService
        if (bluetoothService != null) {
            boolean sent = bluetoothService.sendCommand(command);

            if (sent) {
                Log.d(TAG, "Command sent: " + command);
                statusTextView.postDelayed(() -> {
                    if (command.equals("Zero\r")) {
                        statusTextView.setText("Команда 'Ноль' отправлена");
                    } else {
                        statusTextView.setText("Команда " + command + " отправлена");
                    }
                    statusTextView.setTextColor(getResources().getColor(R.color.bluetooth_connected));
                }, 500);
            } else {
                Log.e(TAG, "Failed to send command: " + command);
                statusTextView.setText("Ошибка отправки команды");
                statusTextView.setTextColor(getResources().getColor(R.color.bluetooth_disconnected));
                Toast.makeText(getContext(), "Ошибка отправки команды", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private final BluetoothService.BluetoothCallback bluetoothCallback = new BluetoothService.BluetoothCallback() {
        @Override
        public void onConnectionStateChange(boolean connected) {
            isConnected = connected;
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (connected) {
                        statusTextView.setText("Готово к отправке команд");
                        statusTextView.setTextColor(getResources().getColor(R.color.bluetooth_connected));
                        enableButtons(true);
                    } else {
                        statusTextView.setText("Отключено от устройства");
                        statusTextView.setTextColor(getResources().getColor(R.color.bluetooth_disconnected));
                        enableButtons(false);
                    }
                });
            }
        }

        @Override
        public void onServicesDiscovered(boolean success) {
            Log.d(TAG, "Services discovered: " + success);
        }

        @Override
        public void onDataReceived(byte[] data, String formattedData) {
            // Не используется для команд
        }

        @Override
        public void onDataBatch(String formattedBatch, long totalBytes, double kbPerSecond) {
            // Не используется для команд
        }

        @Override
        public void onError(String message) {
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    statusTextView.setText("Ошибка: " + message);
                    statusTextView.setTextColor(getResources().getColor(R.color.bluetooth_disconnected));
                });
            }
        }

        @Override
        public void onReconnectAttempt(int attempt, int maxAttempts) {
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    statusTextView.setText("Переподключение... (" + attempt + "/" + maxAttempts + ")");
                });
            }
        }
    };

    private void enableButtons(boolean enabled) {
        btnCommand1.setEnabled(enabled);
        btnCommand2.setEnabled(enabled);
        btnCommand3.setEnabled(enabled);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (bluetoothService != null) {
            bluetoothService.disconnect();
            bluetoothService.close();
        }
    }
}