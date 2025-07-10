package com.example.ggk;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SuppressLint("MissingPermission")
public class AvailableDevicesFragment extends Fragment implements SwipeRefreshLayout.OnRefreshListener {

    private BluetoothAdapter bluetoothAdapter;
    private DeviceListAdapter adapter;
    private SwipeRefreshLayout swipeRefreshLayout;
    private RecyclerView recyclerView;
    private View scanningCard;
    private View emptyState;
    private Handler mainHandler;
    private Set<String> foundDevices = new HashSet<>();
    private List<DeviceListAdapter.DeviceItem> deviceList = new ArrayList<>();

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null && !foundDevices.contains(device.getAddress())) {
                    foundDevices.add(device.getAddress());
                    addDeviceToList(device, false);
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
                // Используем только SwipeRefreshLayout для индикации
                swipeRefreshLayout.setRefreshing(true);
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                swipeRefreshLayout.setRefreshing(false);
                updateEmptyState();
            }
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainHandler = new Handler(Looper.getMainLooper());

        BluetoothManager bluetoothManager = (BluetoothManager) requireContext()
                .getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_available_devices, container, false);

        swipeRefreshLayout = view.findViewById(R.id.swipe_refresh_layout);
        recyclerView = view.findViewById(R.id.device_list);
        scanningCard = view.findViewById(R.id.scanning_card);
        emptyState = view.findViewById(R.id.empty_state);

        swipeRefreshLayout.setOnRefreshListener(this);
        swipeRefreshLayout.setColorSchemeResources(
                R.color.md_theme_primary,
                R.color.md_theme_secondary,
                R.color.md_theme_tertiary
        );

        adapter = new DeviceListAdapter(device -> {
            if (bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }

            MainActivity mainActivity = (MainActivity) getActivity();
            if (mainActivity != null) {
                mainActivity.openDeviceDetails(
                        device.getAddress(),
                        device.getName(),
                        false
                );
            }
        });

        recyclerView.setAdapter(adapter);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        registerReceivers();
        refreshDeviceList();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }
        try {
            requireContext().unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {}
    }

    private void registerReceivers() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        requireContext().registerReceiver(receiver, filter);
    }

    @Override
    public void onRefresh() {
        refreshDeviceList();
    }

    private void refreshDeviceList() {
        Log.d("AvailableDevices", "refreshDeviceList called");

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            swipeRefreshLayout.setRefreshing(false);
            Toast.makeText(getContext(), "Bluetooth не включен", Toast.LENGTH_SHORT).show();
            Log.e("AvailableDevices", "Bluetooth not enabled");
            return;
        }

        deviceList.clear();
        foundDevices.clear();
        adapter.submitList(new ArrayList<>());

        // Показываем сопряженные устройства
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        Log.d("AvailableDevices", "Paired devices: " + pairedDevices.size());
        for (BluetoothDevice device : pairedDevices) {
            addDeviceToList(device, true);
        }

        // Начинаем поиск новых устройств
        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }

        boolean started = bluetoothAdapter.startDiscovery();
        Log.d("AvailableDevices", "Discovery started: " + started);
    }

    private void addDeviceToList(BluetoothDevice device, boolean isPaired) {
        Log.d("AvailableDevices", "Adding device: " + device.getAddress() + " paired: " + isPaired);

        DeviceListAdapter.DeviceItem item = new DeviceListAdapter.DeviceItem(device, isPaired);
        deviceList.add(item);

        mainHandler.post(() -> {
            adapter.submitList(new ArrayList<>(deviceList));
            updateEmptyState();
            Log.d("AvailableDevices", "List updated, size: " + deviceList.size());
        });
    }

    private void updateEmptyState() {
        emptyState.setVisibility(deviceList.isEmpty() ? View.VISIBLE : View.GONE);
    }
}