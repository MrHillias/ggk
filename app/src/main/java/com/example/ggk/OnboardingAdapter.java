package com.example.ggk;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class OnboardingAdapter extends RecyclerView.Adapter<OnboardingAdapter.OnboardingViewHolder> {

    private final List<OnboardingActivity.OnboardingItem> onboardingItems;

    public OnboardingAdapter(List<OnboardingActivity.OnboardingItem> onboardingItems) {
        this.onboardingItems = onboardingItems;
    }

    @NonNull
    @Override
    public OnboardingViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_onboarding, parent, false);
        return new OnboardingViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull OnboardingViewHolder holder, int position) {
        OnboardingActivity.OnboardingItem item = onboardingItems.get(position);
        holder.bind(item);
    }

    @Override
    public int getItemCount() {
        return onboardingItems.size();
    }

    static class OnboardingViewHolder extends RecyclerView.ViewHolder {
        private final ImageView imageView;
        private final TextView titleTextView;
        private final TextView descriptionTextView;

        OnboardingViewHolder(@NonNull View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.onboarding_image);
            titleTextView = itemView.findViewById(R.id.onboarding_title);
            descriptionTextView = itemView.findViewById(R.id.onboarding_description);
        }

        void bind(OnboardingActivity.OnboardingItem item) {
            imageView.setImageResource(item.getImageResource());
            titleTextView.setText(item.getTitle());
            descriptionTextView.setText(item.getDescription());
        }
    }
}