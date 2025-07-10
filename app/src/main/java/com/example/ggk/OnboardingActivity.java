package com.example.ggk;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.util.ArrayList;
import java.util.List;

public class OnboardingActivity extends AppCompatActivity {

    private ViewPager2 viewPager;
    private TabLayout tabLayout;
    private Button btnNext;
    private Button btnSkip;
    private TextView btnGetStarted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Проверяем, показывали ли уже обучение
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        boolean onboardingCompleted = prefs.getBoolean("onboarding_completed", false);

        if (onboardingCompleted) {
            // Если обучение уже пройдено, переходим к главному экрану
            startMainActivity();
            return;
        }

        setContentView(R.layout.activity_onboarding);

        viewPager = findViewById(R.id.onboarding_view_pager);
        tabLayout = findViewById(R.id.onboarding_tab_layout);
        btnNext = findViewById(R.id.btn_next);
        btnSkip = findViewById(R.id.btn_skip);
        btnGetStarted = findViewById(R.id.btn_get_started);

        // Создаем список экранов обучения
        List<OnboardingItem> onboardingItems = new ArrayList<>();

        onboardingItems.add(new OnboardingItem(
                "Добро пожаловать в GGK",
                "Приложение для работы с манометрами МО по Bluetooth и анализа данных",
                R.drawable.ic_onboarding_welcome
        ));

        onboardingItems.add(new OnboardingItem(
                "Поиск устройств",
                "Найдите и подключитесь к вашему манометру по Bluetooth. Потяните вниз для обновления списка",
                R.drawable.ic_onboarding_search
        ));

        onboardingItems.add(new OnboardingItem(
                "Прием данных",
                "Получайте данные в реальном времени. Информация автоматически сохраняется для последующего анализа",
                R.drawable.ic_onboarding_data
        ));

        onboardingItems.add(new OnboardingItem(
                "Анализ на графике",
                "Просматривайте данные на интерактивном графике. Используйте фильтры для детального анализа",
                R.drawable.ic_onboarding_graph
        ));

        onboardingItems.add(new OnboardingItem(
                "Управление данными",
                "Переименовывайте устройства, удаляйте старые данные и используйте поиск для быстрого доступа",
                R.drawable.ic_onboarding_manage
        ));

        // Настраиваем адаптер
        OnboardingAdapter adapter = new OnboardingAdapter(onboardingItems);
        viewPager.setAdapter(adapter);

        // Настраиваем индикаторы
        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            // Пустая реализация - используем точки по умолчанию
        }).attach();

        // Обработчики кнопок
        btnNext.setOnClickListener(v -> {
            if (viewPager.getCurrentItem() < onboardingItems.size() - 1) {
                viewPager.setCurrentItem(viewPager.getCurrentItem() + 1);
            }
        });

        btnSkip.setOnClickListener(v -> finishOnboarding());

        btnGetStarted.setOnClickListener(v -> finishOnboarding());

        // Слушатель изменения страницы
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                if (position == onboardingItems.size() - 1) {
                    // Последняя страница
                    btnNext.setVisibility(View.GONE);
                    btnSkip.setVisibility(View.GONE);
                    btnGetStarted.setVisibility(View.VISIBLE);
                } else {
                    btnNext.setVisibility(View.VISIBLE);
                    btnSkip.setVisibility(View.VISIBLE);
                    btnGetStarted.setVisibility(View.GONE);
                }
            }
        });
    }

    private void finishOnboarding() {
        // Сохраняем флаг, что обучение пройдено
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        prefs.edit().putBoolean("onboarding_completed", true).apply();

        // Переходим к главному экрану
        startMainActivity();
    }

    private void startMainActivity() {
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        finish();
    }

    // Внутренний класс для элементов обучения
    public static class OnboardingItem {
        private final String title;
        private final String description;
        private final int imageResource;

        public OnboardingItem(String title, String description, int imageResource) {
            this.title = title;
            this.description = description;
            this.imageResource = imageResource;
        }

        public String getTitle() { return title; }
        public String getDescription() { return description; }
        public int getImageResource() { return imageResource; }
    }
}