package com.example.ggk;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.splashscreen.SplashScreen;
import androidx.viewpager2.widget.ViewPager2;

import androidx.appcompat.widget.Toolbar;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_ALL_PERMISSIONS = 100;

    private TabLayout tabLayout;
    private ViewPager2 viewPager;
    private MainPagerAdapter pagerAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Временно отключаем splash screen
        // SplashScreen splashScreen = SplashScreen.installSplashScreen(this);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Инициализация UI компонентов
        Toolbar toolbar = findViewById(R.id.toolbar);
        tabLayout = findViewById(R.id.tab_layout);
        viewPager = findViewById(R.id.view_pager);

        setSupportActionBar(toolbar);

        // Проверка и запрос разрешений
        if (checkAndRequestPermissions()) {
            setupTabs();
        }
    }

    private boolean checkAndRequestPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();

        // Bluetooth разрешения для Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN);
            }
        }

        // Разрешение на местоположение для BLE
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        // Разрешения на хранилище для Android < 10
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }

        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permissionsNeeded.toArray(new String[0]), REQUEST_ALL_PERMISSIONS);
            return false;
        }

        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_ALL_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                setupTabs();
            } else {
                Toast.makeText(this, "Все разрешения необходимы для работы приложения",
                        Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private void setupTabs() {
        pagerAdapter = new MainPagerAdapter(this);
        viewPager.setAdapter(pagerAdapter);

        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            switch (position) {
                case 0:
                    tab.setText("Доступные устройства");
                    break;
                case 1:
                    tab.setText("Подключенные устройства");
                    break;
            }
        }).attach();
    }

    public void openDeviceDetailsWithFolder(String deviceAddress, String deviceName, String deviceFolderName, boolean isFromHistory) {
        Intent intent = new Intent(this, DeviceActivity.class);
        intent.putExtra("DEVICE_ADDRESS", deviceAddress);
        intent.putExtra("DEVICE_NAME", deviceName);
        intent.putExtra("DEVICE_FOLDER_NAME", deviceFolderName);
        intent.putExtra("IS_FROM_HISTORY", isFromHistory);
        startActivity(intent);
    }

    public void openDeviceDetailsForSyncWithFolder(String deviceAddress, String deviceName, String deviceFolderName, long lastSyncTime) {
        Intent intent = new Intent(this, DeviceActivity.class);
        intent.putExtra("DEVICE_ADDRESS", deviceAddress);
        intent.putExtra("DEVICE_NAME", deviceName);
        intent.putExtra("DEVICE_FOLDER_NAME", deviceFolderName);
        intent.putExtra("IS_FROM_HISTORY", false);
        intent.putExtra("SYNC_MODE", true);
        intent.putExtra("LAST_SYNC_TIME", lastSyncTime);
        startActivity(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Обновляем список подключенных устройств при возврате к экрану
        if (pagerAdapter != null && pagerAdapter.getConnectedDevicesFragment() != null) {
            ConnectedDevicesFragment fragment = pagerAdapter.getConnectedDevicesFragment();
            // Обновляем список устройств
            fragment.refreshDeviceList();
            // Запускаем сканирование для обновления статусов
            fragment.startBluetoothScanPublic();
        }
    }

    public void openDeviceDetails(String deviceAddress, String deviceName, boolean isFromHistory) {
        Intent intent = new Intent(this, DeviceActivity.class);
        intent.putExtra("DEVICE_ADDRESS", deviceAddress);
        intent.putExtra("DEVICE_NAME", deviceName);
        intent.putExtra("IS_FROM_HISTORY", isFromHistory);
        startActivity(intent);
    }

    public void openDeviceDetailsForSync(String deviceAddress, String deviceName, long lastSyncTime) {
        Intent intent = new Intent(this, DeviceActivity.class);
        intent.putExtra("DEVICE_ADDRESS", deviceAddress);
        intent.putExtra("DEVICE_NAME", deviceName);
        intent.putExtra("IS_FROM_HISTORY", false);
        intent.putExtra("SYNC_MODE", true);
        intent.putExtra("LAST_SYNC_TIME", lastSyncTime);
        startActivity(intent);
    }
}