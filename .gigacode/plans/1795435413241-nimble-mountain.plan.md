# Изменение логики отрисовки цепей с labelMode

## Выполнено

### 1. Пропуск кривых для labelMode цепей
**Файл:** `SchematicWindow.kt`, строка 304, метод `drawPortConnections()`
```kotlin
if (net.labelMode) return@forEach
```

### 2. Смещение меток на 20px от центра компонента
**Файл:** `SchematicWindow.kt`, строки 444-467, метод `drawNetLabelsForActivePorts()`
- Вычисляется центр компонента: `centerX = node.x + symSize.first / 2.0`
- Вектор от центра к пину: `dx, dy`
- Смещение 20px по направлению к пину: `labelX = pinCoords.first + (dx / dist) * 20.0`

## Верификация
- `./gradlew build` — BUILD SUCCESSFUL
- Открыть схему с цепью `labelMode: true`
- Кривая Безье не рисуется, вместо неё — текстовые метки с именем цепи
- Метки смещены на 20px от центра компонента по направлению к пину
