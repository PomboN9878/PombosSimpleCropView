# PombosSimpleCropView - Internal Documentation

Technical reference for contributors. Describes implementation details, state management, and internal contracts.

---

## 1. Overview

### Purpose
Provides a square crop interface for bitmap images with gesture controls (drag, pinch-to-zoom, rotation).

### Scope
- Square 1:1 crop area only (80% of smallest view dimension)
- Fixed center position (crop area cannot be moved)
- 90-degree rotation increments only
- No aspect ratio configuration
- No multi-crop support

### Non-goals
- Arbitrary rotation angles
- Rectangle/circle crop shapes
- Crop area repositioning
- Real-time filters or adjustments

---

## 2. Architecture

### File Structure
```
PombosSimpleCropView.java    // Single file, 700+ lines
├── Touch handling
├── Matrix transformations
├── Animation system
├── Boundary enforcement
└── Crop extraction
```

### Dependencies
- `androidx.appcompat.widget.AppCompatImageView` (base class)
- `android.view.ScaleGestureDetector` (pinch detection)
- `android.animation.ValueAnimator` (snap-back, rotation)
- No external libraries

---

## 3. State Management

### Core State Variables

| Variable | Type | Lifecycle | Mutated By |
|----------|------|-----------|------------|
| `matrix` | Matrix | Persistent | Touch events, animations |
| `savedMatrix` | Matrix | Per-gesture | ACTION_DOWN, ACTION_POINTER_DOWN |
| `currentScale` | float | Persistent | ScaleGestureDetector, rotation end |
| `mode` | int | Per-gesture | Touch state transitions |
| `rotationDegrees` | int | Persistent | Rotation animation end |
| `bounceAnimator` | ValueAnimator | Transient | Snap-back triggers |
| `rotateAnimator` | ValueAnimator | Transient | Rotation button tap |
| `minScale` | float | Recalculated | centerImage(), rotation end |
| `cropSize` | int | Per-layout | onSizeChanged() |

### Critical State Invariants

1. **Matrix consistency**: `matrix` must always be set via `setImageMatrix()` followed by `invalidate()`
2. **Scale tracking**: `currentScale` MUST be updated whenever matrix scale changes (currently enforced only in animations and pinch)
3. **Animation exclusivity**: Only one animator can run at a time (enforced via cancellation)

**Known bug:** `currentScale` is not derived from matrix — stored separately. Can desync if matrix is modified directly. Future fix: derive from `matrix.getValues()[Matrix.MSCALE_X]`.

---

## 4. Touch Event State Machine

### States
```
NONE (0) → no active gesture
DRAG (1) → single-finger drag
ZOOM (2) → two-finger pinch
```

### Transitions
```
NONE ──ACTION_DOWN──→ DRAG ──ACTION_UP──→ NONE
NONE ──ACTION_POINTER_DOWN──→ ZOOM ──ACTION_POINTER_UP──→ NONE
```

### Processing Order (onTouchEvent)
1. Check FAB hit → handle rotation → return true
2. Pass to ScaleGestureDetector
3. Handle drag/zoom based on mode
4. On release → trigger snap-back if needed

**Edge case:** If no drawable loaded, touch events are processed but have no effect (no null check in some paths).

---

## 5. Rendering Pipeline

### Draw Order (onDraw)
```
1. super.onDraw()         // Image with matrix transform (AppCompatImageView)
2. Overlay (4 rects)      // Semi-transparent 0x80000000
3. Grid (6 lines)         // Rule of thirds 0xFFFFFFFF
4. Crop frame (stroke)    // White border
5. FAB (circle + icon)    // Bottom-right button
```

**Performance:** All draw calls occur on UI thread. View invalidates on every touch move and animation frame (~60fps during interaction).

### Invalidation Triggers
- Touch move (DRAG/ZOOM)
- Animation frame update
- Rotation button tap
- Image load via setImageBitmap()

---

## 6. Matrix Transform System

### Transform Order
```
Matrix = Scale × Rotation × Translation
```
Applied via post-multiplication: `matrix.postScale()` → `matrix.postRotate()` → `matrix.postTranslate()`

### Scale Calculation
```java
float effectiveWidth = (rotationDegrees % 180 != 0) ? imageHeight : imageWidth;
float effectiveHeight = (rotationDegrees % 180 != 0) ? imageWidth : imageHeight;

minScale = Math.max(
    cropSize / effectiveWidth,
    cropSize / effectiveHeight
);
```

**Design rationale:** Using `max()` ensures image covers entire crop area. Causes clipping on longer dimension, but prevents gaps.

### Rotation Pivot
```java
// Rotation happens after scale, so pivot must be in scaled space
float pivotX = (imageWidth * scale) / 2f;
float pivotY = (imageHeight * scale) / 2f;
matrix.postRotate(rotationDegrees, pivotX, pivotY);
```

