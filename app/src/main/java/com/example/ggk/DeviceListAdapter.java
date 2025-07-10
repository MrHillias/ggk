package com.example.ggk;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

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
            String name = item.getName();
            if (name == null || name.isEmpty()) {
                name = "Неизвестное устройство";
            }
            deviceName.setText(name);
            deviceAddress.setText(item.getAddress());

            // Показываем статус для сопряженных устройств
            if (item.isPaired()) {
                statusChip.setVisibility(View.VISIBLE);
                statusChip.setText("Сопряжено");
            } else {
                statusChip.setVisibility(View.GONE);
            }
        }
    }

    public static class DeviceItem {
        private final BluetoothDevice device;
        private final boolean isPaired;

        public DeviceItem(BluetoothDevice device, boolean isPaired) {
            this.device = device;
            this.isPaired = isPaired;
        }

        @SuppressLint("MissingPermission")
        public String getName() {
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
            return oldItem.getAddress().equals(newItem.getAddress()) &&
                    oldItem.isPaired() == newItem.isPaired();
        }
    }
}