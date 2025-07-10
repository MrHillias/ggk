package com.example.ggk;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class DevicePagerAdapter extends FragmentStateAdapter {

    private final String deviceAddress;
    private final String deviceName;
    private final boolean isFromHistory;

    private DataTransferFragment dataTransferFragment;
    private DataGraphFragment dataGraphFragment;

    public DevicePagerAdapter(@NonNull FragmentActivity fragmentActivity,
                              String deviceAddress, String deviceName, boolean isFromHistory) {
        super(fragmentActivity);
        this.deviceAddress = deviceAddress;
        this.deviceName = deviceName;
        this.isFromHistory = isFromHistory;
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case 0:
                if (dataTransferFragment == null) {
                    dataTransferFragment = new DataTransferFragment();
                }
                return dataTransferFragment;
            case 1:
                if (dataGraphFragment == null) {
                    dataGraphFragment = new DataGraphFragment();
                }
                return dataGraphFragment;
            default:
                throw new IllegalArgumentException("Invalid position: " + position);
        }
    }

    @Override
    public int getItemCount() {
        return 2;
    }

    public DataGraphFragment getGraphFragment() {
        return dataGraphFragment;
    }
}