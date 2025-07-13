package com.example.ggk;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@SuppressLint("MissingPermission")
public class BluetoothService {
    private static final String TAG = "BluetoothService";
    private static final long CONNECTION_TIMEOUT = 15000;
    private static final int BUFFER_SIZE_THRESHOLD = 8192; // Увеличиваем до 8KB
    private static final int MAX_DATA_DISPLAY_SIZE = 16384;
    private static final String DATA_TAG = "BLE_DATA";
    private static final int MAX_RECONNECT_ATTEMPTS = 3;
    private static final long RECONNECT_DELAY = 2000;
    private static final long BUFFER_PROCESS_INTERVAL = 50; // Ускоряем обработку до 50мс

    // Большой буфер для приема данных - 1MB
    private static final int MAX_RECEPTION_BUFFER_SIZE = 1024 * 1024; // 1MB
    private static final long UI_UPDATE_INTERVAL = 500; // Обновляем UI только раз в 500мс

    // Параметры для контроля потока данных
    private static final int MAX_PENDING_NOTIFICATIONS = 10;
    private static final long NOTIFICATION_TIMEOUT = 5000;

    private final Context context;
    private final BluetoothAdapter bluetoothAdapter;
    private volatile BluetoothGatt bluetoothGatt;
    private BluetoothCallback callback;
    private final Handler mainHandler;
    private final Handler backgroundHandler;
    private final ExecutorService dataProcessingExecutor;

