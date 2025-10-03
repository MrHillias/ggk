package com.example.ggk;

import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

public class DeviceActivity extends AppCompatActivity {

    private String deviceAddress;
    private String deviceName;
    private String deviceFolderName;
    private boolean isFromHistory;
    private boolean isSyncMode;
    private long lastSyncTime;
    private boolean isMTDevice; // Новое поле для определения MT-устройства

    private TabLayout tabLayout;
    private ViewPager2 viewPager;
    private DevicePagerAdapter pagerAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device);

        // Получаем данные из Intent
        deviceAddress = getIntent().getStringExtra("DEVICE_ADDRESS");
        deviceName = getIntent().getStringExtra("DEVICE_NAME");
        deviceFolderName = getIntent().getStringExtra("DEVICE_FOLDER_NAME");
        isFromHistory = getIntent().getBooleanExtra("IS_FROM_HISTORY", false);
        isSyncMode = getIntent().getBooleanExtra("SYNC_MODE", false);
        lastSyncTime = getIntent().getLongExtra("LAST_SYNC_TIME", 0);

        // Если имя папки не передано, используем имя устройства
        if (deviceFolderName == null) {
            deviceFolderName = deviceName;
        }

        // Проверяем, является ли это MT-устройством
        isMTDevice = MTDeviceDataHelper.isMTDevice(this, deviceFolderName);

        // Устанавливаем заголовок
        String title = deviceName;
        if (isSyncMode) {
            title += " - Синхронизация";
        } else if (isMTDevice && isFromHistory) {
            title += " (MT)";
        }
        setTitle(title);

        // Включаем кнопку "назад" в ActionBar
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        // Инициализация UI
        tabLayout = findViewById(R.id.tab_layout);
        viewPager = findViewById(R.id.view_pager);

        setupTabs();
    }

    private void setupTabs() {
        pagerAdapter = new DevicePagerAdapter(this, deviceAddress, deviceName, deviceFolderName, isFromHistory, isMTDevice);
        viewPager.setAdapter(pagerAdapter);

        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            // Для MT-устройств из истории показываем только "График"
            if (isMTDevice && isFromHistory) {
                tab.setText("График");
            } else {
                // Для обычных устройств - стандартные вкладки
                switch (position) {
                    case 0:
                        tab.setText("Данные");
                        break;
                    case 1:
                        tab.setText("График");
                        break;
                    case 2:
                        tab.setText("Управление");
                        break;
                }
            }
        }).attach();

        // Если загружаем из истории и есть сохраненные данные, активируем вторую вкладку
        if (isFromHistory && !isMTDevice) {
            // Проверяем наличие данных для графика
            DataGraphFragment graphFragment = pagerAdapter.getGraphFragment();
            if (graphFragment != null && graphFragment.hasDataForGraph()) {
                // Активируем вторую вкладку после небольшой задержки
                viewPager.postDelayed(() -> viewPager.setCurrentItem(1), 100);
            }
        } else if (isMTDevice && isFromHistory) {
            // Для MT-устройств сразу показываем график (единственная вкладка)
            viewPager.setCurrentItem(0, false);
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    public String getDeviceFolderName() {
        return deviceFolderName;
    }

    public String getDeviceAddress() {
        return deviceAddress;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public boolean isFromHistory() {
        return isFromHistory;
    }

    public boolean isSyncMode() {
        return isSyncMode;
    }

    public long getLastSyncTime() {
        return lastSyncTime;
    }

    // Метод для обновления графика после получения данных
    public void updateGraph() {
        DataGraphFragment graphFragment = pagerAdapter.getGraphFragment();
        if (graphFragment != null) {
            graphFragment.loadAndDisplayGraph();
        }
    }
}