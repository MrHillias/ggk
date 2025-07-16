package com.example.ggk;

import android.app.DatePickerDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Description;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.google.android.material.slider.RangeSlider;
import java.util.Collections;
import java.util.Comparator;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DataGraphFragment extends Fragment {
    private static final String TAG = "DataGraphFragment";

    private TextView yAxisUnitsTextView;
    private TextView xAxisLabelTextView;

    private String deviceFolderName;

    private PressureUnit currentUnit = PressureUnit.PA;
    private Button btnUnits;

    private LineChart lineChart;
    private View emptyView;

    private String deviceName;
    private List<DataPoint> allDataPoints = new ArrayList<>();
    private List<Entry> dataEntries = new ArrayList<>();
    private List<String> timeLabels = new ArrayList<>();

    // Фильтры
    private int startIndex = -1;  // Индекс начальной точки
    private int endIndex = -1;    // Индекс конечной точки
    private boolean extremeMode = false;
    private float minExtremeValue = 0;
    private float maxExtremeValue = 254;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Удаляем setHasOptionsMenu(true) так как используем кнопки

        DeviceActivity activity = (DeviceActivity) getActivity();
        if (activity != null) {
            deviceName = activity.getDeviceName();
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_data_graph, container, false);

        DeviceActivity activity = (DeviceActivity) getActivity();
        if (activity != null) {
            deviceName = activity.getDeviceName();
            deviceFolderName = activity.getDeviceFolderName();
        }

        lineChart = view.findViewById(R.id.line_chart);
        emptyView = view.findViewById(R.id.empty_view);

        // Находим элементы заголовка
        yAxisUnitsTextView = view.findViewById(R.id.y_axis_units);
        xAxisLabelTextView = view.findViewById(R.id.x_axis_label);

        // Находим кнопки
        View btnTimeRange = view.findViewById(R.id.btn_time_range);
        View btnDisplayMode = view.findViewById(R.id.btn_display_mode);
        btnUnits = view.findViewById(R.id.btn_units);

        // Устанавливаем обработчики кликов
        if (btnTimeRange != null) {
            btnTimeRange.setOnClickListener(v -> showTimeRangeDialog());
        }
        if (btnDisplayMode != null) {
            btnDisplayMode.setOnClickListener(v -> showDisplayModeDialog());
        }
        if (btnUnits != null) {
            btnUnits.setOnClickListener(v -> showUnitsDialog());
            updateUnitsButton();
        }

        setupChart();

        return view;
    }

    private void showUnitsDialog() {
        String[] units = new String[PressureUnit.values().length];
        int checkedItem = 0;

        for (int i = 0; i < PressureUnit.values().length; i++) {
            PressureUnit unit = PressureUnit.values()[i];
            units[i] = unit.getDisplayName();
            if (unit == currentUnit) {
                checkedItem = i;
            }
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Выберите единицу измерения")
                .setSingleChoiceItems(units, checkedItem, (dialog, which) -> {
                    currentUnit = PressureUnit.values()[which];
                    updateUnitsButton();
                    applyFiltersAndRefresh();
                    dialog.dismiss();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void updateUnitsButton() {
        if (btnUnits != null) {
            btnUnits.setText(currentUnit.getDisplayName());
        }
        if (yAxisUnitsTextView != null) {
            yAxisUnitsTextView.setText(currentUnit.getDisplayName());
        }
    }

    private void showTimeRangeDialog() {
        if (allDataPoints.isEmpty()) {
            // Сначала загружаем все данные
            loadAllDataPoints();
            if (allDataPoints.isEmpty()) {
                Toast.makeText(requireContext(), "Нет данных для фильтрации", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_time_range_slider, null);
        TextView availableRangeText = dialogView.findViewById(R.id.available_range_text);
        TextView startTimeText = dialogView.findViewById(R.id.start_time_text);
        TextView endTimeText = dialogView.findViewById(R.id.end_time_text);
        TextView selectedPointsText = dialogView.findViewById(R.id.selected_points_text);
        RangeSlider rangeSlider = dialogView.findViewById(R.id.time_range_slider);

        // НЕ сортируем данные - используем порядок из файла

        // Настраиваем слайдер
        rangeSlider.setValueFrom(0);
        rangeSlider.setValueTo(allDataPoints.size() - 1);

        // Устанавливаем начальные значения
        if (startIndex == -1 || endIndex == -1) {
            rangeSlider.setValues(0f, (float)(allDataPoints.size() - 1));
            startIndex = 0;
            endIndex = allDataPoints.size() - 1;
        } else {
            rangeSlider.setValues((float)startIndex, (float)endIndex);
        }

        // Отображаем доступный диапазон с реальным временем из файла
        String firstTime = allDataPoints.get(0).timeString;
        String lastTime = allDataPoints.get(allDataPoints.size() - 1).timeString;

        // Добавляем информацию о количестве точек
        availableRangeText.setText(String.format("%s — %s\n(%d точек)",
                firstTime, lastTime, allDataPoints.size()));

        // Обновляем отображение при изменении слайдера
        RangeSlider.OnChangeListener sliderListener = new RangeSlider.OnChangeListener() {
            @Override
            public void onValueChange(@NonNull RangeSlider slider, float value, boolean fromUser) {
                List<Float> values = slider.getValues();
                int start = Math.round(values.get(0));
                int end = Math.round(values.get(1));

                // Показываем реальное время из файла
                startTimeText.setText(allDataPoints.get(start).timeString);
                endTimeText.setText(allDataPoints.get(end).timeString);

                int selectedCount = end - start + 1;
                selectedPointsText.setText("Выбрано точек: " + selectedCount);
            }
        };

        rangeSlider.addOnChangeListener(sliderListener);

        // Инициализируем отображение
        sliderListener.onValueChange(rangeSlider, 0, false);

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Выберите временной диапазон")
                .setView(dialogView)
                .setPositiveButton("Применить", (d, which) -> {
                    List<Float> values = rangeSlider.getValues();
                    startIndex = Math.round(values.get(0));
                    endIndex = Math.round(values.get(1));
                    applyFiltersAndRefresh();
                })
                .setNegativeButton("Отмена", null)
                .setNeutralButton("Сбросить", (d, which) -> {
                    startIndex = -1;
                    endIndex = -1;
                    applyFiltersAndRefresh();
                })
                .create();

        dialog.show();
    }
    private void showDisplayModeDialog() {
        String[] modes = {"Стандартный режим", "Режим экстремумов"};
        int checkedItem = extremeMode ? 1 : 0;

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Режим отображения")
                .setSingleChoiceItems(modes, checkedItem, (dialog, which) -> {
                    if (which == 1) {
                        // Показываем диалог для ввода диапазона
                        dialog.dismiss();
                        showExtremeRangeDialog();
                    } else {
                        extremeMode = false;
                        applyFiltersAndRefresh();
                        dialog.dismiss();
                    }
                })
                .show();
    }

    private void showExtremeRangeDialog() {
        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);

        final EditText minInput = new EditText(requireContext());
        minInput.setHint("Минимальное значение (" + currentUnit.getDisplayName() + ")");
        minInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);

        // Конвертируем текущие значения в выбранные единицы
        float minInCurrentUnit = (float) currentUnit.convertFromPa(minExtremeValue);
        float maxInCurrentUnit = (float) currentUnit.convertFromPa(maxExtremeValue);

        minInput.setText(String.valueOf(minInCurrentUnit));
        layout.addView(minInput);

        final EditText maxInput = new EditText(requireContext());
        maxInput.setHint("Максимальное значение (" + currentUnit.getDisplayName() + ")");
        maxInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        maxInput.setText(String.valueOf(maxInCurrentUnit));
        layout.addView(maxInput);

        TextView helpText = new TextView(requireContext());
        helpText.setText("\nБудут показаны только значения ВНЕ указанного диапазона");
        helpText.setTextSize(12);
        helpText.setPadding(0, 20, 0, 0);
        layout.addView(helpText);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Диапазон для исключения")
                .setView(layout)
                .setPositiveButton("Применить", (dialog, which) -> {
                    try {
                        float minValue = Float.parseFloat(minInput.getText().toString());
                        float maxValue = Float.parseFloat(maxInput.getText().toString());

                        // Конвертируем обратно в Па для хранения
                        minExtremeValue = (float) currentUnit.convertToPa(minValue);
                        maxExtremeValue = (float) currentUnit.convertToPa(maxValue);

                        extremeMode = true;
                        applyFiltersAndRefresh();
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "Ошибка парсинга чисел", e);
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void applyFiltersAndRefresh() {
        loadAndDisplayGraph();
    }

    @Override
    public void onResume() {
        super.onResume();

        // Принудительно обновляем график при возврате к фрагменту
        if (lineChart != null) {
            lineChart.post(() -> {
                loadAndDisplayGraph();
            });
        }
    }

    private void setupChart() {
        lineChart.setTouchEnabled(true);
        lineChart.setDragEnabled(true);
        lineChart.setScaleEnabled(true);
        lineChart.setPinchZoom(true);
        lineChart.setDrawGridBackground(false);

        // ВАЖНО: НЕ используем setViewPortOffsets - это обрезает метки!
        // Вместо этого используем стандартные отступы графика

        // Базовые отступы - будут адаптироваться в displayChart()
        lineChart.setExtraBottomOffset(15f);   // Для меток времени
        lineChart.setExtraLeftOffset(20f);     // Увеличено для научной нотации
        lineChart.setExtraRightOffset(15f);    // Отступ справа
        lineChart.setExtraTopOffset(10f);      // Отступ сверху

        // Отключаем стандартное описание
        Description description = new Description();
        description.setEnabled(false);
        lineChart.setDescription(description);

        XAxis xAxis = lineChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(true);
        xAxis.setGranularity(1f);
        xAxis.setDrawLabels(true);
        xAxis.setYOffset(5f); // Небольшой отступ от оси
        xAxis.setTextSize(10f); // Размер текста

        YAxis leftAxis = lineChart.getAxisLeft();
        leftAxis.setDrawGridLines(true);
        leftAxis.setDrawLabels(true);
        leftAxis.setGranularityEnabled(false); // Отключаем, будем управлять в displayChart
        leftAxis.setXOffset(15f); // Увеличенный отступ для научной нотации
        leftAxis.setTextSize(10f); // Размер текста
        leftAxis.setDrawAxisLine(true);
        leftAxis.setAxisLineWidth(1f);
        leftAxis.setPosition(YAxis.YAxisLabelPosition.OUTSIDE_CHART);

        // Убеждаемся, что есть место для меток
        leftAxis.setSpaceTop(15f);
        leftAxis.setSpaceBottom(15f);

        // Минимальная и максимальная ширина для меток
        leftAxis.setMinWidth(40f);  // Увеличено для научной нотации
        leftAxis.setMaxWidth(60f);  // Увеличено для научной нотации

        YAxis rightAxis = lineChart.getAxisRight();
        rightAxis.setEnabled(false);

        // Включаем легенду (опционально)
        lineChart.getLegend().setEnabled(false);
    }


    public void loadAndDisplayGraph() {
        // Если данные еще не загружены, загружаем
        if (allDataPoints.isEmpty()) {
            loadAllDataPoints();
        }

        dataEntries.clear();
        timeLabels.clear();

        if (allDataPoints.isEmpty()) {
            showEmptyView();
            return;
        }

        // НЕ сортируем данные, сохраняем порядок из файла
        // Это важно, так как данные уже идут в правильном порядке

        // Определяем диапазон для отображения
        int displayStart = (startIndex >= 0) ? startIndex : 0;
        int displayEnd = (endIndex >= 0 && endIndex < allDataPoints.size()) ? endIndex : allDataPoints.size() - 1;

        // Применяем фильтры
        int index = 0;
        for (int i = displayStart; i <= displayEnd; i++) {
            DataPoint point = allDataPoints.get(i);

            // Проверяем режим экстремумов
            if (extremeMode) {
                if (point.value >= minExtremeValue && point.value <= maxExtremeValue) {
                    continue; // Пропускаем значения внутри диапазона
                }
            }

            float convertedValue = (float) currentUnit.convertFromPa(point.value);
            dataEntries.add(new Entry(index, convertedValue));
            timeLabels.add(point.timeString);
            index++;
        }

        if (dataEntries.isEmpty()) {
            showEmptyView();
            return;
        }

        displayChart();
    }

    private void displayChart() {
        lineChart.setVisibility(View.VISIBLE);
        emptyView.setVisibility(View.GONE);

        LineDataSet dataSet = new LineDataSet(dataEntries, "");

        // Настройка цвета в зависимости от режима
        if (extremeMode) {
            dataSet.setColor(Color.RED);
            dataSet.setCircleColor(Color.RED);
            dataSet.setFillColor(Color.parseColor("#FFCDD2"));
        } else {
            dataSet.setColor(getResources().getColor(R.color.bluetooth_connected));
            dataSet.setCircleColor(getResources().getColor(R.color.bluetooth_connected));
            dataSet.setFillColor(getResources().getColor(R.color.bluetooth_scanning));
        }

        dataSet.setLineWidth(3f);
        dataSet.setCircleRadius(4f);
        dataSet.setDrawCircleHole(true);
        dataSet.setCircleHoleRadius(2f);
        dataSet.setDrawValues(false);
        dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        dataSet.setCubicIntensity(0.2f);
        dataSet.setDrawFilled(true);
        dataSet.setFillAlpha(50);

        LineData lineData = new LineData(dataSet);
        lineChart.setData(lineData);

        // Настраиваем ось X с видимыми метками
        XAxis xAxis = lineChart.getXAxis();
        xAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                int index = (int) value;
                if (index >= 0 && index < timeLabels.size()) {
                    String fullDateTime = timeLabels.get(index);

                    if (fullDateTime.contains(" ")) {
                        // Показываем только время HH:mm
                        String time = fullDateTime.split(" ")[1];
                        if (time.length() >= 5) {
                            return time.substring(0, 5);
                        }
                    } else if (fullDateTime.contains(":") && fullDateTime.length() >= 5) {
                        return fullDateTime.substring(0, 5);
                    }
                    return fullDateTime;
                }
                return "";
            }
        });

        // Оптимальное количество меток для видимости
        int labelCount = Math.min(6, Math.max(4, timeLabels.size() / 10));
        xAxis.setLabelCount(labelCount, false);
        xAxis.setLabelRotationAngle(0); // Без поворота для лучшей читаемости
        xAxis.setTextColor(getResources().getColor(R.color.black));
        xAxis.setGridColor(Color.LTGRAY);
        xAxis.setAxisLineColor(getResources().getColor(R.color.black));
        xAxis.setGranularity(1f);
        xAxis.setAvoidFirstLastClipping(true);
        xAxis.setTextSize(10f); // Размер для читаемости
        xAxis.setCenterAxisLabels(false);
        xAxis.setDrawLabels(true); // Убеждаемся, что метки рисуются

        YAxis leftAxis = lineChart.getAxisLeft();
        leftAxis.setTextColor(getResources().getColor(R.color.black));
        leftAxis.setGridColor(Color.LTGRAY);
        leftAxis.setAxisLineColor(getResources().getColor(R.color.black));
        leftAxis.setDrawLabels(true); // ВАЖНО: включаем отображение меток
        leftAxis.setEnabled(true); // ВАЖНО: включаем ось
        leftAxis.setDrawAxisLine(true);
        leftAxis.setDrawGridLines(true);
        leftAxis.setPosition(YAxis.YAxisLabelPosition.OUTSIDE_CHART);
        leftAxis.setSpaceTop(15f);
        leftAxis.setSpaceBottom(15f);

        // СНАЧАЛА определяем диапазон значений
        float minValue = Float.MAX_VALUE;
        float maxValue = Float.MIN_VALUE;
        for (Entry entry : dataEntries) {
            minValue = Math.min(minValue, entry.getY());
            maxValue = Math.max(maxValue, entry.getY());
        }

        // ТЕПЕРЬ вычисляем range
        float range = maxValue - minValue;

        // Адаптивное пространство для меток и размер текста
        if (range < 0.001 || maxValue < 0.001) {
            // Для очень маленьких значений - научная нотация
            lineChart.setExtraLeftOffset(35f);  // Больше места для научной нотации (например, 1.0e-3)
            leftAxis.setXOffset(15f);
            leftAxis.setTextSize(9f);
        } else if (range < 0.1 || maxValue < 0.1) {
            // Для маленьких значений как PSI (0.02) - компактный формат
            lineChart.setExtraLeftOffset(20f);  // Умеренное пространство
            leftAxis.setXOffset(10f);
            leftAxis.setTextSize(9f);
        } else if (range < 10) {
            // Для средних значений
            lineChart.setExtraLeftOffset(15f);
            leftAxis.setXOffset(8f);
            leftAxis.setTextSize(10f);
        } else {
            // Для больших значений
            lineChart.setExtraLeftOffset(10f);
            leftAxis.setXOffset(5f);
            leftAxis.setTextSize(10f);
        }

        // Интеллектуальный форматтер для оси Y - упрощенный для компактности
        leftAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                // Для нуля
                if (Math.abs(value) < 0.0000001) {
                    return "0";
                }
                // Для очень маленьких значений - научная нотация
                else if (Math.abs(value) < 0.001) {
                    return String.format(Locale.US, "%.0e", value); // Сокращенная научная нотация
                }
                // Для маленьких значений типа PSI (0.001 - 0.1)
                else if (Math.abs(value) < 0.01) {
                    return String.format(Locale.US, "%.3f", value); // Максимум 3 знака
                }
                else if (Math.abs(value) < 0.1) {
                    return String.format(Locale.US, "%.2f", value); // Максимум 2 знака
                }
                // Для значений 0.1 - 1
                else if (Math.abs(value) < 1) {
                    return String.format(Locale.US, "%.1f", value);
                }
                // Для значений 1 - 10
                else if (Math.abs(value) < 10) {
                    // Если целое число - без дробной части
                    if (value == (int) value) {
                        return String.format(Locale.US, "%d", (int) value);
                    } else {
                        return String.format(Locale.US, "%.1f", value);
                    }
                }
                // Для больших значений
                else if (Math.abs(value) < 1000) {
                    return String.format(Locale.US, "%.0f", value);
                }
                // Для очень больших значений
                else {
                    return String.format(Locale.US, "%.0e", value);
                }
            }
        });

        // Адаптивное количество меток на оси Y
        int yLabelCount;
        if (range < 0.001) {
            yLabelCount = 3; // Минимум меток для очень маленьких диапазонов
        } else if (range < 0.01) {
            yLabelCount = 4;
        } else if (range < 0.1) {
            yLabelCount = 5;
        } else {
            yLabelCount = 5;
        }

        leftAxis.setLabelCount(yLabelCount, false);

        // Настройка диапазона с адаптивными отступами
        if (range > 0) {
            // Адаптивный отступ
            float paddingPercent;
            if (range < 0.001) {
                paddingPercent = 0.5f; // 50% для очень маленьких
            } else if (range < 0.01) {
                paddingPercent = 0.3f; // 30% для маленьких
            } else if (range < 0.1) {
                paddingPercent = 0.2f; // 20% для средне-маленьких
            } else {
                paddingPercent = 0.1f; // 10% для обычных
            }

            float padding = range * paddingPercent;

            leftAxis.setAxisMinimum(minValue - padding);
            leftAxis.setAxisMaximum(maxValue + padding);
        } else {
            // Если все значения одинаковые
            if (Math.abs(minValue) < 0.001) {
                leftAxis.setAxisMinimum(minValue - 0.001f);
                leftAxis.setAxisMaximum(maxValue + 0.001f);
            } else if (Math.abs(minValue) < 0.1) {
                leftAxis.setAxisMinimum(minValue * 0.8f);
                leftAxis.setAxisMaximum(maxValue * 1.2f);
            } else if (Math.abs(minValue) < 1) {
                leftAxis.setAxisMinimum(minValue * 0.5f);
                leftAxis.setAxisMaximum(maxValue * 1.5f);
            } else {
                leftAxis.setAxisMinimum(minValue - 1);
                leftAxis.setAxisMaximum(maxValue + 1);
            }
        }

        // Гранулярность для разных диапазонов
        if (range < 0.001) {
            leftAxis.setGranularityEnabled(false);
        } else if (range < 0.01) {
            leftAxis.setGranularityEnabled(true);
            leftAxis.setGranularity(0.001f);
        } else if (range < 0.1) {
            leftAxis.setGranularityEnabled(true);
            leftAxis.setGranularity(0.01f);
        } else {
            leftAxis.setGranularityEnabled(true);
            leftAxis.setGranularity(0.1f);
        }

        // Убеждаемся, что оси рисуются
        leftAxis.setEnabled(true);
        xAxis.setEnabled(true);

        lineChart.getLegend().setEnabled(false);
        lineChart.invalidate();
        lineChart.animateX(1000);

        updateStatistics();
    }

    private void updateStatistics() {
        if (dataEntries.isEmpty()) return;

        TextView dataPointsValue = getView().findViewById(R.id.data_points_value);
        TextView averageValue = getView().findViewById(R.id.average_value);
        TextView rangeValue = getView().findViewById(R.id.range_value);

        dataPointsValue.setText(String.valueOf(dataEntries.size()));

        float sum = 0;
        float min = Float.MAX_VALUE;
        float max = Float.MIN_VALUE;

        for (Entry entry : dataEntries) {
            float value = entry.getY();
            sum += value;
            min = Math.min(min, value);
            max = Math.max(max, value);
        }

        float average = sum / dataEntries.size();

        // Компактное форматирование
        String avgText = formatCompactValue(average);
        String minText = formatCompactValue(min);
        String maxText = formatCompactValue(max);

        averageValue.setText(avgText);

        // Супер-компактный формат для диапазона
        String rangeText;
        if (minText.contains("e") || maxText.contains("e")) {
            // Если есть научная нотация, показываем вертикально
            rangeText = minText + "\n—\n" + maxText;
        } else {
            // Обычные числа - пробуем в одну строку
            rangeText = minText + "—" + maxText;
        }

        rangeValue.setText(rangeText);
        rangeValue.setGravity(android.view.Gravity.CENTER);
        rangeValue.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f); // Уменьшаем размер
    }

    private String formatCompactValue(float value) {
        if (Math.abs(value) == 0) {
            return "0";
        } else if (Math.abs(value) < 0.001) {
            // Супер-короткая научная нотация
            return String.format(Locale.US, "%.0e", value).replace("e+0", "e").replace("e-0", "e-");
        } else if (Math.abs(value) < 0.01) {
            return String.format(Locale.US, "%.3f", value);
        } else if (Math.abs(value) < 0.1) {
            return String.format(Locale.US, "%.2f", value);
        } else if (Math.abs(value) < 10) {
            return String.format(Locale.US, "%.1f", value);
        } else if (Math.abs(value) < 1000) {
            return String.format(Locale.US, "%.0f", value);
        } else {
            // Для больших чисел используем сокращения
            if (value >= 1000000) {
                return String.format(Locale.US, "%.1fM", value / 1000000);
            } else if (value >= 1000) {
                return String.format(Locale.US, "%.1fK", value / 1000);
            }
            return String.format(Locale.US, "%.0f", value);
        }
    }

    private void loadAllDataPoints() {
        allDataPoints.clear();

        try {
            File deviceFolder = new File(requireContext().getFilesDir(), sanitizeFileName(deviceFolderName));
            File dataFile = new File(deviceFolder, "data.txt");

            if (!dataFile.exists()) {
                return;
            }

            SimpleDateFormat fullDateFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault());
            SimpleDateFormat timeOnlyFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

            // Базовая дата для времени, которое переходит через полночь
            Calendar baseDate = Calendar.getInstance();
            baseDate.set(2024, 0, 1, 0, 0, 0); // 1 января 2024, 00:00:00
            baseDate.set(Calendar.MILLISECOND, 0);

            Date previousDate = null;
            boolean crossedMidnight = false;

            try (BufferedReader reader = new BufferedReader(new FileReader(dataFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(";");
                    if (parts.length == 2) {
                        try {
                            float value = Float.parseFloat(parts[0]);
                            Date date = null;
                            String timeString = parts[1].trim();

                            try {
                                // Пробуем полный формат
                                date = fullDateFormat.parse(timeString);
                            } catch (ParseException e1) {
                                try {
                                    // Парсим только время
                                    Date timeOnly = timeOnlyFormat.parse(timeString);
                                    Calendar timeCal = Calendar.getInstance();
                                    timeCal.setTime(timeOnly);

                                    // Используем базовую дату
                                    Calendar resultCal = (Calendar) baseDate.clone();
                                    resultCal.set(Calendar.HOUR_OF_DAY, timeCal.get(Calendar.HOUR_OF_DAY));
                                    resultCal.set(Calendar.MINUTE, timeCal.get(Calendar.MINUTE));
                                    resultCal.set(Calendar.SECOND, timeCal.get(Calendar.SECOND));

                                    // Проверяем переход через полночь
                                    if (previousDate != null && !crossedMidnight) {
                                        Calendar prevCal = Calendar.getInstance();
                                        prevCal.setTime(previousDate);

                                        // Если текущее время меньше предыдущего, значит перешли через полночь
                                        if (timeCal.get(Calendar.HOUR_OF_DAY) < prevCal.get(Calendar.HOUR_OF_DAY)) {
                                            crossedMidnight = true;
                                            resultCal.add(Calendar.DAY_OF_MONTH, 1);
                                        }
                                    } else if (crossedMidnight) {
                                        // Если уже перешли через полночь, добавляем день
                                        resultCal.add(Calendar.DAY_OF_MONTH, 1);
                                    }

                                    date = resultCal.getTime();
                                    previousDate = date;

                                } catch (ParseException e2) {
                                    Log.e(TAG, "Не удалось распарсить время: " + timeString);
                                    continue;
                                }
                            }

                            if (date != null) {
                                allDataPoints.add(new DataPoint(value, date, timeString));
                            }
                        } catch (NumberFormatException e) {
                            Log.e(TAG, "Ошибка парсинга числа: " + parts[0]);
                        }
                    }
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Ошибка загрузки данных", e);
        }
    }

    private void showEmptyView() {
        lineChart.setVisibility(View.GONE);
        emptyView.setVisibility(View.VISIBLE);

        TextView emptyText = emptyView.findViewById(R.id.empty_text);
        if (emptyText != null) {
            if (extremeMode && dataEntries.isEmpty() && !allDataPoints.isEmpty()) {
                emptyText.setText("Нет данных вне указанного диапазона");
            }
            //else if (startDate != null || endDate != null) {
            //    emptyText.setText("Нет данных в выбранном временном диапазоне");
            //}
            else {
                emptyText.setText("Нет данных для отображения");
            }
        }
    }

    private String formatDateTimeForDisplay(DataPoint point) {
        SimpleDateFormat displayFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault());
        return displayFormat.format(point.date);
    }

    public boolean hasDataForGraph() {
        try {
            File deviceFolder = new File(requireContext().getFilesDir(), sanitizeFileName(deviceFolderName));
            File dataFile = new File(deviceFolder, "data.txt");
            return dataFile.exists() && dataFile.length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String sanitizeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9.-]", "_");
    }

    // Внутренний класс для хранения точек данных
    private static class DataPoint {
        float value;
        Date date;
        String timeString;

        DataPoint(float value, Date date, String timeString) {
            this.value = value;
            this.date = date;
            this.timeString = timeString;
        }
    }
}