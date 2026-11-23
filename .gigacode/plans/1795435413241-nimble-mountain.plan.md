# Fix: Expand click area for port circles and component hitbox

## Problem 1: Port circles
Port circles (`Circle`) in `SchematicWindow.drawAllPorts()` have `pickOnBounds = false` by default. Mouse events fire only on the stroke (outline), not the filled area.

## Problem 2: Component hitbox too small
In `drawComponents()`, component dimensions are read from Canvas using `.width/.height` which are 0 before layout. For fallback components with many ports, the calculated hitbox is tiny instead of the correct size with port spacing.

## Solution

### Fix 1: Port hitbox (already done)
Add invisible hitbox circle with 10px radius under each port circle.

### Fix 2: Component dimensions
**File:** `src/main/kotlin/ru/circuitforge/ui/schematic/SchematicWindow.kt`

**Location:** `drawComponents()` method, lines ~528-532

**Change:** Replace `.width/.height` with `.prefWidth/.prefHeight` for Canvas:
```kotlin
// Before:
is Canvas -> firstChild.width to firstChild.height
// After:
is Canvas -> firstChild.prefWidth to firstChild.prefHeight
```

## Verification
- Build: `./gradlew build`
- Run app, open schematic with large fallback components (many ports)
- Click area for component should now correctly cover the full component bounds
- Port circles should be clickable anywhere within their area