    // Атомарные переменные для thread-safe операций
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicBoolean isConnecting = new AtomicBoolean(false);
    private final AtomicBoolean saveToFile = new AtomicBoolean(false);
    private final AtomicBoolean asciiMode = new AtomicBoolean(true); // По умолчанию ASCII режим
    private final AtomicBoolean numericMode = new AtomicBoolean(false); // Режим только чисел после '\r\n'
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);

    private volatile FileOutputStream fileOutputStream;
    private volatile File outputFile;

    // Буфер для отслеживания окончания последовательности '\r\n'
    private final StringBuilder sequenceBuffer = new StringBuilder();

    // Буфер для отслеживания последовательности "End\r\n" в числовом режиме
    private final java.util.List<Integer> numericBuffer = new java.util.ArrayList<>();

    // Буфер для накопления данных - используем большой буфер
    private final Queue<byte[]> dataBuffer = new ConcurrentLinkedQueue<>();
    private final AtomicInteger bufferedBytesCount = new AtomicInteger(0);

    // Большой буфер для быстрого приема данных (1MB)
    private final java.io.ByteArrayOutputStream receptionBuffer = new java.io.ByteArrayOutputStream(MAX_RECEPTION_BUFFER_SIZE);
    private volatile long lastUIUpdateTime = 0;
    private volatile boolean receptionComplete = false;

    // Статистика - используем AtomicLong для thread-safety
    private final AtomicLong totalBytesReceived = new AtomicLong(0);
    private final AtomicLong totalPacketsReceived = new AtomicLong(0);
    private final AtomicLong droppedPackets = new AtomicLong(0);
    private volatile long startReceivingTime = 0;
    private volatile long lastDataReceivedTime = 0;
    private volatile int lastSequenceNumber = -1;

    // Для уведомления о статусе соединения
    private final AtomicBoolean servicesDiscovered = new AtomicBoolean(false);
    private final AtomicBoolean notificationsEnabled = new AtomicBoolean(false);

    // UUID для подключения
    private volatile UUID currentServiceUuid;
    private volatile UUID currentCharacteristicUuid;
    private volatile String currentDeviceAddress;

    public void setSyncMode(boolean b, long lastSyncTime) {
    }

    public interface BluetoothCallback {
        void onConnectionStateChange(boolean connected);
        void onServicesDiscovered(boolean success);
        void onDataReceived(byte[] data, String formattedData);
        void onDataBatch(String formattedBatch, long totalBytes, double kbPerSecond);
        void onError(String message);
        void onReconnectAttempt(int attempt, int maxAttempts);
    }

    public BluetoothService(Context context) {
        this.context = context;
        this.mainHandler = new Handler(Looper.getMainLooper());

        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();

        // Создаем background handler для операций, не связанных с UI
        this.backgroundHandler = new Handler(Looper.getMainLooper());

        // Используем отдельный пул потоков для обработки данных
        dataProcessingExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "BluetoothDataProcessor");
            t.setDaemon(true);
            return t;
        });

        // Запускаем периодическую обработку буфера
        scheduleBufferProcessing();
    }

    private void scheduleBufferProcessing() {
        backgroundHandler.postDelayed(bufferProcessRunnable, BUFFER_PROCESS_INTERVAL);
    }

    // Runnable для периодической обработки буфера
    private final Runnable bufferProcessRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                if (isConnected.get() && bufferedBytesCount.get() > 0) {
                    processDataBuffer();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in buffer processing", e);
            } finally {
                // Перепланируем выполнение только если сервис активен
                if (isConnected.get() || isConnecting.get()) {
                    backgroundHandler.postDelayed(this, BUFFER_PROCESS_INTERVAL);
                }
            }
        }
    };

    // Обработка накопленных данных
    private void processDataBuffer() {
        dataProcessingExecutor.execute(() -> {
            try {
                List<byte[]> dataChunks = new ArrayList<>();
                int totalSize = 0;

                // Извлекаем все данные из очереди
                byte[] chunk;
                while ((chunk = dataBuffer.poll()) != null) {
                    dataChunks.add(chunk);
                    totalSize += chunk.length;
                }
                bufferedBytesCount.set(0);

                if (totalSize == 0) return;

                // Объединяем все чанки в один массив
                ByteBuffer combinedBuffer = ByteBuffer.allocate(totalSize);
                for (byte[] dataChunk : dataChunks) {
                    combinedBuffer.put(dataChunk);

                    // Если включено сохранение в файл
                    if (saveToFile.get() && fileOutputStream != null) {
                        try {
                            synchronized (this) {
                                if (fileOutputStream != null) {
                                    fileOutputStream.write(dataChunk);
                                    fileOutputStream.flush(); // Принудительная запись
                                }
                            }
                        } catch (IOException e) {
                            Log.e(TAG, "Error writing to file", e);
                            notifyError("Error writing to file: " + e.getMessage());
                        }
                    }
                }

                byte[] combinedData = combinedBuffer.array();

                // Форматируем данные для отображения
                String formattedBatch = formatDataBatch(combinedData);

                // Расчет скорости передачи
                long currentTime = System.currentTimeMillis();
                double elapsedTimeSeconds = (currentTime - startReceivingTime) / 1000.0;
                long totalBytes = totalBytesReceived.get();
                double kbPerSecond = elapsedTimeSeconds > 0 ? (totalBytes / 1024.0) / elapsedTimeSeconds : 0;

                // Обновляем UI через callback
                if (callback != null) {
                    mainHandler.post(() -> {
                        try {
                            callback.onDataBatch(formattedBatch, totalBytes, kbPerSecond);
                        } catch (Exception e) {
                            Log.e(TAG, "Error in callback", e);
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing data buffer", e);
                notifyError("Error processing data: " + e.getMessage());
            }
        });
    }

    private String formatDataBatch(byte[] data) {
        if (asciiMode.get()) {
            return formatDataBatchAsAscii(data);
        } else {
            return formatDataBatchAsHex(data);
        }
    }

    // Форматирование данных для отображения в текстовом виде с Windows-1251
    private String formatDataBatchAsAscii(byte[] data) {
        StringBuilder result = new StringBuilder();

        Log.d(TAG, "formatDataBatchAsAscii called, initial numericMode: " + numericMode.get() + ", data length: " + data.length);

        // Обрабатываем каждый байт отдельно
        for (int i = 0; i < data.length; i++) {
            byte currentByte = data[i];
            int value = currentByte & 0xFF;

            // Если еще не в числовом режиме, отслеживаем последовательность
            if (!numericMode.get()) {
                sequenceBuffer.append((char) value);

                // Проверяем наличие "Start" и затем реальных байтов CR LF (не символов!)
                String bufferContent = sequenceBuffer.toString();
                if (bufferContent.contains("Start")) {
                    int startIndex = bufferContent.lastIndexOf("Start");

                    // Логируем последовательность для отладки
                    if (startIndex + 5 < bufferContent.length()) {
                        String afterStart = bufferContent.substring(startIndex + 5);
                        Log.d(TAG, "Found 'Start', checking what follows: [" +
                                afterStart.replaceAll("\r", "\\\\r").replaceAll("\n", "\\\\n") + "]");

                        // Логируем ASCII коды символов после "Start"
                        StringBuilder codes = new StringBuilder();
                        for (int k = 0; k < Math.min(afterStart.length(), 10); k++) {
                            codes.append((int)afterStart.charAt(k)).append(" ");
                        }
                        Log.d(TAG, "ASCII codes after 'Start': " + codes.toString());
                    }

                    // Проверяем, что после "Start" идут именно байты 13 и 10 (или только 10)
                    if (startIndex + 5 < bufferContent.length()) {
                        String afterStart = bufferContent.substring(startIndex + 5);

                        // Ищем последовательность CR(13) + LF(10) или просто LF(10)
                        boolean foundNewline = false;

                        // Проверяем каждый символ после "Start"
                        for (int j = 0; j < afterStart.length(); j++) {
                            char c = afterStart.charAt(j);
                            int charCode = (int) c;

                            // Если встречаем LF (10) или CR+LF (13+10)
                            if (charCode == 10) {
                                foundNewline = true;
                                Log.d(TAG, "Found LF (10) at position " + j + " after 'Start'");
                                break;
                            }
                            // Если встречаем CR (13), проверяем следующий символ
                            else if (charCode == 13 && j + 1 < afterStart.length()) {
                                char nextC = afterStart.charAt(j + 1);
                                if ((int) nextC == 10) {
                                    foundNewline = true;
                                    Log.d(TAG, "Found CR+LF (13+10) at position " + j + " after 'Start'");
                                    break;
                                }
                            }
                        }

                        // Альтернативная проверка: если после "Start" идет пробел и символы,
                        // возможно это другой формат данных
                        if (!foundNewline && afterStart.startsWith(" ")) {
                            Log.d(TAG, "Alternative pattern: 'Start' followed by space and data");
                            // Возможно, переключение должно происходить после "Start " (с пробелом)
                            foundNewline = true;
                        }

                        if (foundNewline) {
                            // Найден переход! Переключаемся в числовой режим
                            numericMode.set(true);
                            Log.d(TAG, "Switching to numeric mode at byte " + i + " after 'Start' + newline (real \\r\\n)");

                            // Добавляем перевод строки в результат перед переключением
                            result.append("\n");

                            // Уведомляем пользователя
                            if (callback != null) {
                                mainHandler.post(() -> callback.onError("AUTO: Switched to numeric mode after 'Start' + newline"));
                            }

                            sequenceBuffer.setLength(0);

                            // НЕ добавляем текущий байт, если это CR или LF после "Start"
                            if (value == 13 || value == 10) {
                                // Пропускаем CR и LF после "Start", не отображаем их как числа
                                continue;
                            }

                            // Добавляем текущий байт уже как число только если это не CR/LF
                            result.append(value).append(" ");
                            continue;
                        }
                    }
                }

                // Ограничиваем размер буфера
                if (sequenceBuffer.length() > 100) {
                    String keepEnd = sequenceBuffer.substring(sequenceBuffer.length() - 50);
                    sequenceBuffer.setLength(0);
                    sequenceBuffer.append(keepEnd);
                }

                // Добавляем как текст (до переключения)
                if (value >= 32 && value <= 126) {
                    result.append((char) value);
                } else if (value == 10) {
                    result.append("\n");
                } else if (value == 13) {
                    // CR - не добавляем отдельно, он будет обработан вместе с LF
                    // Только добавляем если следующий байт НЕ LF
                    if (i + 1 < data.length && (data[i + 1] & 0xFF) != 10) {
                        result.append("\r");
                    }
                } else if (value == 9) {
                    result.append("\t");
                } else {
                    result.append(".");
                }
            } else {
                // Уже в числовом режиме - все как числа

                // Добавляем в буфер для отслеживания "End\r\n" (69 110 100 13 10)
                numericBuffer.add(value);

                // Ограничиваем размер буфера (держим только последние 10 байт)
                if (numericBuffer.size() > 10) {
                    numericBuffer.remove(0);
                }

                // Проверяем на последовательность "End\r\n" (69 110 100 13 10)
                if (numericBuffer.size() >= 5) {
                    int size = numericBuffer.size();
                    if (numericBuffer.get(size - 5) == 69 &&  // 'E'
                            numericBuffer.get(size - 4) == 110 && // 'n'
                            numericBuffer.get(size - 3) == 100 && // 'd'
                            numericBuffer.get(size - 2) == 13 &&  // '\r'
                            numericBuffer.get(size - 1) == 10) {  // '\n'

                        Log.d(TAG, "Found 'End\\r\\n' sequence in numeric mode");

                        // Удаляем последние 5 чисел из результата (если они уже добавлены)
                        String currentResult = result.toString();
                        String[] parts = currentResult.trim().split("\\s+");

                        if (parts.length >= 5) {
                            // Восстанавливаем результат без последних 5 чисел
                            StringBuilder newResult = new StringBuilder();
                            for (int j = 0; j < parts.length - 5; j++) {
                                newResult.append(parts[j]).append(" ");
                            }
                            // Добавляем "End" и переход строки
                            newResult.append("End\n");

                            // Заменяем результат
                            result.setLength(0);
                            result.append(newResult.toString());

                            // Очищаем буфер
                            numericBuffer.clear();

                            // Уведомляем пользователя
                            if (callback != null) {
                                mainHandler.post(() -> callback.onError("Found 'End' sequence - data transmission complete"));
                            }

                            continue;
                        }
                    }
                }

                // Добавляем как обычное число
                result.append(value).append(" ");
            }
        }

        return result.toString();
    }

    // Проверка, является ли текст валидным для отображения
    private boolean isValidText(String text) {
        if (text == null || text.isEmpty()) return false;

        int printableChars = 0;
        int totalChars = text.length();

        for (char c : text.toCharArray()) {
            if (Character.isLetterOrDigit(c) || Character.isWhitespace(c) ||
                    ".,!?;:()[]{}\"'-+=<>/@#$%^&*".indexOf(c) >= 0) {
                printableChars++;
            }
        }

        // Считаем текст валидным, если более 70% символов печатаемые
        return (printableChars * 100.0 / totalChars) > 70;
    }

    // Форматирование данных для HEX режима с переключением на числа после 'Start\r\n'
    private String formatDataBatchAsHex(byte[] data) {
        StringBuilder result = new StringBuilder();

        Log.d(TAG, "formatDataBatchAsHex called, initial numericMode: " + numericMode.get() + ", data length: " + data.length);

        // Обрабатываем каждый байт отдельно
        for (int i = 0; i < data.length; i++) {
            byte currentByte = data[i];
            int value = currentByte & 0xFF;

            // Если еще не в числовом режиме, отслеживаем последовательность
            if (!numericMode.get()) {
                sequenceBuffer.append((char) value);

                // Проверяем наличие "Start" и затем CR LF (13 10)
                String bufferContent = sequenceBuffer.toString();
                if (bufferContent.contains("Start")) {
                    int startIndex = bufferContent.lastIndexOf("Start");
                    String afterStart = bufferContent.substring(startIndex + 5);

                    // Проверяем точную последовательность: Start + CR(13) + LF(10)
                    if (afterStart.length() >= 2) {
                        boolean foundCRLF = false;
                        for (int j = 0; j < afterStart.length() - 1; j++) {
                            char c1 = afterStart.charAt(j);
                            char c2 = afterStart.charAt(j + 1);
                            if (((int)c1 == 13 && (int)c2 == 10) || (int)c2 == 10) {
                                foundCRLF = true;
                                break;
                            }
                        }

                        if (foundCRLF) {
                            // Найден переход! Переключаемся в числовой режим
                            numericMode.set(true);
                            Log.d(TAG, "HEX mode - Switching to numeric mode at byte " + i + " after 'Start' + CR LF");

                            // Уведомляем пользователя
                            if (callback != null) {
                                mainHandler.post(() -> callback.onError("AUTO: Switched to numeric mode after 'Start'"));
                            }

                            sequenceBuffer.setLength(0);

                            // Добавляем текущий байт уже как число
                            result.append(value).append(" ");
                            continue;
                        }
                    }
                }

                // Ограничиваем размер буфера
                if (sequenceBuffer.length() > 100) {
                    String keepEnd = sequenceBuffer.substring(sequenceBuffer.length() - 50);
                    sequenceBuffer.setLength(0);
                    sequenceBuffer.append(keepEnd);
                }

                // Добавляем как символы (до переключения)
                if (value >= 32 && value <= 126) {
                    result.append("'").append((char) value).append("' ");
                } else if (value == 10) {
                    result.append("\\n ");
                } else if (value == 13) {
                    result.append("\\r ");
                } else if (value == 9) {
                    result.append("\\t ");
                } else if (value == 0) {
                    result.append("NULL ");
                } else {
                    result.append(value).append(" ");
                }
            } else {
                // Уже в числовом режиме - все как числа
                result.append(value).append(" ");
            }
        }

        return result.toString();
    }

    // Простой HEX dump без ASCII интерпретации
    private void addSimpleHexDump(StringBuilder result, byte[] data, int start, int length) {
        final int BYTES_PER_LINE = 16;

        for (int i = start; i < start + length; i += BYTES_PER_LINE) {
            // Адрес
            result.append(String.format("%04X: ", i));

            // Только HEX байты
            for (int j = 0; j < BYTES_PER_LINE; j++) {
                if (i + j < start + length) {
                    int value = data[i + j] & 0xFF;
                    result.append(String.format("%02X ", value));
                } else {
                    result.append("   ");
                }

                // Дополнительный пробел в середине для читаемости
                if (j == 7) {
                    result.append(" ");
                }
            }
            result.append("\n");
        }
    }

    // Вспомогательный метод для создания hex dump
    private void addHexDump(StringBuilder result, byte[] data, int start, int length) {
        final int BYTES_PER_LINE = 16;

        for (int i = start; i < start + length; i += BYTES_PER_LINE) {
            // Адрес
            result.append(String.format("%04X: ", i));

            // HEX представление
            for (int j = 0; j < BYTES_PER_LINE; j++) {
                if (i + j < start + length) {
                    result.append(String.format("%02X ", data[i + j] & 0xFF));
                } else {
                    result.append("   ");
                }
                if (j == 7) result.append(" ");
            }

            result.append(" | ");

            // ASCII представление
            for (int j = 0; j < BYTES_PER_LINE; j++) {
                if (i + j < start + length) {
                    int value = data[i + j] & 0xFF;
                    result.append((value >= 32 && value <= 126) ? (char) value : '.');
                }
            }
            result.append("\n");
        }
    }

    // Улучшенное управление файлами
    public void setSaveToFile(boolean save) {
        saveToFile.set(save);

        if (save && fileOutputStream == null) {
            createOutputFile();
        } else if (!save && fileOutputStream != null) {
            closeOutputFile();
        }
    }

    private synchronized void createOutputFile() {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
            String timestamp = sdf.format(new Date());

            File directory = new File(context.getExternalFilesDir(null), "BLEData");
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IOException("Cannot create directory: " + directory.getAbsolutePath());
            }

            outputFile = new File(directory, "ble_data_" + timestamp + ".bin");
            fileOutputStream = new FileOutputStream(outputFile);

            Log.d(TAG, "Created output file: " + outputFile.getAbsolutePath());
            notifyError("Started saving to: " + outputFile.getName());
        } catch (IOException e) {
            Log.e(TAG, "Error creating output file", e);
            outputFile = null;
            fileOutputStream = null;
            notifyError("Error creating file: " + e.getMessage());
        }
    }

    private synchronized void closeOutputFile() {
        if (fileOutputStream != null) {
            try {
                fileOutputStream.flush();
                fileOutputStream.close();

                if (callback != null && outputFile != null) {
                    final String fileName = outputFile.getAbsolutePath();
                    mainHandler.post(() -> callback.onError("Data saved to: " + fileName));
                }
            } catch (IOException e) {
                Log.e(TAG, "Error closing output file", e);
                notifyError("Error closing file: " + e.getMessage());
            } finally {
                fileOutputStream = null;
                outputFile = null;
            }
        }
    }

    public void setCallback(BluetoothCallback callback) {
        this.callback = callback;
    }

    public boolean isConnected() {
        return isConnected.get();
    }

    public boolean isConnecting() {
        return isConnecting.get();
    }

    // Улучшенный метод подключения с переподключением
    public void connect(String deviceAddress, UUID serviceUuid, UUID characteristicUuid) {
        if (bluetoothAdapter == null || deviceAddress == null) {
            notifyError("Bluetooth adapter not available or device address is null");
            return;
        }

        // Сохраняем параметры для переподключения
        currentDeviceAddress = deviceAddress;
        currentServiceUuid = serviceUuid;
        currentCharacteristicUuid = characteristicUuid;

        // Сбрасываем счетчик попыток при новом подключении
        reconnectAttempts.set(0);

        connectInternal();
    }

    private void connectInternal() {
        if (isConnecting.get()) {
            Log.w(TAG, "Connection already in progress");
            return;
        }

        // Закрываем предыдущее соединение, если есть
        closeConnection();

        // Сбрасываем флаги состояния
        isConnected.set(false);
        isConnecting.set(true);
        servicesDiscovered.set(false);
        notificationsEnabled.set(false);

        // Сбрасываем числовой режим при новом подключении
        resetNumericMode();
        Log.d(TAG, "Connection started - reset to text mode, numeric mode: " + numericMode.get());

        // Очищаем счетчики и буферы
        totalBytesReceived.set(0);
        totalPacketsReceived.set(0);
        droppedPackets.set(0);
        startReceivingTime = System.currentTimeMillis();
        lastDataReceivedTime = 0;

        // Очищаем большой буфер приема
        clearReceptionBuffer();

        // Очищаем обычный буфер
        dataBuffer.clear();
        bufferedBytesCount.set(0);

        // Получаем устройство по адресу
        final BluetoothDevice device = bluetoothAdapter.getRemoteDevice(currentDeviceAddress);
        if (device == null) {
            notifyError("Device not found. Unable to connect.");
            isConnecting.set(false);
            return;
        }

        // Настраиваем таймаут соединения
        mainHandler.postDelayed(() -> {
            if (isConnecting.get() && !isConnected.get()) {
                Log.e(TAG, "Connection timeout");
                handleConnectionFailure("Connection timeout");
            }
        }, CONNECTION_TIMEOUT);

        // Подключаемся к устройству
        Log.d(TAG, "Attempting to connect to device: " + currentDeviceAddress);
        try {
            bluetoothGatt = device.connectGatt(context, false, gattCallback);
            if (bluetoothGatt == null) {
                throw new RuntimeException("Failed to create GATT connection");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error creating GATT connection", e);
            handleConnectionFailure("Error creating GATT connection: " + e.getMessage());
        }
    }

    // Централизованная обработка ошибок подключения
    private void handleConnectionFailure(String error) {
        isConnecting.set(false);
        isConnected.set(false);

        int attempts = reconnectAttempts.incrementAndGet();
        Log.w(TAG, "Connection failed (attempt " + attempts + "/" + MAX_RECONNECT_ATTEMPTS + "): " + error);

        if (callback != null) {
            mainHandler.post(() -> callback.onReconnectAttempt(attempts, MAX_RECONNECT_ATTEMPTS));
        }

        if (attempts < MAX_RECONNECT_ATTEMPTS) {
            // Пытаемся переподключиться
            mainHandler.postDelayed(() -> {
                Log.i(TAG, "Attempting to reconnect...");
                connectInternal();
            }, RECONNECT_DELAY);
        } else {
            // Превышено количество попыток
            notifyError("Failed to connect after " + MAX_RECONNECT_ATTEMPTS + " attempts: " + error);
            notifyConnectionStateChange(false);
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "GATT connection failed with status: " + status);
                handleConnectionFailure("GATT connection failed with status: " + status);
                return;
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected.set(true);
                isConnecting.set(false);
                reconnectAttempts.set(0); // Сбрасываем счетчик при успешном подключении

                Log.i(TAG, "Connected to GATT server.");
                notifyConnectionStateChange(true);

                // Запускаем обработчик буфера
                scheduleBufferProcessing();

                // Запрашиваем увеличение MTU для более эффективной передачи данных
                boolean mtuResult = gatt.requestMtu(517);
                if (!mtuResult) {
                    Log.w(TAG, "Failed to request MTU, proceeding with service discovery");
                    gatt.discoverServices();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected from GATT server.");
                handleDisconnection();
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Log.d(TAG, "MTU changed to: " + mtu + ", status: " + status);

            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Успешно установлен больший MTU
                notifyError("MTU increased to " + mtu + " bytes");
            } else {
                // Не удалось увеличить MTU, используем стандартный
                Log.w(TAG, "Failed to increase MTU, using default (23 bytes)");
            }

            // После установки MTU начинаем обнаружение служб
            boolean result = gatt.discoverServices();
            if (!result) {
                Log.e(TAG, "Failed to start service discovery");
                handleConnectionFailure("Failed to start service discovery");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered successfully");

                BluetoothGattService service = gatt.getService(currentServiceUuid);
                if (service != null) {
                    BluetoothGattCharacteristic characteristic = service.getCharacteristic(currentCharacteristicUuid);
                    if (characteristic != null) {
                        boolean success = enableNotifications(gatt, characteristic);
                        servicesDiscovered.set(true);
                        notifyServicesDiscovered(success);
                    } else {
                        Log.e(TAG, "Characteristic not found");
                        handleConnectionFailure("Characteristic not found: " + currentCharacteristicUuid);
                    }
                } else {
                    Log.e(TAG, "Service not found");
                    handleConnectionFailure("Service not found: " + currentServiceUuid);
                }
            } else {
                Log.e(TAG, "Service discovery failed with status: " + status);
                handleConnectionFailure("Service discovery failed with status: " + status);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic.getValue();
            if (data != null && data.length > 0) {
                handleReceivedData(data);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Descriptor write successful");
                if (descriptor.getCharacteristic().getUuid().equals(currentCharacteristicUuid)) {
                    notificationsEnabled.set(true);
                    notifyError("Notifications enabled successfully");
                }
            } else {
                Log.e(TAG, "Descriptor write failed: " + status);
                handleConnectionFailure("Failed to enable notifications: status " + status);
            }
        }
    };

    private void handleDisconnection() {
        boolean wasConnected = isConnected.getAndSet(false);
        isConnecting.set(false);
        servicesDiscovered.set(false);
        notificationsEnabled.set(false);

        // Обрабатываем оставшиеся данные в буфере
        if (bufferedBytesCount.get() > 0) {
            processDataBuffer();
        }

        // Закрываем файл, если он открыт
        if (saveToFile.get()) {
            closeOutputFile();
        }

        if (wasConnected) {
            notifyConnectionStateChange(false);
        }

        closeConnection();
    }

    private void handleReceivedData(byte[] data) {
        try {
            // ПРИОРИТЕТ 1: Быстро сохраняем данные в большой буфер
            synchronized (receptionBuffer) {
                receptionBuffer.write(data);
            }

            // Обновляем статистику
            lastDataReceivedTime = System.currentTimeMillis();
            if (startReceivingTime == 0) {
                startReceivingTime = lastDataReceivedTime;
            }

            long totalBytes = totalBytesReceived.addAndGet(data.length);
            long totalPackets = totalPacketsReceived.incrementAndGet();

            // Минимальное логирование только для критических пакетов
            if (totalPackets % 100 == 0) {
                Log.d(TAG, "Received " + totalPackets + " packets, " + totalBytes + " bytes");
            }

            // Проверяем на окончание передачи "End\r\n"
            checkForEndSequence(data);

            // ОЧЕНЬ редкое обновление UI - только раз в 500мс
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastUIUpdateTime > UI_UPDATE_INTERVAL || receptionComplete) {
                lastUIUpdateTime = currentTime;

                // Добавляем в очередь для обработки UI (НЕ блокируем прием)
                dataProcessingExecutor.execute(() -> {
                    processDataForUI();
                });
            }

        } catch (Exception e) {
            Log.e(TAG, "Error handling received data", e);
        }
    }

    private void checkForEndSequence(byte[] data) {
        // Быстрая проверка на "End\r\n" без сложной обработки
        if (data.length >= 5) {
            int len = data.length;
            if (data[len-5] == 69 && data[len-4] == 110 && data[len-3] == 100 &&
                    data[len-2] == 13 && data[len-1] == 10) {
                receptionComplete = true;
                Log.d(TAG, "Reception complete - End sequence detected");
            }
        }
    }

    private void processDataForUI() {
        try {
            byte[] allData;
            synchronized (receptionBuffer) {
                allData = receptionBuffer.toByteArray();
            }

            if (allData.length == 0) return;

            // Обрабатываем только небольшую порцию для UI (последние 8KB)
            int displayStart = Math.max(0, allData.length - MAX_DATA_DISPLAY_SIZE);
            byte[] displayData = new byte[allData.length - displayStart];
            System.arraycopy(allData, displayStart, displayData, 0, displayData.length);

            // Форматируем данные
            String formattedData = formatDataBatch(displayData);

            // Обновляем UI
            if (callback != null) {
                long totalBytes = totalBytesReceived.get();
                long duration = System.currentTimeMillis() - startReceivingTime;
                double kbPerSecond = duration > 0 ? (totalBytes / 1024.0) / (duration / 1000.0) : 0;

                mainHandler.post(() -> {
                    try {
                        callback.onDataBatch(formattedData, totalBytes, kbPerSecond);
                    } catch (Exception e) {
                        Log.e(TAG, "Error in UI callback", e);
                    }
                });
            }

        } catch (Exception e) {
            Log.e(TAG, "Error processing data for UI", e);
        }
    }

    private void logReceivedData(byte[] data) {
        StringBuilder sb = new StringBuilder();
        sb.append("Received ").append(data.length).append(" bytes: ");

        int logSize = Math.min(data.length, 32); // Логируем только первые 32 байта
        for (int i = 0; i < logSize; i++) {
            sb.append(String.format("%02X ", data[i] & 0xFF));
        }
        if (data.length > logSize) {
            sb.append("...");
        }

        Log.d(DATA_TAG, sb.toString());
    }

    // Включение уведомлений для характеристики
    private boolean enableNotifications(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        try {
            boolean success = gatt.setCharacteristicNotification(characteristic, true);
            if (!success) {
                Log.e(TAG, "Failed to set characteristic notification");
                return false;
            }

            Log.d(TAG, "setCharacteristicNotification returned true");

            // Записываем дескриптор для включения уведомлений
            UUID descriptorUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(descriptorUuid);

            if (descriptor != null) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                boolean writeSuccess = gatt.writeDescriptor(descriptor);
                Log.d(TAG, "Descriptor write initiated: " + writeSuccess);
                return writeSuccess;
            } else {
                Log.e(TAG, "Client configuration descriptor not found");
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error enabling notifications", e);
            return false;
        }
    }

    // Отключение от устройства
    public void disconnect() {
        Log.d(TAG, "Disconnect requested");
        isConnecting.set(false);
        reconnectAttempts.set(MAX_RECONNECT_ATTEMPTS); // Предотвращаем автоматические переподключения

        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
        } else {
            handleDisconnection();
        }
    }

    private void closeConnection() {
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing GATT", e);
            } finally {
                bluetoothGatt = null;
            }
        }
    }

    // Закрытие ресурсов
    public void close() {
        Log.d(TAG, "Closing BluetoothService");

        // Отключаемся
        disconnect();

        // Обработка оставшихся данных
        if (bufferedBytesCount.get() > 0) {
            processDataBuffer();
        }

        // Закрытие файла
        if (saveToFile.get()) {
            closeOutputFile();
        }

        // Остановка обработчиков
        mainHandler.removeCallbacksAndMessages(null);
        backgroundHandler.removeCallbacksAndMessages(null);

        // Завершение пула потоков
        try {
            dataProcessingExecutor.shutdown();
            if (!dataProcessingExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                dataProcessingExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            dataProcessingExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // Метод для установки режима отображения
    public void setAsciiMode(boolean asciiMode) {
        this.asciiMode.set(asciiMode);

        // Обрабатываем оставшиеся данные в буфере при изменении режима
        if (bufferedBytesCount.get() > 0) {
            processDataBuffer();
        }
    }

    // Метод для сброса числового режима (например, при переподключении)
    public void resetNumericMode() {
        boolean wasNumeric = numericMode.getAndSet(false);
        sequenceBuffer.setLength(0);
        numericBuffer.clear();
        Log.d(TAG, "Numeric mode reset");

        // Уведомляем о смене режима
        if (wasNumeric && callback != null) {
            mainHandler.post(() -> callback.onError("Reset to text mode - waiting for 'Start\\r\\n'"));
        }
    }

    // Метод для принудительной активации числового режима
    public void forceNumericMode(boolean enable) {
        boolean wasNumeric = numericMode.getAndSet(enable);
        if (enable) {
            sequenceBuffer.setLength(0);
            numericBuffer.clear();
        }
        Log.d(TAG, "Numeric mode " + (enable ? "enabled" : "disabled") + " manually");

        // Уведомляем о смене режима
        if (wasNumeric != enable && callback != null) {
            String message = enable ? "Forced numeric mode enabled" : "Numeric mode disabled - waiting for 'Start\\r\\n'";
            mainHandler.post(() -> callback.onError(message));
        }
    }

    // Проверка текущего режима
    public boolean isNumericMode() {
        return numericMode.get();
    }

    // Вспомогательные методы для уведомлений
    private void notifyError(String message) {
        if (callback != null) {
            mainHandler.post(() -> {
                try {
                    callback.onError(message);
                } catch (Exception e) {
                    Log.e(TAG, "Error in error callback", e);
                }
            });
        }
    }

    private void notifyConnectionStateChange(boolean connected) {
        if (callback != null) {
            mainHandler.post(() -> {
                try {
                    callback.onConnectionStateChange(connected);
                } catch (Exception e) {
                    Log.e(TAG, "Error in connection state callback", e);
                }
            });
        }
    }

    private void notifyServicesDiscovered(boolean success) {
        if (callback != null) {
            mainHandler.post(() -> {
                try {
                    callback.onServicesDiscovered(success);
                } catch (Exception e) {
                    Log.e(TAG, "Error in services discovered callback", e);
                }
            });
        }
    }

    // Метод для принудительного переподключения
    public void forceReconnect() {
        Log.i(TAG, "Force reconnect requested");
        reconnectAttempts.set(0);
        disconnect();

        // Небольшая задержка перед переподключением
        mainHandler.postDelayed(() -> {
            if (currentDeviceAddress != null && currentServiceUuid != null && currentCharacteristicUuid != null) {
                connectInternal();
            }
        }, 1000);
    }

    // Получение полных данных после завершения приема
    public byte[] getCompleteReceivedData() {
        synchronized (receptionBuffer) {
            return receptionBuffer.toByteArray();
        }
    }

    // Очистка буфера приема
    public void clearReceptionBuffer() {
        synchronized (receptionBuffer) {
            receptionBuffer.reset();
        }
        receptionComplete = false;
        lastUIUpdateTime = 0;
    }

    // Проверка завершения приема
    public boolean isReceptionComplete() {
        return receptionComplete;
    }

    public long getTotalPacketsReceived() {
        return totalPacketsReceived.get();
    }

    public long getDroppedPackets() {
        return droppedPackets.get();
    }

    public double getPacketLossRate() {
        long total = totalPacketsReceived.get();
        long dropped = droppedPackets.get();
        return total > 0 ? (dropped * 100.0 / total) : 0.0;
    }

    public long getConnectionDuration() {
        return startReceivingTime > 0 ? System.currentTimeMillis() - startReceivingTime : 0;
    }

    public boolean isNotificationsEnabled() {
        return notificationsEnabled.get();
    }

    public boolean isServicesDiscovered() {
        return servicesDiscovered.get();
    }
}