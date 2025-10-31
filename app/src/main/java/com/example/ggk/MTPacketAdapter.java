package com.example.ggk;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.Locale;

public class MTPacketAdapter extends ListAdapter<MTPacketAdapter.PacketData, MTPacketAdapter.PacketViewHolder> {

    public static class PacketData {
        public final int packetNumber;
        public final List<Integer> values; // Значения от 0 до 131
        public final int receivedChecksum; // Контрольная сумма из данных
        public final int calculatedChecksum; // Рассчитанная контрольная сумма
        public boolean isExpanded = false;

        public PacketData(int packetNumber, List<Integer> values, int receivedChecksum) {
            this.packetNumber = packetNumber;
            this.values = values;
            this.receivedChecksum = receivedChecksum;
            this.calculatedChecksum = calculateChecksum(values);
        }

        private int calculateChecksum(List<Integer> values) {
            int sum = 0;
            for (int value : values) {
                sum += value;
            }
            return sum;
        }

        public boolean isChecksumValid() {
            return receivedChecksum == calculatedChecksum;
        }
    }

    public MTPacketAdapter() {
        super(new PacketDiffCallback());
    }

    @NonNull
    @Override
    public PacketViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_mt_packet, parent, false);
        return new PacketViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PacketViewHolder holder, int position) {
        holder.bind(getItem(position));
    }

    class PacketViewHolder extends RecyclerView.ViewHolder {
        private final TextView packetNumberText;
        private final TextView receivedChecksumText;
        private final TextView calculatedChecksumText;
        private final TextView statusText;
        private final ImageView expandIcon;
        private final View detailsContainer;
        private final TextView dataValuesText;

        PacketViewHolder(@NonNull View itemView) {
            super(itemView);
            packetNumberText = itemView.findViewById(R.id.packet_number_text);
            receivedChecksumText = itemView.findViewById(R.id.received_checksum_text);
            calculatedChecksumText = itemView.findViewById(R.id.calculated_checksum_text);
            statusText = itemView.findViewById(R.id.status_text);
            expandIcon = itemView.findViewById(R.id.expand_icon);
            detailsContainer = itemView.findViewById(R.id.details_container);
            dataValuesText = itemView.findViewById(R.id.data_values_text);

            itemView.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    PacketData packet = getItem(position);
                    packet.isExpanded = !packet.isExpanded;
                    notifyItemChanged(position);
                }
            });
        }

        void bind(PacketData packet) {
            // Номер пакета
            packetNumberText.setText(String.format(Locale.US, "Пакет #%d", packet.packetNumber));

            // Контрольные суммы
            receivedChecksumText.setText(String.format(Locale.US, "Получено: %d", packet.receivedChecksum));
            calculatedChecksumText.setText(String.format(Locale.US, "Рассчитано: %d", packet.calculatedChecksum));

            // Статус проверки
            if (packet.isChecksumValid()) {
                statusText.setText("✓ Верно");
                statusText.setTextColor(itemView.getContext().getResources().getColor(R.color.bluetooth_connected));
                itemView.setBackgroundResource(R.drawable.packet_background_valid);
            } else {
                statusText.setText("✗ Ошибка");
                statusText.setTextColor(itemView.getContext().getResources().getColor(R.color.bluetooth_disconnected));
                itemView.setBackgroundResource(R.drawable.packet_background_error);
            }

            // Раскрытие/сворачивание
            if (packet.isExpanded) {
                expandIcon.setRotation(180);
                detailsContainer.setVisibility(View.VISIBLE);

                // Форматируем данные в строку
                StringBuilder dataText = new StringBuilder();
                dataText.append("Значения (").append(packet.values.size()).append(" шт.):\n");

                // Показываем по 10 значений в строке
                for (int i = 0; i < packet.values.size(); i++) {
                    dataText.append(packet.values.get(i));
                    if (i < packet.values.size() - 1) {
                        dataText.append(", ");
                        if ((i + 1) % 10 == 0) {
                            dataText.append("\n");
                        }
                    }
                }

                dataValuesText.setText(dataText.toString());
            } else {
                expandIcon.setRotation(0);
                detailsContainer.setVisibility(View.GONE);
            }
        }
    }

    static class PacketDiffCallback extends DiffUtil.ItemCallback<PacketData> {
        @Override
        public boolean areItemsTheSame(@NonNull PacketData oldItem, @NonNull PacketData newItem) {
            return oldItem.packetNumber == newItem.packetNumber;
        }

        @Override
        public boolean areContentsTheSame(@NonNull PacketData oldItem, @NonNull PacketData newItem) {
            return oldItem.packetNumber == newItem.packetNumber &&
                    oldItem.receivedChecksum == newItem.receivedChecksum &&
                    oldItem.calculatedChecksum == newItem.calculatedChecksum &&
                    oldItem.isExpanded == newItem.isExpanded;
        }
    }
}