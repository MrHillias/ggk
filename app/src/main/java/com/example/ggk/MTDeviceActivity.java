package com.example.ggk;

import android.os.Bundle;
import android.view.MenuItem;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

public class MTDeviceActivity extends AppCompatActivity {

    private String deviceAddress;
    private String deviceName;

    private TabLayout tabLayout;
    private ViewPager2 viewPager;
    private MTPagerAdapter pagerAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mt_device);

        // Получаем данные из Intent
        deviceAddress = getIntent().getStringExtra("DEVICE_ADDRESS");
        deviceName = getIntent().getStringExtra("DEVICE_NAME");

        // Устанавливаем заголовок
        setTitle(deviceName + " (MT Device)");

        // Включаем кнопку "назад"
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        // Инициализация UI
        tabLayout = findViewById(R.id.tab_layout);
        viewPager = findViewById(R.id.view_pager);

        setupTabs();
    }

    private void setupTabs() {
        pagerAdapter = new MTPagerAdapter(this);
        viewPager.setAdapter(pagerAdapter);

        // ВАЖНО: Отключаем предзагрузку соседних страниц
        viewPager.setOffscreenPageLimit(ViewPager2.OFFSCREEN_PAGE_LIMIT_DEFAULT);

        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            switch (position) {
                case 0:
                    tab.setText("Информация");
                    tab.setIcon(R.drawable.ic_info);
                    break;
                case 1:
                    tab.setText("Данные");
                    tab.setIcon(R.drawable.ic_data);
                    break;
                case 2:
                    tab.setText("График");
                    tab.setIcon(R.drawable.ic_chart);
                    break;
            }
        }).attach();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public String getDeviceAddress() {
        return deviceAddress;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public MTPagerAdapter getPagerAdapter() {
        return pagerAdapter;
    }

    // Внутренний класс адаптера для вкладок (публичный для доступа из фрагментов)
    public class MTPagerAdapter extends FragmentStateAdapter {

        private MTDeviceInfoFragment infoFragment;
        private MTDataFragment dataFragment;
        private DataGraphFragment graphFragment;

        public MTPagerAdapter(FragmentActivity activity) {
            super(activity);
        }

        @Override
        public Fragment createFragment(int position) {
            switch (position) {
                case 0:
                    if (infoFragment == null) {
                        infoFragment = new MTDeviceInfoFragment();
                    }
                    return infoFragment;
                case 1:
                    if (dataFragment == null) {
                        dataFragment = new MTDataFragment();
                    }
                    return dataFragment;
                case 2:
                    if (graphFragment == null) {
                        graphFragment = new DataGraphFragment();
                    }
                    return graphFragment;
                default:
                    throw new IllegalArgumentException("Invalid position: " + position);
            }
        }

        @Override
        public int getItemCount() {
            return 3; // Теперь 3 вкладки вместо 4
        }

        public MTDeviceInfoFragment getInfoFragment() {
            return infoFragment;
        }

        public MTDataFragment getDataFragment() {
            return dataFragment;
        }

        public DataGraphFragment getGraphFragment() {
            return graphFragment;
        }
    }

    private boolean shouldAutoStartDataDownload = false;

    public boolean shouldAutoStartDataDownload() {
        return shouldAutoStartDataDownload;
    }

    public void clearAutoStartFlag() {
        shouldAutoStartDataDownload = false;
    }

    public void requestAutoDataDownload() {
        shouldAutoStartDataDownload = true;
    }
}