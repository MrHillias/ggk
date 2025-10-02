package com.example.ggk;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MTDeviceDataHelper {
    private static final String TAG = "MTDeviceDataHelper";

    /**
     * Сохраняет информацию о MT устройстве
     */
    public static void saveMTDeviceInfo(Context context, String deviceName,
                                        String deviceAddress, String info) {
        try {
            File deviceFolder = new File(context.getFilesDir(), sanitizeFileName(deviceName));
            if (!deviceFolder.exists()) {
                deviceFolder.mkdirs();
            }

            // Сохраняем адрес устройства
            File addressFile = new File(deviceFolder, "device_address.txt");
            try (FileWriter writer = new FileWriter(addressFile)) {
                writer.write(deviceAddress);
            }

            // Сохраняем информацию об устройстве
            File infoFile = new File(deviceFolder, "mt_info.txt");
            try (FileWriter writer = new FileWriter(infoFile)) {
                writer.write(info);
            }

            // Добавляем маркер MT устройства
            File mtMarkerFile = new File(deviceFolder, "mt_device");
            mtMarkerFile.createNewFile();

            Log.d(TAG, "Saved MT device info for " + deviceName);
        } catch (IOException e) {
            Log.e(TAG, "Error saving MT device info", e);
        }
    }

    /**
     * Проверяет, является ли устройство MT
     */
    public static boolean isMTDevice(Context context, String deviceFolderName) {
        File deviceFolder = new File(context.getFilesDir(), sanitizeFileName(deviceFolderName));
        File mtMarkerFile = new File(deviceFolder, "mt_device");
        return mtMarkerFile.exists();
    }

    /**
     * Получает информацию о MT устройстве
     */
    public static String getMTDeviceInfo(Context context, String deviceFolderName) {
        try {
            File deviceFolder = new File(context.getFilesDir(), sanitizeFileName(deviceFolderName));
            File infoFile = new File(deviceFolder, "mt_info.txt");

            if (infoFile.exists()) {
                StringBuilder content = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new FileReader(infoFile))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        content.append(line).append("\n");
                    }
                }
                return content.toString();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error reading MT device info", e);
        }
        return null;
    }

    /**
     * Конвертирует данные MT устройства в массив double
     */
    public static double[] parseNumericData(String data) {
        String[] parts = data.split("\\s+");
        java.util.List<Double> values = new java.util.ArrayList<>();

        for (String part : parts) {
            try {
                double value = Double.parseDouble(part.trim());
                values.add(value);
            } catch (NumberFormatException e) {
                // Игнорируем нечисловые значения
            }
        }

        double[] result = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            result[i] = values.get(i);
        }
        return result;
    }

    private static String sanitizeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9.-]", "_");
    }

    /**
     * Сохраняет данные MT устройства в формате, совместимом с обычными устройствами
     * БЕЗ создания info.txt
     */
    public static void saveMTData(Context context, String deviceName,
                                  double[] values, long startTime) throws IOException {
        try {
            File deviceFolder = new File(context.getFilesDir(), sanitizeFileName(deviceName));
            if (!deviceFolder.exists()) {
                deviceFolder.mkdirs();
            }

            // Создаем маркер MT устройства
            File mtMarkerFile = new File(deviceFolder, "mt_device");
            if (!mtMarkerFile.exists()) {
                mtMarkerFile.createNewFile();
            }

            // Сохраняем в стандартный файл data.txt для совместимости с графиком
            File dataFile = new File(deviceFolder, "data.txt");
            SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault());

            try (FileWriter writer = new FileWriter(dataFile, true)) { // Append mode
                // Данные идут в обратном порядке (от новых к старым)
                for (int i = values.length - 1; i >= 0; i--) {
                    long timestamp = startTime - (i * 1000L); // Вычитаем секунды
                    writer.write(String.format(Locale.US, "%.2f;%s\n",
                            values[i], sdf.format(new Date(timestamp))));
                }
            }

            Log.d(TAG, "Saved " + values.length + " MT data points for " + deviceName);
        } catch (IOException e) {
            Log.e(TAG, "Error saving MT data", e);
            throw e; // Пробрасываем исключение для обработки в UI
        }
    }
}