package com.example.ggk;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class DeviceDatabase extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "bluetooth_devices.db";
    private static final int DATABASE_VERSION = 1;

    // Таблица устройств
    private static final String TABLE_DEVICES = "devices";
    private static final String COLUMN_ADDRESS = "address";
    private static final String COLUMN_NAME = "name";
    private static final String COLUMN_CUSTOM_NAME = "custom_name";
    private static final String COLUMN_LAST_SEEN = "last_seen";
    private static final String COLUMN_IS_FAVORITE = "is_favorite";

    private static DeviceDatabase instance;

    public static synchronized DeviceDatabase getInstance(Context context) {
        if (instance == null) {
            instance = new DeviceDatabase(context.getApplicationContext());
        }
        return instance;
    }

    private DeviceDatabase(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createTable = "CREATE TABLE " + TABLE_DEVICES + " (" +
                COLUMN_ADDRESS + " TEXT PRIMARY KEY, " +
                COLUMN_NAME + " TEXT, " +
                COLUMN_CUSTOM_NAME + " TEXT, " +
                COLUMN_LAST_SEEN + " INTEGER, " +
                COLUMN_IS_FAVORITE + " INTEGER DEFAULT 0)";
        db.execSQL(createTable);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_DEVICES);
        onCreate(db);
    }

    // Добавить или обновить устройство
    public void updateDevice(String address, String name, long lastSeen) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_ADDRESS, address);
        values.put(COLUMN_NAME, name);
        values.put(COLUMN_LAST_SEEN, lastSeen);

        // Проверяем, существует ли устройство
        Cursor cursor = db.query(TABLE_DEVICES, new String[]{COLUMN_ADDRESS},
                COLUMN_ADDRESS + "=?", new String[]{address}, null, null, null);

        if (cursor.moveToFirst()) {
            // Обновляем существующее устройство, сохраняя custom_name
            db.update(TABLE_DEVICES, values, COLUMN_ADDRESS + "=?", new String[]{address});
        } else {
            // Добавляем новое устройство
            db.insert(TABLE_DEVICES, null, values);
        }
        cursor.close();
    }

    // Переименовать устройство
    public void renameDevice(String address, String customName) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_CUSTOM_NAME, customName);
        db.update(TABLE_DEVICES, values, COLUMN_ADDRESS + "=?", new String[]{address});
    }

    // Удалить устройство
    public void deleteDevice(String address) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_DEVICES, COLUMN_ADDRESS + "=?", new String[]{address});
    }

    // Получить информацию об устройстве
    @SuppressLint("Range")
    public DeviceInfo getDeviceInfo(String address) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_DEVICES, null,
                COLUMN_ADDRESS + "=?", new String[]{address}, null, null, null);

        DeviceInfo info = null;
        if (cursor.moveToFirst()) {
            info = new DeviceInfo();
            info.address = cursor.getString(cursor.getColumnIndex(COLUMN_ADDRESS));
            info.name = cursor.getString(cursor.getColumnIndex(COLUMN_NAME));
            info.customName = cursor.getString(cursor.getColumnIndex(COLUMN_CUSTOM_NAME));
            info.lastSeen = cursor.getLong(cursor.getColumnIndex(COLUMN_LAST_SEEN));
            info.isFavorite = cursor.getInt(cursor.getColumnIndex(COLUMN_IS_FAVORITE)) == 1;
        }
        cursor.close();
        return info;
    }

    // Получить все устройства
    public List<DeviceInfo> getAllDevices() {
        List<DeviceInfo> devices = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_DEVICES, null, null, null, null, null,
                COLUMN_LAST_SEEN + " DESC");

        while (cursor.moveToNext()) {
            DeviceInfo info = new DeviceInfo();
            info.address = cursor.getString(cursor.getColumnIndex(COLUMN_ADDRESS));
            info.name = cursor.getString(cursor.getColumnIndex(COLUMN_NAME));
            info.customName = cursor.getString(cursor.getColumnIndex(COLUMN_CUSTOM_NAME));
            info.lastSeen = cursor.getLong(cursor.getColumnIndex(COLUMN_LAST_SEEN));
            info.isFavorite = cursor.getInt(cursor.getColumnIndex(COLUMN_IS_FAVORITE)) == 1;
            devices.add(info);
        }
        cursor.close();
        return devices;
    }

    // Установить/снять избранное
    public void setFavorite(String address, boolean isFavorite) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_IS_FAVORITE, isFavorite ? 1 : 0);
        db.update(TABLE_DEVICES, values, COLUMN_ADDRESS + "=?", new String[]{address});
    }

    // Класс для хранения информации об устройстве
    public static class DeviceInfo {
        public String address;
        public String name;
        public String customName;
        public long lastSeen;
        public boolean isFavorite;

        public String getDisplayName() {
            return customName != null && !customName.isEmpty() ? customName : name;
        }
    }
}