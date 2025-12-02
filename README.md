# PombosSimpleCropView

[![](https://jitpack.io/v/PomboN9878/PombosSimpleCropView.svg)](https://jitpack.io/#PomboN9878/PombosSimpleCropView)
[![API](https://img.shields.io/badge/API-24%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=24)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

A lightweight Android image crop view with gesture controls and smooth animations.

## Features

- Pinch-to-zoom and drag gestures with boundary constraints
- 90-degree rotation with animated transitions
- Rule of thirds grid overlay
- Automatic boundary snap-back
- Configurable zoom limits
- Single bitmap allocation during crop operation

## Installation

Add JitPack repository to your root `build.gradle`:

```gradle
allprojects {
    repositories {
        maven { url 'https://jitpack.io' }
    }
}
```

Add the dependency:

```gradle
dependencies {
    implementation 'com.github.PomboN9878:PombosSimpleCropView:1.0'
}
```

**Requirements:** minSdkVersion 24

## Quick Start

Add the view to your layout:

```xml
<com.pombos.pombocropview.PombosCropView
    android:id="@+id/cropView"
    android:layout_width="match_parent"
    android:layout_height="400dp"
    android:background="@color/black" />
```

Load an image and get the cropped result:

```java
PombosCropView cropView = findViewById(R.id.cropView);

// Load image
Bitmap bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.sample);
cropView.setImageBitmap(bitmap);

// Get cropped bitmap
Bitmap result = cropView.getCroppedBitmap();
```

## Loading Images

### From Bitmap

```java
Bitmap bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.sample_image);
cropView.setImageBitmap(bitmap);
```

### From URI

```java
Uri imageUri = data.getData();
try (InputStream inputStream = getContentResolver().openInputStream(imageUri)) {
    Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
    cropView.setImageBitmap(bitmap);
} catch (IOException e) {
    e.printStackTrace();
}
```

### From File

```java
File imageFile = new File(filePath);
if (imageFile.exists()) {
    Bitmap bitmap = BitmapFactory.decodeFile(imageFile.getAbsolutePath());
    cropView.setImageBitmap(bitmap);
}
```

### With Glide

```java
Glide.with(context)
    .asBitmap()
    .load(imageUri)
    .into(new CustomTarget<Bitmap>() {
        @Override
        public void onResourceReady(@NonNull Bitmap resource, 
                                    @Nullable Transition<? super Bitmap> transition) {
            cropView.setImageBitmap(resource);
        }
        
        @Override
        public void onLoadCleared(@Nullable Drawable placeholder) {}
    });
```

### With Picasso

```java
Picasso.get()
    .load(imageUri)
    .into(new Target() {
        @Override
        public void onBitmapLoaded(Bitmap bitmap, Picasso.LoadedFrom from) {
            cropView.setImageBitmap(bitmap);
        }
        
        @Override
        public void onBitmapFailed(Exception e, Drawable errorDrawable) {}
        
        @Override
        public void onPrepareLoad(Drawable placeHolderDrawable) {}
    });
```

## Getting Cropped Result

```java
Bitmap croppedBitmap = cropView.getCroppedBitmap();

if (croppedBitmap != null) {
    // Use the result
    imageView.setImageBitmap(croppedBitmap);
}
```

### Saving to File

```java
File outputFile = new File(getFilesDir(), "cropped.jpg");
try (FileOutputStream out = new FileOutputStream(outputFile)) {
    croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
} catch (IOException e) {
    e.printStackTrace();
}
```

## User Interactions

- **Drag**: Move the image within crop bounds
- **Pinch**: Zoom in/out within scale limits
- **Rotation Button**: Tap the floating button (bottom-right) to rotate 90 degrees
- **Auto-snap**: Image automatically snaps back when released outside bounds

## Performance Considerations

**Large Images:** For images larger than 2048x2048, consider downsampling before loading to avoid OutOfMemoryError:

```java
BitmapFactory.Options options = new BitmapFactory.Options();
options.inSampleSize = 2; // Downsample by factor of 2
Bitmap bitmap = BitmapFactory.decodeFile(filePath, options);
```

**Memory:** The crop operation creates one bitmap copy of the cropped region. Recycle the original bitmap if no longer needed:

```java
Bitmap croppedBitmap = cropView.getCroppedBitmap();
originalBitmap.recycle();
```

**Threading:** Bitmap operations are synchronous. For large images on slower devices, consider running `getCroppedBitmap()` on a background thread.

## Customization

Modify constants in `src/main/java/com/pombos/pombocropview/PombosCropView.java`:

| Constant | Default | Description |
|----------|---------|-------------|
| `BOUNCE_DURATION` | 300ms | Snap-back animation duration |
| `ROTATE_DURATION` | 400ms | Rotation animation duration |
| `RESISTANCE_FACTOR` | 400f | Drag resistance strength when exceeding bounds |
| `MAX_OVERSCROLL` | 0.2f | Maximum overscroll ratio (20% of crop size) |
| `minScale` | Dynamic | Calculated to cover crop area |
| `maxScale` | 4f | Maximum zoom level |

Paint colors in `init()` method:

| Paint | Default | Usage |
|-------|---------|-------|
| `gridPaint` | 0xFFFFFFFF | Grid lines |
| `overlayPaint` | 0x80000000 | Semi-transparent overlay |
| `fabPaint` | 0x40000000 | Rotation button background |
| `fabStrokePaint` | 0xFFFFFFFF | Rotation button border |

Crop area size: Currently hardcoded to 80% of the smallest view dimension. Modify in `onSizeChanged()`:

```java
cropSize = (int) (Math.min(w, h) * 0.8f);
```

## How It Works

**Crop Area:** Calculated as 80% of the smallest view dimension, centered on screen. The remaining area is covered with a semi-transparent overlay.

**Scale Limits:** Minimum scale is dynamically calculated to ensure the image always covers the crop area. Maximum scale is 4x.

**Boundary Resistance:** When dragging beyond boundaries, translation is reduced by a resistance factor to provide tactile feedback before snap-back.

**Animations:** 
- Snap-back uses DecelerateInterpolator over 300ms
- Rotation uses DecelerateInterpolator over 400ms
- Centering uses OvershootInterpolator for spring effect

For detailed implementation, see [docs/INTERNALS.md](docs/INTERNALS.md).

## Example Activity

```java
public class MainActivity extends AppCompatActivity {
    private PombosCropView cropView;
    private static final int PICK_IMAGE = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cropView = findViewById(R.id.cropView);
        
        findViewById(R.id.selectButton).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_PICK);
            intent.setType("image/*");
            startActivityForResult(intent, PICK_IMAGE);
        });

        findViewById(R.id.cropButton).setOnClickListener(v -> {
            Bitmap result = cropView.getCroppedBitmap();
            if (result != null) {
                // Handle result
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            try (InputStream stream = getContentResolver().openInputStream(uri)) {
                Bitmap bitmap = BitmapFactory.decodeStream(stream);
                cropView.setImageBitmap(bitmap);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
```

## License

```
Copyright 2024 PomboN9878

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

## Contributing

Contributions are welcome. Open an issue to discuss proposed changes before submitting a pull request.

## Links

- [Repository](https://github.com/PomboN9878/PombosSimpleCropView)
- [Issue Tracker](https://github.com/PomboN9878/PombosSimpleCropView/issues)
- [JitPack](https://jitpack.io/#PomboN9878/PombosSimpleCropView)
