package com.example.ggk;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;

public class DeviceListAdapter extends ListAdapter<DeviceListAdapter.DeviceItem, DeviceListAdapter.DeviceViewHolder> {

    public interface OnDeviceClickListener {
        void onDeviceClick(DeviceItem device);
    }

    private final OnDeviceClickListener listener;

    public DeviceListAdapter(OnDeviceClickListener listener) {
        super(new DeviceDiffCallback());
        this.listener = listener;
    }

    @NonNull
    @Override
    public DeviceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_device, parent, false);
        return new DeviceViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull DeviceViewHolder holder, int position) {
        holder.bind(getItem(position));
    }

    class DeviceViewHolder extends RecyclerView.ViewHolder {
        private final TextView deviceName;
        private final TextView deviceAddress;
        private final TextView statusChip;

        DeviceViewHolder(@NonNull View itemView) {
            super(itemView);
            deviceName = itemView.findViewById(R.id.device_name);
            deviceAddress = itemView.findViewById(R.id.device_address);
            statusChip = itemView.findViewById(R.id.status_chip);

            itemView.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    listener.onDeviceClick(getItem(position));
                }
            });
        }

        @SuppressLint("MissingPermission")
        void bind(DeviceItem item) {
            // Используем отображаемое имя (пользовательское или оригинальное)
            String name = item.getDisplayName();
            if (name == null || name.isEmpty()) {
                name = "Неизвестное устройство";
            }

            // Проверяем, есть ли сохраненные данные для этого устройства
            boolean hasData = checkIfHasData(item.getAddress(), item.getName());

            deviceName.setText(name);
            deviceAddress.setText(item.getAddress());

            // Показываем статус
            if (hasData) {
                statusChip.setVisibility(View.VISIBLE);
                statusChip.setText("Есть данные");
                statusChip.setBackgroundResource(R.drawable.chip_background_available);
            } else if (item.isPaired()) {
                statusChip.setVisibility(View.VISIBLE);
                statusChip.setText("Сопряжено");
                statusChip.setBackgroundResource(R.drawable.chip_background);
            } else {
                statusChip.setVisibility(View.GONE);
            }
        }

        private boolean checkIfHasData(String address, String name) {
            Context context = itemView.getContext();
            File appDir = context.getFilesDir();
            File[] files = appDir.listFiles();

            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        // Проверяем по MAC-адресу
                        String savedAddress = DeviceInfoHelper.getDeviceAddress(context, file.getName());
                        if (savedAddress != null && savedAddress.equalsIgnoreCase(address)) {
                            return true;
                        }

                        // Также проверяем по имени
                        if (file.getName().equals(name)) {
                            return true;
                        }
                    }
                }
            }

            return false;
        }
    }

    public static class DeviceItem {
        private final BluetoothDevice device;
        private final boolean isPaired;
        private String customName;

        public DeviceItem(BluetoothDevice device, boolean isPaired) {
            this.device = device;
            this.isPaired = isPaired;
        }

        public void setCustomName(String customName) {
            this.customName = customName;
        }

        @SuppressLint("MissingPermission")
        public String getName() {
            return device.getName();
        }

        // Получаем отображаемое имя (пользовательское или оригинальное)
        @SuppressLint("MissingPermission")
        public String getDisplayName() {
            if (customName != null && !customName.isEmpty()) {
                return customName;
            }
            return device.getName();
        }

        public String getAddress() {
            return device.getAddress();
        }

        public BluetoothDevice getDevice() {
            return device;
        }

        public boolean isPaired() {
            return isPaired;
        }
    }

    static class DeviceDiffCallback extends DiffUtil.ItemCallback<DeviceItem> {
        @Override
        public boolean areItemsTheSame(@NonNull DeviceItem oldItem, @NonNull DeviceItem newItem) {
            return oldItem.getAddress().equals(newItem.getAddress());
        }

        @Override
        public boolean areContentsTheSame(@NonNull DeviceItem oldItem, @NonNull DeviceItem newItem) {
            String oldName = oldItem.customName != null ? oldItem.customName : oldItem.getName();
            String newName = newItem.customName != null ? newItem.customName : newItem.getName();

            return oldItem.getAddress().equals(newItem.getAddress()) &&
                    oldItem.isPaired() == newItem.isPaired() &&
                    ((oldName == null && newName == null) ||
                            (oldName != null && oldName.equals(newName)));
        }
    }
}