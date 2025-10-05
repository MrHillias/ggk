package com.example.ggk;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MTDataAdapter extends ListAdapter<MTDataAdapter.DataPoint, MTDataAdapter.DataPointViewHolder> {

    public static class DataPoint {
        public final int index;
        public final double value;
        public final long timestamp;

        public DataPoint(int index, double value, long timestamp) {
            this.index = index;
            this.value = value;
            this.timestamp = timestamp;
        }
    }

    public MTDataAdapter() {
        super(new DataPointDiffCallback());
    }

    @NonNull
    @Override
    public DataPointViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_mt_data_point, parent, false);
        return new DataPointViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull DataPointViewHolder holder, int position) {
        holder.bind(getItem(position));
    }

    static class DataPointViewHolder extends RecyclerView.ViewHolder {
        private final TextView pointIndex;
        private final TextView timestampText;
        private final TextView dateText;
        private final TextView pressureValue;
        private final TextView unitText;

        private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault());

        DataPointViewHolder(@NonNull View itemView) {
            super(itemView);
            pointIndex = itemView.findViewById(R.id.point_index);
            timestampText = itemView.findViewById(R.id.timestamp_text);
            dateText = itemView.findViewById(R.id.date_text);
            pressureValue = itemView.findViewById(R.id.pressure_value);
            unitText = itemView.findViewById(R.id.unit_text);
        }

        void bind(DataPoint dataPoint) {
            // Индекс (показываем с 1, а не с 0)
            pointIndex.setText(String.valueOf(dataPoint.index + 1));

            // Форматируем дату и время
            Date date = new Date(dataPoint.timestamp);
            timestampText.setText(timeFormat.format(date));
            dateText.setText(dateFormat.format(date));

            // Значение давления
            pressureValue.setText(String.format(Locale.US, "%.0f", dataPoint.value));

            // Единица измерения (пока Па, можно расширить)
            unitText.setText("Па");
        }
    }

    static class DataPointDiffCallback extends DiffUtil.ItemCallback<DataPoint> {
        @Override
        public boolean areItemsTheSame(@NonNull DataPoint oldItem, @NonNull DataPoint newItem) {
            return oldItem.index == newItem.index;
        }

        @Override
        public boolean areContentsTheSame(@NonNull DataPoint oldItem, @NonNull DataPoint newItem) {
            return oldItem.index == newItem.index &&
                    oldItem.value == newItem.value &&
                    oldItem.timestamp == newItem.timestamp;
        }
    }
}