package com.example.ggk;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ConnectedDevicesAdapter extends ListAdapter<ConnectedDevicesFragment.DeviceInfo,
        ConnectedDevicesAdapter.DeviceViewHolder> {

    public interface OnDeviceClickListener {
        void onDeviceClick(ConnectedDevicesFragment.DeviceInfo device);
        void onDeviceOptionsClick(ConnectedDevicesFragment.DeviceInfo device, View anchor);
    }

    private final OnDeviceClickListener listener;

    public ConnectedDevicesAdapter(OnDeviceClickListener listener) {
        super(new DeviceDiffCallback());
        this.listener = listener;
    }

    @NonNull
    @Override
    public DeviceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_connected_device, parent, false);
        return new DeviceViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull DeviceViewHolder holder, int position) {
        holder.bind(getItem(position));
    }

    class DeviceViewHolder extends RecyclerView.ViewHolder {
        private final TextView deviceName;
        private final TextView deviceAddress;
        private final TextView lastUpdateText;
        private final TextView dataPointsText;
        private final ImageButton optionsButton;
        private final ImageView deviceIcon;
        private final View iconBackground;
        private final TextView statusChip;

        DeviceViewHolder(@NonNull View itemView) {
            super(itemView);
            deviceName = itemView.findViewById(R.id.device_name);
            deviceAddress = itemView.findViewById(R.id.device_address);
            lastUpdateText = itemView.findViewById(R.id.last_update_text);
            dataPointsText = itemView.findViewById(R.id.data_points_text);
            optionsButton = itemView.findViewById(R.id.options_button);
            deviceIcon = itemView.findViewById(R.id.device_icon);
            iconBackground = itemView.findViewById(R.id.icon_background);
            statusChip = itemView.findViewById(R.id.status_chip);

            itemView.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    listener.onDeviceClick(getItem(position));
                }
            });

            optionsButton.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    listener.onDeviceOptionsClick(getItem(position), v);
                }
            });
        }

        void bind(ConnectedDevicesFragment.DeviceInfo device) {
            deviceName.setText(device.getDisplayName());
            deviceAddress.setText(device.address);

            // Показываем точное время обновления
            SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault());
            String updateTime = dateFormat.format(new Date(device.lastModified));
            lastUpdateText.setText("Обновлено в " + updateTime);

            // Количество точек данных - теперь под временем обновления
            if (device.dataSize > 0) {
                dataPointsText.setVisibility(View.VISIBLE);
                dataPointsText.setText(device.dataSize + " измерений");
            } else {
                dataPointsText.setVisibility(View.GONE);
            }

            // Устанавливаем цвет иконки в зависимости от давности обновления
            long timeDiff = System.currentTimeMillis() - device.lastModified;
            if (timeDiff < 86400000) { // Меньше суток
                iconBackground.setBackgroundResource(R.drawable.circle_background_green);
            } else if (timeDiff < 604800000) { // Меньше недели
                iconBackground.setBackgroundResource(R.drawable.circle_background_orange);
            } else {
                iconBackground.setBackgroundResource(R.drawable.circle_background);
            }

            // Показываем статус доступности
            if (device.isAvailable) {
                statusChip.setVisibility(View.VISIBLE);
                statusChip.setText("В сети");
                statusChip.setBackgroundResource(R.drawable.chip_background_available);
                statusChip.setTextColor(itemView.getContext().getResources().getColor(R.color.white));
            } else {
                statusChip.setVisibility(View.VISIBLE);
                statusChip.setText("Не в сети");
                statusChip.setBackgroundResource(R.drawable.chip_background_unavailable);
                statusChip.setTextColor(itemView.getContext().getResources().getColor(R.color.chip_text_unavailable));
            }
        }
    }

    static class DeviceDiffCallback extends DiffUtil.ItemCallback<ConnectedDevicesFragment.DeviceInfo> {
        @Override
        public boolean areItemsTheSame(@NonNull ConnectedDevicesFragment.DeviceInfo oldItem,
                                       @NonNull ConnectedDevicesFragment.DeviceInfo newItem) {
            return oldItem.folderName.equals(newItem.folderName);
        }

        @Override
        public boolean areContentsTheSame(@NonNull ConnectedDevicesFragment.DeviceInfo oldItem,
                                          @NonNull ConnectedDevicesFragment.DeviceInfo newItem) {
            return oldItem.folderName.equals(newItem.folderName) &&
                    oldItem.getDisplayName().equals(newItem.getDisplayName()) &&
                    oldItem.lastModified == newItem.lastModified &&
                    oldItem.dataSize == newItem.dataSize &&
                    oldItem.isAvailable == newItem.isAvailable;
        }
    }
}