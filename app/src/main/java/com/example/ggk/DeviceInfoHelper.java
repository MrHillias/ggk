package com.example.ggk;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.BufferedReader;
import java.io.IOException;

public class DeviceInfoHelper {
    private static final String TAG = "DeviceInfoHelper";
    private static final String DEVICE_ADDRESS_FILE = "device_address.txt";

    /**
     * Сохраняет адрес устройства в папку устройства
     */
    public static void saveDeviceAddress(Context context, String deviceName, String deviceAddress) {
        try {
            File deviceFolder = new File(context.getFilesDir(), sanitizeFileName(deviceName));
            if (!deviceFolder.exists()) {
                deviceFolder.mkdirs();
            }

            File addressFile = new File(deviceFolder, DEVICE_ADDRESS_FILE);
            try (FileWriter writer = new FileWriter(addressFile)) {
                writer.write(deviceAddress);
            }

            Log.d(TAG, "Saved device address for " + deviceName + ": " + deviceAddress);
        } catch (IOException e) {
            Log.e(TAG, "Error saving device address", e);
        }
    }

    /**
     * Получает адрес устройства из папки устройства
     */
    public static String getDeviceAddress(Context context, String deviceName) {
        try {
            File deviceFolder = new File(context.getFilesDir(), sanitizeFileName(deviceName));
            File addressFile = new File(deviceFolder, DEVICE_ADDRESS_FILE);

            if (addressFile.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(addressFile))) {
                    return reader.readLine();
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Error reading device address", e);
        }

        // Если не удалось получить адрес, возвращаем имя устройства как заглушку
        return deviceName;
    }

    /**
     * Проверяет, есть ли сохраненные данные для устройства
     */
    public static boolean hasDeviceData(Context context, String deviceName) {
        File deviceFolder = new File(context.getFilesDir(), sanitizeFileName(deviceName));
        File infoFile = new File(deviceFolder, "info.txt");
        File dataFile = new File(deviceFolder, "data.txt");
        return deviceFolder.exists() && infoFile.exists() && dataFile.exists();
    }

    /**
     * Очищает имя файла от недопустимых символов
     */
    public static String sanitizeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9.-]", "_");
    }
}