### Translation
```java
// Map current bounds to view space
RectF bounds = new RectF(0, 0, imageWidth, imageHeight);
matrix.mapRect(bounds);

// Calculate offset to center within crop area
float dx = cropCenterX - (bounds.left + bounds.width() / 2f);
float dy = cropCenterY - (bounds.top + bounds.height() / 2f);
matrix.postTranslate(dx, dy);
```

---

## 7. Boundary Enforcement

### Evaluation Order
```
1. Calculate testMatrix with proposed delta
2. Check if bounds exceed crop area
3. If yes:
   a. Calculate overflow amount
   b. If overflow < maxOverscroll (20%):
      Apply resistance: delta *= 1 / (1 + overflow / RESISTANCE_FACTOR)
   c. If overflow >= maxOverscroll:
      Apply hard limit: delta = delta - (overflow - maxOverscroll)
4. Return adjusted delta
```

### Resistance Formula
```java
float resistance = 1f / (1f + overflow / RESISTANCE_FACTOR);
resistedDelta = originalDelta * resistance;
```

At `overflow = RESISTANCE_FACTOR`, movement is halved.

**Trade-off:** Not physically accurate (no velocity decay), but cheap and responsive.

### Hard Limit
```java
float maxOverscroll = cropSize * MAX_OVERSCROLL;  // 20%

if (overflow > maxOverscroll) {
    resistedDelta = originalDelta - (overflow - maxOverscroll);
}
```

Prevents dragging image completely off-screen.

### Snap-Back Triggers
- Scale below `minScale` → call `animateToCenter()`
- Any crop edge exposed → call `animateBounceBack()`

Both checked on ACTION_UP and ACTION_POINTER_UP.

---

## 8. Animation System

### Active Animators
- `bounceAnimator`: Boundary snap-back (translation only)
- `rotateAnimator`: 90° rotation (scale + rotation + translation)

### Lifecycle Management
```java
// CRITICAL: Always cancel before starting new animation
if (bounceAnimator != null && bounceAnimator.isRunning()) {
    bounceAnimator.cancel();
}
```

**Why:** Concurrent matrix mutations cause corruption. Cancellation ensures mutual exclusion.

### Bounce-Back Implementation
```java
// Interpolate translation values directly
values[Matrix.MTRANS_X] = startX + (endX - startX) * progress;
values[Matrix.MTRANS_Y] = startY + (endY - startY) * progress;
matrix.setValues(values);
```

Duration: 300ms, DecelerateInterpolator

### Rotation Implementation
```java
// Rebuild entire matrix each frame
matrix.reset();
matrix.postScale(scale, scale);
matrix.postRotate(currentRotation, pivotX, pivotY);
matrix.postTranslate(dx, dy);

// Update currentScale (prevents desync)
currentScale = scale;
```

Duration: 400ms, DecelerateInterpolator

**Why rebuild:** Effective dimensions change continuously during rotation (not just at 90° boundaries). Interpolating only rotation would cause scale jumps.

**Performance:** ~24 rebuilds per rotation (400ms ÷ 16ms frame). Acceptable on modern devices.

### Edge Case: Rotation During Snap-Back
Tapping rotation button while snap-back is running cancels snap-back and starts rotation. Image may be out-of-bounds at rotation start, but rotation recalculates position and re-centers.

**Better approach (not implemented):** Queue rotation to start after snap-back completes.

---

## 9. Gesture Processing Details

### Complete Pinch-to-Zoom Flow
```
1. ACTION_DOWN → savedMatrix = matrix, mode = DRAG
2. ACTION_POINTER_DOWN → mode = ZOOM
3. ScaleGestureDetector.onScale():
   a. Calculate scaleFactor from finger distance
   b. newScale = currentScale * scaleFactor
   c. If newScale <= maxScale:
      - matrix.postScale(scaleFactor, scaleFactor, focusX, focusY)
      - currentScale = newScale
4. ACTION_POINTER_UP → check boundaries, trigger snap-back if needed
```

**Multi-pointer edge case:** Android pointer IDs are not stable. If fingers are lifted in unexpected order, `mode` may remain ZOOM with only one finger down. This is handled by transitioning to NONE on ACTION_UP regardless of mode.

### Complete Rotation Flow
```
1. User taps FAB (detected in onTouchEvent before gesture processing)
2. Check if rotateAnimator is running → return if yes
3. Calculate endRotation = (currentDegrees + 90) % 360
4. Start animation:
   - Each frame: rebuild matrix with interpolated rotation
   - Recalculate effectiveWidth/Height at current rotation
   - Recalculate scale to cover crop area
   - Re-center within crop
5. onAnimationEnd():
   - rotationDegrees = endRotation
   - Update minScale for new orientation
```

