package com.example.ggk;

public enum PressureUnit {
    PA("Па", 1.0),
    KPA("кПа", 0.001),
    MM_HG("мм рт.ст.", 0.00750062),
    MPA("МПа", 0.000001),
    KGF_CM2("кгс/см²", 0.0000101972),
    MBAR("мБар", 0.01),
    BAR("Бар", 0.00001),
    PSI("psi", 0.000145038);

    private final String displayName;
    private final double conversionFactor;

    PressureUnit(String displayName, double conversionFactor) {
        this.displayName = displayName;
        this.conversionFactor = conversionFactor;
    }

    public String getDisplayName() {
        return displayName;
    }

    public double convertFromPa(double valueInPa) {
        return valueInPa * conversionFactor;
    }

    public double convertToPa(double value) {
        return value / conversionFactor;
    }

    // Умное форматирование значения с учетом величины
    public String formatValue(double valueInPa) {
        double convertedValue = convertFromPa(valueInPa);

        // Определяем формат на основе величины значения
        if (Math.abs(convertedValue) < 0.001) {
            // Очень маленькие значения - научная нотация
            return String.format("%.2e", convertedValue);
        } else if (Math.abs(convertedValue) < 0.01) {
            // 4 знака после запятой
            return String.format("%.4f", convertedValue);
        } else if (Math.abs(convertedValue) < 0.1) {
            // 3 знака после запятой
            return String.format("%.3f", convertedValue);
        } else if (Math.abs(convertedValue) < 1) {
            // 2 знака после запятой
            return String.format("%.2f", convertedValue);
        } else if (Math.abs(convertedValue) < 10) {
            // 1 знак после запятой
            return String.format("%.1f", convertedValue);
        } else if (Math.abs(convertedValue) < 1000) {
            // Без дробной части
            return String.format("%.0f", convertedValue);
        } else {
            // Большие значения - разделители тысяч
            return String.format("%,.0f", convertedValue);
        }
    }

    // Определение количества знаков после запятой для данной величины
    public int getDecimalPlaces(double valueInPa) {
        double convertedValue = Math.abs(convertFromPa(valueInPa));

        if (convertedValue < 0.001) return 6;  // Для научной нотации
        else if (convertedValue < 0.01) return 4;
        else if (convertedValue < 0.1) return 3;
        else if (convertedValue < 1) return 2;
        else if (convertedValue < 10) return 1;
        else return 0;
    }

    public static PressureUnit fromDisplayName(String displayName) {
        for (PressureUnit unit : values()) {
            if (unit.displayName.equals(displayName)) {
                return unit;
            }
        }
        return PA;
    }
}