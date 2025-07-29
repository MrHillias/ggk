package com.example.ggk;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class DevicePagerAdapter extends FragmentStateAdapter {

    private final String deviceAddress;
    private final String deviceName;
    private final String deviceFolderName;
    private final boolean isFromHistory;

    private DataTransferFragment dataTransferFragment;
    private DataGraphFragment dataGraphFragment;
    private CommandControlFragment commandControlFragment;

    public DevicePagerAdapter(@NonNull FragmentActivity fragmentActivity,
                              String deviceAddress, String deviceName, String deviceFolderName, boolean isFromHistory) {
        super(fragmentActivity);
        this.deviceAddress = deviceAddress;
        this.deviceName = deviceName;
        this.deviceFolderName = deviceFolderName;
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
            case 2:
                if (commandControlFragment == null) {
                    commandControlFragment = new CommandControlFragment();
                }
                return commandControlFragment;
            default:
                throw new IllegalArgumentException("Invalid position: " + position);
        }
    }

    @Override
    public int getItemCount() {
        return 3; // Всегда 3 вкладки для всех устройств
    }

    public DataGraphFragment getGraphFragment() {
        return dataGraphFragment;
    }
}