package com.example.ggk;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@SuppressLint("MissingPermission")
public class AvailableDevicesFragment extends Fragment implements SwipeRefreshLayout.OnRefreshListener {
    private static final String TAG = "AvailableDevicesFragment";

    private BluetoothAdapter bluetoothAdapter;
    private DeviceListAdapter adapter;
    private SwipeRefreshLayout swipeRefreshLayout;
    private RecyclerView recyclerView;
    private View scanningCard;
    private View emptyState;
    private Handler mainHandler;
    private Set<String> foundDevices = new HashSet<>();
    private List<DeviceListAdapter.DeviceItem> deviceList = new ArrayList<>();

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null && !foundDevices.contains(device.getAddress())) {
                    foundDevices.add(device.getAddress());
                    addDeviceToList(device, false);
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
                // Используем только SwipeRefreshLayout для индикации
                swipeRefreshLayout.setRefreshing(true);
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                swipeRefreshLayout.setRefreshing(false);
                updateEmptyState();
            }
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainHandler = new Handler(Looper.getMainLooper());

        BluetoothManager bluetoothManager = (BluetoothManager) requireContext()
                .getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_available_devices, container, false);

        swipeRefreshLayout = view.findViewById(R.id.swipe_refresh_layout);
        recyclerView = view.findViewById(R.id.device_list);
        scanningCard = view.findViewById(R.id.scanning_card);
        emptyState = view.findViewById(R.id.empty_state);

        swipeRefreshLayout.setOnRefreshListener(this);
        swipeRefreshLayout.setColorSchemeResources(
                R.color.md_theme_primary,
                R.color.md_theme_secondary,
                R.color.md_theme_tertiary
        );

        adapter = new DeviceListAdapter(device -> {
            if (bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }

            // Проверяем, есть ли уже сохраненные данные для этого устройства
            checkExistingDataAndProceed(device);
        });

        recyclerView.setAdapter(adapter);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        registerReceivers();
        refreshDeviceList();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }
        try {
            requireContext().unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {}
    }

    private void registerReceivers() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        requireContext().registerReceiver(receiver, filter);
    }

    @Override
    public void onRefresh() {
        refreshDeviceList();
    }

    private void refreshDeviceList() {
        Log.d(TAG, "refreshDeviceList called");

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            swipeRefreshLayout.setRefreshing(false);
            Toast.makeText(getContext(), "Bluetooth не включен", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Bluetooth not enabled");
            return;
        }

        deviceList.clear();
        foundDevices.clear();
        adapter.submitList(new ArrayList<>());

        // Показываем сопряженные устройства
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        Log.d(TAG, "Paired devices: " + pairedDevices.size());
        for (BluetoothDevice device : pairedDevices) {
            addDeviceToList(device, true);
        }

        // Начинаем поиск новых устройств
        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }

        boolean started = bluetoothAdapter.startDiscovery();
        Log.d(TAG, "Discovery started: " + started);
    }

    private void addDeviceToList(BluetoothDevice device, boolean isPaired) {
        Log.d(TAG, "Adding device: " + device.getAddress() + " paired: " + isPaired);

        DeviceListAdapter.DeviceItem item = new DeviceListAdapter.DeviceItem(device, isPaired);
        deviceList.add(item);

        mainHandler.post(() -> {
            adapter.submitList(new ArrayList<>(deviceList));
            updateEmptyState();
            Log.d(TAG, "List updated, size: " + deviceList.size());
        });
    }

    private void updateEmptyState() {
        emptyState.setVisibility(deviceList.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void checkExistingDataAndProceed(DeviceListAdapter.DeviceItem device) {
        // Проверяем все папки в директории приложения
        File appDir = requireContext().getFilesDir();
        File[] files = appDir.listFiles();

        String existingFolderName = null;
        long lastSyncTime = 0;

        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    // Проверяем по MAC-адресу
                    String savedAddress = DeviceInfoHelper.getDeviceAddress(requireContext(), file.getName());
                    if (savedAddress != null && savedAddress.equalsIgnoreCase(device.getAddress())) {
                        existingFolderName = file.getName();
                        // Получаем время последней записи
                        lastSyncTime = getLastDataTime(file);
                        break;
                    }

                    // Также проверяем по имени устройства
                    if (file.getName().equals(device.getName())) {
                        existingFolderName = file.getName();
                        lastSyncTime = getLastDataTime(file);
                        break;
                    }
                }
            }
        }

        if (existingFolderName != null) {
            // Данные уже есть, спрашиваем пользователя
            showDataExistsDialog(device, existingFolderName, lastSyncTime);
        } else {
            // Данных нет, сразу подключаемся
            connectToDevice(device, false, 0);
        }
    }

    private void showDataExistsDialog(DeviceListAdapter.DeviceItem device, String existingFolderName, long lastSyncTime) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Данные уже существуют")
                .setMessage("Для этого устройства уже есть сохраненные данные. Что вы хотите сделать?")
                .setPositiveButton("Скачать только новые", (dialog, which) -> {
                    // Докачка новых данных
                    connectToDevice(device, true, lastSyncTime);
                })
                .setNeutralButton("Скачать все заново", (dialog, which) -> {
                    // Удаляем старые данные и качаем заново
                    deleteExistingData(existingFolderName);
                    connectToDevice(device, false, 0);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void deleteExistingData(String folderName) {
        File deviceFolder = new File(requireContext().getFilesDir(), folderName);
        if (deviceFolder.exists()) {
            File[] files = deviceFolder.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
            deviceFolder.delete();
        }
    }

    private long getLastDataTime(File deviceFolder) {
        File dataFile = new File(deviceFolder, "data.txt");
        if (!dataFile.exists()) {
            return 0;
        }

        String lastLine = null;
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(dataFile, "r")) {
            long fileLength = file.length() - 1;
            StringBuilder sb = new StringBuilder();

            for (long filePointer = fileLength; filePointer != -1; filePointer--) {
                file.seek(filePointer);
                int readByte = file.readByte();

                if (readByte == 0xA) { // LF
                    if (filePointer < fileLength) {
                        lastLine = sb.reverse().toString();
                        break;
                    }
                } else if (readByte != 0xD) { // Ignore CR
                    sb.append((char) readByte);
                }
            }

            if (lastLine == null && sb.length() > 0) {
                lastLine = sb.reverse().toString();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading last line from data file", e);
            return 0;
        }

        if (lastLine != null && lastLine.contains(";")) {
            String[] parts = lastLine.split(";");
            if (parts.length >= 2) {
                try {
                    SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault());
                    Date date = sdf.parse(parts[1].trim());
                    return date != null ? date.getTime() : 0;
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing date from last line", e);
                }
            }
        }

        return 0;
    }

    private void connectToDevice(DeviceListAdapter.DeviceItem device, boolean syncMode, long lastSyncTime) {
        MainActivity mainActivity = (MainActivity) getActivity();
        if (mainActivity != null) {
            if (syncMode) {
                // Режим синхронизации - нужно найти имя папки
                String folderName = findDeviceFolderName(device);
                mainActivity.openDeviceDetailsForSyncWithFolder(
                        device.getAddress(),
                        device.getName(),
                        folderName,
                        lastSyncTime
                );
            } else {
                // Обычное подключение
                mainActivity.openDeviceDetails(
                        device.getAddress(),
                        device.getName(),
                        false
                );
            }
        }
    }

    private String findDeviceFolderName(DeviceListAdapter.DeviceItem device) {
        File appDir = requireContext().getFilesDir();
        File[] files = appDir.listFiles();

        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    // Проверяем по MAC-адресу
                    String savedAddress = DeviceInfoHelper.getDeviceAddress(requireContext(), file.getName());
                    if (savedAddress != null && savedAddress.equalsIgnoreCase(device.getAddress())) {
                        return file.getName();
                    }

                    // Также проверяем по имени устройства
                    if (file.getName().equals(device.getName())) {
                        return file.getName();
                    }
                }
            }
        }

        return device.getName(); // По умолчанию возвращаем имя устройства
    }
}