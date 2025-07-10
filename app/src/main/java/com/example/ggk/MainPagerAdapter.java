package com.example.ggk;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class MainPagerAdapter extends FragmentStateAdapter {

    private AvailableDevicesFragment availableDevicesFragment;
    private ConnectedDevicesFragment connectedDevicesFragment;

    public MainPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case 0:
                if (availableDevicesFragment == null) {
                    availableDevicesFragment = new AvailableDevicesFragment();
                }
                return availableDevicesFragment;
            case 1:
                if (connectedDevicesFragment == null) {
                    connectedDevicesFragment = new ConnectedDevicesFragment();
                }
                return connectedDevicesFragment;
            default:
                throw new IllegalArgumentException("Invalid position: " + position);
        }
    }

    @Override
    public int getItemCount() {
        return 2;
    }

    public ConnectedDevicesFragment getConnectedDevicesFragment() {
        return connectedDevicesFragment;
    }
}