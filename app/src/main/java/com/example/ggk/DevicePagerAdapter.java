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
    private final boolean isMTDevice;

    private DataTransferFragment dataTransferFragment;
    private DataGraphFragment dataGraphFragment;
    private CommandControlFragment commandControlFragment;

    public DevicePagerAdapter(@NonNull FragmentActivity fragmentActivity,
                              String deviceAddress, String deviceName, String deviceFolderName,
                              boolean isFromHistory, boolean isMTDevice) {
        super(fragmentActivity);
        this.deviceAddress = deviceAddress;
        this.deviceName = deviceName;
        this.deviceFolderName = deviceFolderName;
        this.isFromHistory = isFromHistory;
        this.isMTDevice = isMTDevice;
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        // Для MT-устройств из истории показываем только график
        if (isMTDevice && isFromHistory) {
            if (dataGraphFragment == null) {
                dataGraphFragment = new DataGraphFragment();
            }
            return dataGraphFragment;
        }

        // Для обычных устройств - стандартная логика
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
        // Для MT-устройств из истории только 1 вкладка (График)
        if (isMTDevice && isFromHistory) {
            return 1;
        }
        // Для обычных устройств - 3 вкладки
        return 3;
    }

    public DataGraphFragment getGraphFragment() {
        return dataGraphFragment;
    }
}