**Performance note:** Rotation is smooth on mid-range devices, but may drop frames on low-end devices due to matrix rebuild cost.

---

## 10. Crop Extraction

### Implementation
```java
// Step 1: Render transformed image to full-view bitmap
Bitmap fullViewBitmap = Bitmap.createBitmap(viewWidth, viewHeight, ARGB_8888);
Canvas canvas = new Canvas(fullViewBitmap);
canvas.concat(matrix);
canvas.drawBitmap(originalBitmap, 0, 0, null);

// Step 2: Extract crop region
Bitmap result = Bitmap.createBitmap(fullViewBitmap, left, top, cropSize, cropSize);
fullViewBitmap.recycle();
```

### Memory Cost
For 1080x1920 view, 864x864 crop:
- Temporary allocation: 1080 × 1920 × 4 = 8.3 MB
- Result: 864 × 864 × 4 = 3.0 MB
- Peak usage: 11.3 MB

**Thread safety:** Runs on UI thread. Large crops (>2000px view) can cause visible lag (100-200ms).

### Alternative Considered
Inverse-map crop bounds to original bitmap coordinates, extract directly.

**Why not used:**
- Rotation requires manual pixel mapping (no native API)
- Matrix inversion precision errors at extreme scales
- Interpolation quality differs from display rendering

**Trade-off:** Higher memory usage, guaranteed pixel-perfect output.

---

## 11. Public API Contracts

### setImageBitmap(Bitmap)
- **Thread:** Must be called on UI thread
- **Null handling:** Accepted, but gestures have no effect until non-null bitmap set
- **Side effects:** Resets matrix, recalculates minScale, triggers centerImage()
- **Memory:** Retains reference to bitmap (not copied)

### getCroppedBitmap()
- **Thread:** Must be called on UI thread (allocates bitmaps, draws to canvas)
- **Returns:** Null if no drawable set, or if extraction fails
- **Memory:** Allocates ~(viewWidth × viewHeight + cropSize²) × 4 bytes temporarily
- **Performance:** Blocking operation, 50-200ms on mid-range devices for typical sizes
- **Caller responsibility:** Recycle returned bitmap when done

### rotateImage()
- **Thread:** Must be called on UI thread
- **Pre-condition:** Drawable must be set (returns early if null)
- **Side effects:** Cancels active snap-back, starts 400ms rotation animation
- **Re-entrancy:** Ignores call if rotation already in progress

---

## 12. Known Limitations

### 1. Scale Desync
`currentScale` stored separately from matrix. If matrix is modified externally (e.g., direct `setValues()`), scale tracking breaks.

**Impact:** Min/max scale enforcement fails, snap-back calculations incorrect.

**Mitigation:** Always modify matrix via provided methods (animations).

### 2. Null Drawable Handling
Many methods check `if (getDrawable() == null) return`, but not all touch paths do. Can cause NullPointerException in edge cases.

**Impact:** Crash if user interacts before image loads.

**Mitigation:** Check drawable in onTouchEvent early return.

### 3. Large Bitmap Memory
No downsampling. Loading 4000×3000 images causes OOM on low-end devices.

**Impact:** App crash on image selection.

**Mitigation:** Caller must downsample before setImageBitmap().

### 4. UI Thread Blocking
Crop extraction runs synchronously on UI thread. Large crops (>2000px) cause visible lag.

**Impact:** ANR risk, poor UX.

**Mitigation:** Caller should execute getCroppedBitmap() on background thread, but this requires careful lifecycle management.

### 5. Animation Interruption
Rapidly tapping rotation or triggering gestures during animations can leave matrix in inconsistent state if cancellation fails.

**Impact:** Image position/scale incorrect until next gesture.

**Mitigation:** Better animation queue management.

---

## 13. Future Improvements

### High Priority
1. **Derive scale from matrix**: Replace `currentScale` with `matrix.getValues()[MSCALE_X]`
2. **Null safety**: Add drawable checks in all touch paths
3. **Background crop**: Add async getCroppedBitmapAsync(Callback)

### Medium Priority
4. **Configurable crop size**: Add `setCropSize(int)` or `setCropRatio(float)`
5. **Aspect ratio support**: Allow non-square crops
6. **Memory optimization**: Skip full-view bitmap by inverse-mapping coordinates

### Low Priority
7. **Arbitrary rotation**: Support any angle (requires dynamic crop area)
8. **Crop repositioning**: Allow dragging crop frame independently
9. **Multi-gesture support**: Handle 3+ finger interactions

### Breaking Changes Required
- Current single-constructor API too limiting
- Need builder pattern or XML attributes for configuration
- State management refactor for complex features
