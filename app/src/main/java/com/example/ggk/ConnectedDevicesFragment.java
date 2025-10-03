package com.example.ggk;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
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
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@SuppressLint("MissingPermission")
public class ConnectedDevicesFragment extends Fragment {
    private static final String TAG = "ConnectedDevicesFragment";
    private static final long SCAN_INTERVAL = 10000; // Сканирование каждые 10 секунд
    private static final long SCAN_DURATION = 5000; // Длительность сканирования 5 секунд

    private RecyclerView recyclerView;
    private View emptyView;
    private EditText searchEditText;
    private ConnectedDevicesAdapter adapter;
    private List<DeviceInfo> allDevices;
    private List<DeviceInfo> filteredDevices;
    private String searchQuery = "";

    // Элементы для индикации сканирования
    private View scanIndicatorContainer;
    private ProgressBar scanProgress;
    private ImageView scanCompleteIcon;

    private BluetoothAdapter bluetoothAdapter;
    private Handler scanHandler;
    private boolean isScanning = false;
    private Set<String> availableDeviceAddresses = new HashSet<>();
    private Map<String, String> deviceNameMap = new HashMap<>(); // MAC адрес -> Имя устройства

    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) {
                    String address = device.getAddress();
                    String name = device.getName();

                    Log.d(TAG, "Found device: " + name + " (" + address + ")");

                    // Добавляем в список доступных устройств
                    availableDeviceAddresses.add(address);

                    // Сохраняем имя устройства
                    if (name != null && !name.isEmpty()) {
                        deviceNameMap.put(address, name);
                    }

                    // Логируем для отладки
                    Log.d(TAG, "Available devices count: " + availableDeviceAddresses.size());
                    for (DeviceInfo savedDevice : allDevices) {
                        String normalizedSaved = normalizeMacAddress(savedDevice.address);
                        String normalizedFound = normalizeMacAddress(address);
                        if (normalizedSaved.equals(normalizedFound)) {
                            Log.d(TAG, "MATCH FOUND! Device: " + savedDevice.getDisplayName() +
                                    " Saved MAC: " + savedDevice.address + " Found MAC: " + address);
                        }
                    }

                    // Обновляем UI
                    updateDeviceAvailability();
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
                isScanning = true;
                showScanIndicator(true);
                Log.d(TAG, "Scan started");
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                isScanning = false;
                showScanIndicator(false);
                Log.d(TAG, "Scan finished. Found devices: " + availableDeviceAddresses.size());

                // Выводим все найденные устройства
                for (String mac : availableDeviceAddresses) {
                    Log.d(TAG, "Available device MAC: " + mac);
                }

                // Выводим все сохраненные устройства
                for (DeviceInfo device : allDevices) {
                    Log.d(TAG, "Saved device: " + device.getDisplayName() +
                            " MAC: " + device.address + " Available: " + device.isAvailable);
                }
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_connected_devices, container, false);

        recyclerView = view.findViewById(R.id.devices_recycler_view);
        emptyView = view.findViewById(R.id.empty_view);
        searchEditText = view.findViewById(R.id.search_edit_text);

        // Инициализация элементов индикации сканирования
        scanIndicatorContainer = view.findViewById(R.id.scan_indicator_container);
        scanProgress = view.findViewById(R.id.scan_progress);
        scanCompleteIcon = view.findViewById(R.id.scan_complete_icon);

        allDevices = new ArrayList<>();
        filteredDevices = new ArrayList<>();
        scanHandler = new Handler(Looper.getMainLooper());

        // Получаем Bluetooth адаптер
        BluetoothManager bluetoothManager = (BluetoothManager) requireContext()
                .getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();

        adapter = new ConnectedDevicesAdapter(
                new ConnectedDevicesAdapter.OnDeviceClickListener() {
                    @Override
                    public void onDeviceClick(DeviceInfo device) {
                        MainActivity mainActivity = (MainActivity) getActivity();
                        if (mainActivity != null) {
                            // Проверяем, является ли это MT-устройством
                            boolean isMTDevice = MTDeviceDataHelper.isMTDevice(
                                    requireContext(),
                                    device.folderName
                            );

                            if (isMTDevice) {
                                // Для MT-устройств открываем только график через DeviceActivity
                                mainActivity.openDeviceDetailsWithFolder(
                                        device.address,
                                        device.getDisplayName(),
                                        device.folderName,
                                        true  // isFromHistory = true
                                );
                            } else {
                                // Для обычных устройств - стандартная логика
                                mainActivity.openDeviceDetailsWithFolder(
                                        device.address,
                                        device.getDisplayName(),
                                        device.folderName,
                                        true
                                );
                            }
                        }
                    }

                    @Override
                    public void onDeviceOptionsClick(DeviceInfo device, View anchor) {
                        showDeviceOptionsMenu(device, anchor);
                    }
                }
        );

        recyclerView.setAdapter(adapter);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                searchQuery = s.toString().toLowerCase().trim();
                filterDevices();
            }
        });

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();

        // Регистрируем приемник для сканирования
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        requireContext().registerReceiver(scanReceiver, filter);

        refreshDeviceList();

        // Начинаем периодическое сканирование
        startPeriodicScanning();

        // Сразу запускаем сканирование для обновления статусов
        startBluetoothScan();
    }

    @Override
    public void onPause() {
        super.onPause();

        // Останавливаем сканирование
        stopPeriodicScanning();

        // Отменяем регистрацию приемника
        try {
            requireContext().unregisterReceiver(scanReceiver);
        } catch (IllegalArgumentException ignored) {}
    }

    private void showScanIndicator(boolean scanning) {
        if (scanIndicatorContainer != null) {
            scanIndicatorContainer.setVisibility(View.VISIBLE);

            if (scanning) {
                scanProgress.setVisibility(View.VISIBLE);
                scanCompleteIcon.setVisibility(View.GONE);
            } else {
                // Показываем галочку на секунду после завершения сканирования
                scanProgress.setVisibility(View.GONE);
                scanCompleteIcon.setVisibility(View.VISIBLE);

                // Скрываем индикатор через 1 секунду
                scanHandler.postDelayed(() -> {
                    if (scanIndicatorContainer != null) {
                        scanIndicatorContainer.setVisibility(View.GONE);
                    }
                }, 1000);
            }
        }
    }

    // Публичный метод для запуска сканирования из MainActivity
    public void startBluetoothScanPublic() {
        startBluetoothScan();
    }

    private void startPeriodicScanning() {
        // Очищаем предыдущие задачи
        scanHandler.removeCallbacks(scanRunnable);

        // Планируем периодические сканирования
        scanHandler.postDelayed(scanRunnable, SCAN_INTERVAL);
    }

    private void stopPeriodicScanning() {
        scanHandler.removeCallbacks(scanRunnable);

        if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }
    }

    private final Runnable scanRunnable = new Runnable() {
        @Override
        public void run() {
            startBluetoothScan();
            scanHandler.postDelayed(this, SCAN_INTERVAL);
        }
    };

    private void startBluetoothScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Log.w(TAG, "Bluetooth not available or not enabled");
            return;
        }

        if (isScanning) {
            Log.d(TAG, "Already scanning");
            return;
        }

        // Очищаем список доступных устройств перед новым сканированием
        availableDeviceAddresses.clear();

        // Сначала добавляем все сопряженные устройства как доступные
        try {
            Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
            for (BluetoothDevice device : pairedDevices) {
                availableDeviceAddresses.add(device.getAddress());
                if (device.getName() != null) {
                    deviceNameMap.put(device.getAddress(), device.getName());
                }
                Log.d(TAG, "Added paired device: " + device.getName() + " (" + device.getAddress() + ")");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting paired devices", e);
        }

        // Отменяем предыдущее сканирование, если оно еще идет
        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }

        // Начинаем новое сканирование
        isScanning = true;
        boolean started = bluetoothAdapter.startDiscovery();
        Log.d(TAG, "Bluetooth scan started: " + started);

        // Сразу обновляем доступность для сопряженных устройств
        updateDeviceAvailability();

        // Автоматически останавливаем сканирование через SCAN_DURATION
        scanHandler.postDelayed(() -> {
            if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }
        }, SCAN_DURATION);
    }

    private void updateDeviceAvailability() {
        // Обновляем статус доступности для всех устройств
        boolean hasChanges = false;

        // Создаем нормализованный набор доступных MAC адресов
        Set<String> normalizedAvailableAddresses = new HashSet<>();
        for (String mac : availableDeviceAddresses) {
            normalizedAvailableAddresses.add(normalizeMacAddress(mac));
        }

        for (DeviceInfo device : allDevices) {
            boolean wasAvailable = device.isAvailable;

            // Нормализуем MAC адрес устройства для сравнения
            String normalizedDeviceMac = normalizeMacAddress(device.address);
            device.isAvailable = normalizedAvailableAddresses.contains(normalizedDeviceMac);

            // Если устройство стало доступным, обновляем его имя из Bluetooth
            if (device.isAvailable) {
                // Ищем оригинальный MAC адрес в deviceNameMap
                for (String mac : availableDeviceAddresses) {
                    if (normalizeMacAddress(mac).equals(normalizedDeviceMac) &&
                            deviceNameMap.containsKey(mac)) {
                        device.bluetoothName = deviceNameMap.get(mac);
                        break;
                    }
                }
            }

            // Логируем изменения статуса
            if (wasAvailable != device.isAvailable) {
                Log.d(TAG, "Device " + device.getDisplayName() + " (" + device.address +
                        ") availability changed to: " + device.isAvailable);
                hasChanges = true;
            }
        }

        // Обновляем UI только если были изменения
        if (hasChanges || filteredDevices.isEmpty()) {
            filterDevices();
        }
    }

    public void refreshDeviceList() {
        allDevices.clear();

        // Получаем директорию приложения
        File appDir = requireContext().getFilesDir();
        File[] files = appDir.listFiles();

        if (files != null) {
            for (File file : files) {
                if (file.isDirectory() && hasRequiredFiles(file)) {
                    DeviceInfo info = new DeviceInfo();
                    info.folderName = file.getName();
                    info.originalName = file.getName();
                    info.customName = loadCustomName(file);

                    // Загружаем MAC адрес устройства
                    String savedAddress = DeviceInfoHelper.getDeviceAddress(requireContext(), file.getName());
                    info.address = savedAddress != null ? savedAddress : file.getName();

                    info.lastModified = file.lastModified();
                    info.folder = file;
                    info.isAvailable = false; // По умолчанию недоступно

                    // Получаем размер данных
                    File dataFile = new File(file, "data.txt");
                    if (dataFile.exists()) {
                        info.dataSize = countDataPoints(dataFile);
                    }

                    allDevices.add(info);

                    Log.d(TAG, "Loaded device: " + info.getDisplayName() +
                            " with MAC: " + info.address);
                }
            }
        }

        // Сортируем по дате изменения (новые первые)
        allDevices.sort((a, b) -> Long.compare(b.lastModified, a.lastModified));

        filterDevices();
    }

    private void filterDevices() {
        filteredDevices.clear();

        if (searchQuery.isEmpty()) {
            filteredDevices.addAll(allDevices);
        } else {
            for (DeviceInfo device : allDevices) {
                String displayName = device.getDisplayName().toLowerCase();
                String address = device.address.toLowerCase();

                if (displayName.contains(searchQuery) || address.contains(searchQuery)) {
                    filteredDevices.add(device);
                }
            }
        }

        // ВАЖНО: Создаем новый список для адаптера, чтобы DiffUtil увидел изменения
        List<DeviceInfo> newList = new ArrayList<>();
        for (DeviceInfo device : filteredDevices) {
            // Создаем копию объекта для обновления UI
            DeviceInfo copy = new DeviceInfo();
            copy.folderName = device.folderName;
            copy.originalName = device.originalName;
            copy.customName = device.customName;
            copy.address = device.address;
            copy.lastModified = device.lastModified;
            copy.dataSize = device.dataSize;
            copy.folder = device.folder;
            copy.isAvailable = device.isAvailable;
            copy.bluetoothName = device.bluetoothName;
            newList.add(copy);
        }

        adapter.submitList(newList);
        updateEmptyState();
    }

    private void updateEmptyState() {
        if (filteredDevices.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            emptyView.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }
    }

    private boolean hasRequiredFiles(File folder) {
        File infoFile = new File(folder, "info.txt");
        File dataFile = new File(folder, "data.txt");
        return infoFile.exists() && dataFile.exists();
    }

    private String loadCustomName(File folder) {
        File customNameFile = new File(folder, "custom_name.txt");
        if (customNameFile.exists()) {
            try {
                byte[] bytes = new byte[(int) customNameFile.length()];
                java.io.FileInputStream fis = new java.io.FileInputStream(customNameFile);
                fis.read(bytes);
                fis.close();
                return new String(bytes).trim();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    private void saveCustomName(File folder, String customName) {
        File customNameFile = new File(folder, "custom_name.txt");
        try {
            FileWriter writer = new FileWriter(customNameFile);
            writer.write(customName);
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private int countDataPoints(File dataFile) {
        int count = 0;
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(dataFile));
            while (reader.readLine() != null) count++;
            reader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return count;
    }

    private void showDeviceOptionsMenu(DeviceInfo device, View anchor) {
        PopupMenu popupMenu = new PopupMenu(requireContext(), anchor);
        popupMenu.getMenuInflater().inflate(R.menu.device_options_menu, popupMenu.getMenu());

        // Проверяем, является ли это MT-устройством
        boolean isMTDevice = MTDeviceDataHelper.isMTDevice(requireContext(), device.folderName);

        // Показываем пункт "Докачать данные" только для доступных НЕ-MT устройств
        MenuItem syncItem = popupMenu.getMenu().findItem(R.id.action_sync);
        if (syncItem != null) {
            syncItem.setVisible(device.isAvailable && !isMTDevice);
        }

        popupMenu.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.action_sync) {
                startDataSync(device);
                return true;
            } else if (itemId == R.id.action_rename) {
                showRenameDialog(device);
                return true;
            } else if (itemId == R.id.action_delete) {
                showDeleteConfirmationDialog(device);
                return true;
            } else if (itemId == R.id.action_info) {
                showDeviceInfoDialog(device);
                return true;
            }
            return false;
        });

        popupMenu.show();
    }

    private void startDataSync(DeviceInfo device) {
        // Показываем диалог подтверждения
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Докачать данные")
                .setMessage("Начать синхронизацию новых данных с устройства \"" +
                        device.getDisplayName() + "\"?")
                .setPositiveButton("Начать", (dialog, which) -> {
                    // Открываем DeviceActivity в режиме синхронизации
                    Intent intent = new Intent(getContext(), DeviceActivity.class);
                    intent.putExtra("DEVICE_ADDRESS", device.address);
                    intent.putExtra("DEVICE_NAME", device.getDisplayName());
                    intent.putExtra("IS_FROM_HISTORY", false);
                    intent.putExtra("SYNC_MODE", true);
                    intent.putExtra("LAST_SYNC_TIME", getLastSyncTime(device));
                    startActivity(intent);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private long getLastSyncTime(DeviceInfo device) {
        // Получаем время последней записи из data.txt
        File dataFile = new File(device.folder, "data.txt");
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
        } catch (IOException e) {
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

    private String normalizeMacAddress(String mac) {
        if (mac == null) return "";
        // Убираем все разделители и приводим к верхнему регистру
        return mac.replaceAll("[:-]", "").toUpperCase();
    }

    private void showRenameDialog(DeviceInfo device) {
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_rename_device, null);
        TextInputEditText nameEditText = dialogView.findViewById(R.id.name_edit_text);
        TextInputLayout nameInputLayout = dialogView.findViewById(R.id.name_input_layout);

        String currentName = device.getDisplayName();
        nameEditText.setText(currentName);
        nameEditText.setSelection(currentName.length());

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Переименовать устройство")
                .setView(dialogView)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    String newName = nameEditText.getText().toString().trim();
                    if (!newName.isEmpty()) {
                        device.customName = newName;
                        saveCustomName(device.folder, newName);
                        refreshDeviceList();
                        Toast.makeText(requireContext(), "Устройство переименовано", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showDeleteConfirmationDialog(DeviceInfo device) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Удалить устройство?")
                .setMessage("Вы уверены, что хотите удалить устройство \"" + device.getDisplayName() +
                        "\" и все связанные данные?")
                .setPositiveButton("Удалить", (dialog, which) -> {
                    deleteDeviceFolder(device.folder);
                    refreshDeviceList();
                    Toast.makeText(requireContext(), "Устройство удалено", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void deleteDeviceFolder(File folder) {
        if (folder.exists()) {
            File[] files = folder.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
            folder.delete();
        }
    }

    private void showDeviceInfoDialog(DeviceInfo device) {
        StringBuilder info = new StringBuilder();

        // Проверяем, является ли это MT-устройством
        boolean isMTDevice = MTDeviceDataHelper.isMTDevice(requireContext(), device.folderName);

        if (isMTDevice) {
            info.append("Тип: MT-устройство\n");
        }

        info.append("Название: ").append(device.getDisplayName()).append("\n");
        if (device.customName != null) {
            info.append("Оригинальное имя: ").append(device.originalName).append("\n");
        }
        info.append("Адрес: ").append(device.address).append("\n");
        info.append("Последнее обновление: ").append(formatDateTime(device.lastModified)).append("\n");
        info.append("Точек данных: ").append(device.dataSize).append("\n");

        // Добавляем информацию о доступности
        info.append("Статус: ").append(device.isAvailable ? "В зоне видимости" : "Вне зоны видимости").append("\n");
        if (device.isAvailable && device.bluetoothName != null) {
            info.append("Bluetooth имя: ").append(device.bluetoothName).append("\n");
        }

        // Размер папки
        long folderSize = getFolderSize(device.folder);
        info.append("Размер данных: ").append(formatFileSize(folderSize));

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Информация об устройстве")
                .setMessage(info.toString())
                .setPositiveButton("OK", null)
                .show();
    }

    private long getFolderSize(File folder) {
        long size = 0;
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                size += file.length();
            }
        }
        return size;
    }

    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Locale.US, "%.1f KB", size / 1024.0);
        return String.format(Locale.US, "%.1f MB", size / (1024.0 * 1024));
    }

    private String formatDateTime(long timestamp) {
        SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }

    // Класс для хранения информации об устройстве
    public static class DeviceInfo {
        String folderName;
        String originalName;
        String customName;
        String address;
        long lastModified;
        int dataSize;
        File folder;
        boolean isAvailable = false; // Доступно ли устройство в данный момент
        String bluetoothName; // Текущее имя устройства в Bluetooth

        public String getDisplayName() {
            return customName != null && !customName.isEmpty() ? customName : originalName;
        }
    }
}