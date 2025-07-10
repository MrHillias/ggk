package com.example.ggk;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.PopupMenu;
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
import java.util.List;
import java.util.Locale;

public class ConnectedDevicesFragment extends Fragment {

    private RecyclerView recyclerView;
    private View emptyView;
    private EditText searchEditText;
    private ConnectedDevicesAdapter adapter;
    private List<DeviceInfo> allDevices;
    private List<DeviceInfo> filteredDevices;
    private String searchQuery = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_connected_devices, container, false);

        recyclerView = view.findViewById(R.id.devices_recycler_view);
        emptyView = view.findViewById(R.id.empty_view);
        searchEditText = view.findViewById(R.id.search_edit_text);

        allDevices = new ArrayList<>();
        filteredDevices = new ArrayList<>();

        adapter = new ConnectedDevicesAdapter(
                new ConnectedDevicesAdapter.OnDeviceClickListener() {
                    @Override
                    public void onDeviceClick(DeviceInfo device) {
                        MainActivity mainActivity = (MainActivity) getActivity();
                        if (mainActivity != null) {
                            mainActivity.openDeviceDetails(
                                    device.address,
                                    device.getDisplayName(),
                                    true
                            );
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
        refreshDeviceList();
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
                    info.address = DeviceInfoHelper.getDeviceAddress(requireContext(), file.getName());
                    info.lastModified = file.lastModified();
                    info.folder = file;

                    // Получаем размер данных
                    File dataFile = new File(file, "data.txt");
                    if (dataFile.exists()) {
                        info.dataSize = countDataPoints(dataFile);
                    }

                    allDevices.add(info);
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

        adapter.submitList(new ArrayList<>(filteredDevices));
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

        popupMenu.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.action_rename) {
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
        info.append("Название: ").append(device.getDisplayName()).append("\n");
        if (device.customName != null) {
            info.append("Оригинальное имя: ").append(device.originalName).append("\n");
        }
        info.append("Адрес: ").append(device.address).append("\n");
        info.append("Последнее обновление: ").append(formatDateTime(device.lastModified)).append("\n");
        info.append("Точек данных: ").append(device.dataSize).append("\n");

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

        public String getDisplayName() {
            return customName != null && !customName.isEmpty() ? customName : originalName;
        }
    }
}