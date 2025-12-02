package com.pombos.pombocropview;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.content.ContextCompat;

public class PombosCropView extends AppCompatImageView {
    private Matrix matrix = new Matrix();
    private Matrix savedMatrix = new Matrix();

    private static final int NONE = 0;
    private static final int DRAG = 1;
    private static final int ZOOM = 2;
    private int mode = NONE;

    private float startX = 0f;
    private float startY = 0f;

    private float minScale = 0.5f;
    private float maxScale = 4f;
    private float currentScale = 1f;

    private int rotationDegrees = 0;

    private ValueAnimator bounceAnimator;
    private ValueAnimator rotateAnimator;
    private static final long BOUNCE_DURATION = 300;
    private static final long ROTATE_DURATION = 400;
    private static final float RESISTANCE_FACTOR = 400f;
    private static final float MAX_OVERSCROLL = 0.2f;

    private ScaleGestureDetector scaleDetector;

    private Paint gridPaint;
    private Paint overlayPaint;
    private Paint fabPaint;
    private Paint fabStrokePaint;
    private Drawable fabIcon;
    private float fabRadius = 60f;
    private float fabCx, fabCy;

    private int cropSize = 0;
    private int viewWidth = 0;
    private int viewHeight = 0;

    public PombosCropView(Context context) {
        super(context);
        init(context);
    }

    public PombosCropView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        setScaleType(ScaleType.MATRIX);

        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());

        gridPaint = new Paint();
        gridPaint.setColor(0xFFFFFFFF);
        gridPaint.setStrokeWidth(2f);
        gridPaint.setStyle(Paint.Style.STROKE);

        overlayPaint = new Paint();
        overlayPaint.setColor(0x80000000);
        overlayPaint.setStyle(Paint.Style.FILL);

        fabPaint = new Paint();
        fabPaint.setColor(0x40000000);
        fabPaint.setAntiAlias(true);
        fabPaint.setStyle(Paint.Style.FILL);

        fabStrokePaint = new Paint();
        fabStrokePaint.setColor(0xFFFFFFFF);
        fabStrokePaint.setStrokeWidth(3f);
        fabStrokePaint.setAntiAlias(true);
        fabStrokePaint.setStyle(Paint.Style.STROKE);

        try {
            fabIcon = ContextCompat.getDrawable(context, R.drawable.ic_rotate);
            if (fabIcon != null) {
                fabIcon.setTint(0xFFFFFFFF);
            }
        } catch (Exception e) {
            fabIcon = null;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        viewWidth = w;
        viewHeight = h;
        cropSize = (int) (Math.min(w, h) * 0.8f);
        centerImage();
        fabCx = viewWidth - fabRadius - 30f;
        fabCy = viewHeight - fabRadius - 30f;
    }

    private void centerImage() {
        if (getDrawable() == null) return;

        float imageWidth = getDrawable().getIntrinsicWidth();
        float imageHeight = getDrawable().getIntrinsicHeight();

        float effectiveWidth = imageWidth;
        float effectiveHeight = imageHeight;

        if (rotationDegrees % 180 != 0) {
            effectiveWidth = imageHeight;
            effectiveHeight = imageWidth;
        }

        minScale = Math.max(cropSize / effectiveWidth, cropSize / effectiveHeight);

        if (currentScale < minScale) {
            currentScale = minScale;
        }

        float scale = Math.max(cropSize / effectiveWidth, cropSize / effectiveHeight);

        matrix.reset();
        matrix.postScale(scale, scale);
        matrix.postRotate(rotationDegrees, (imageWidth * scale) / 2f, (imageHeight * scale) / 2f);

        RectF rect = new RectF(0, 0, imageWidth, imageHeight);
        matrix.mapRect(rect);

        int leftCrop = (viewWidth - cropSize) / 2;
        int topCrop = (viewHeight - cropSize) / 2;

        float cropCenterX = leftCrop + cropSize / 2f;
        float cropCenterY = topCrop + cropSize / 2f;

        float imageCenterX = rect.left + rect.width() / 2f;
        float imageCenterY = rect.top + rect.height() / 2f;

        float dx = cropCenterX - imageCenterX;
        float dy = cropCenterY - imageCenterY;

        matrix.postTranslate(dx, dy);
        setImageMatrix(matrix);
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        double dist = Math.sqrt(Math.pow(x - fabCx, 2) + Math.pow(y - fabCy, 2));

        if (dist <= fabRadius) {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                rotateImage();
            }
            return true;
        }

        scaleDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (bounceAnimator != null && bounceAnimator.isRunning()) {
                    bounceAnimator.cancel();
                }
                if (rotateAnimator != null && rotateAnimator.isRunning()) {
                    rotateAnimator.cancel();
                }
                savedMatrix.set(matrix);
                startX = event.getX();
                startY = event.getY();
                mode = DRAG;
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                savedMatrix.set(matrix);
                mode = ZOOM;
                break;

            case MotionEvent.ACTION_MOVE:
                if (mode == DRAG) {
                    matrix.set(savedMatrix);
                    float dx = event.getX() - startX;
                    float dy = event.getY() - startY;
                    float[] resistedOffset = applyResistance(dx, dy);
                    matrix.postTranslate(resistedOffset[0], resistedOffset[1]);
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if (mode == DRAG || mode == ZOOM) {
                    animateBounceBack();
                }
                mode = NONE;
                break;
        }

        setImageMatrix(matrix);
        invalidate();
        return true;
    }

    private float[] applyResistance(float dx, float dy) {
        if (getDrawable() == null) return new float[]{dx, dy};

        Matrix testMatrix = new Matrix(savedMatrix);
        testMatrix.postTranslate(dx, dy);

        RectF bounds = new RectF(0, 0, getDrawable().getIntrinsicWidth(), getDrawable().getIntrinsicHeight());
        testMatrix.mapRect(bounds);

        int leftCrop = (viewWidth - cropSize) / 2;
        int topCrop = (viewHeight - cropSize) / 2;
        int rightCrop = leftCrop + cropSize;
        int bottomCrop = topCrop + cropSize;

        float maxOverscroll = cropSize * MAX_OVERSCROLL;

        float resistedDx = dx;
        float resistedDy = dy;

        if (bounds.left > leftCrop) {
            float overflow = bounds.left - leftCrop;
            if (overflow > maxOverscroll) {
                resistedDx = dx - (overflow - maxOverscroll);
            } else {
                float resistance = 1f / (1f + overflow / RESISTANCE_FACTOR);
                resistedDx = dx * resistance;
            }
        } else if (bounds.right < rightCrop) {
            float overflow = rightCrop - bounds.right;
            if (overflow > maxOverscroll) {
                resistedDx = dx + (overflow - maxOverscroll);
            } else {
                float resistance = 1f / (1f + overflow / RESISTANCE_FACTOR);
                resistedDx = dx * resistance;
            }
        }

        if (bounds.top > topCrop) {
            float overflow = bounds.top - topCrop;
            if (overflow > maxOverscroll) {
                resistedDy = dy - (overflow - maxOverscroll);
            } else {
                float resistance = 1f / (1f + overflow / RESISTANCE_FACTOR);
                resistedDy = dy * resistance;
            }
        } else if (bounds.bottom < bottomCrop) {
            float overflow = bottomCrop - bounds.bottom;
            if (overflow > maxOverscroll) {
                resistedDy = dy + (overflow - maxOverscroll);
            } else {
                float resistance = 1f / (1f + overflow / RESISTANCE_FACTOR);
                resistedDy = dy * resistance;
            }
        }

        return new float[]{resistedDx, resistedDy};
    }

    private void animateBounceBack() {
        if (getDrawable() == null) return;

        float[] m = new float[9];
        matrix.getValues(m);
        float scale = (float) Math.sqrt(m[Matrix.MSCALE_X] * m[Matrix.MSCALE_X] + m[Matrix.MSKEW_X] * m[Matrix.MSKEW_X]);

        if (scale < minScale) {
            animateToCenter();
            return;
        }

        RectF bounds = new RectF(0, 0, getDrawable().getIntrinsicWidth(), getDrawable().getIntrinsicHeight());
        matrix.mapRect(bounds);

        int leftCrop = (viewWidth - cropSize) / 2;
        int topCrop = (viewHeight - cropSize) / 2;
        int rightCrop = leftCrop + cropSize;
        int bottomCrop = topCrop + cropSize;

        float transX = m[Matrix.MTRANS_X];
        float transY = m[Matrix.MTRANS_Y];
        float correctedX = transX;
        float correctedY = transY;

        boolean needsAnimation = false;

        if (bounds.left > leftCrop) {
            correctedX = transX + (leftCrop - bounds.left);
            needsAnimation = true;
        } else if (bounds.right < rightCrop) {
            correctedX = transX + (rightCrop - bounds.right);
            needsAnimation = true;
        }

        if (bounds.top > topCrop) {
            correctedY = transY + (topCrop - bounds.top);
            needsAnimation = true;
        } else if (bounds.bottom < bottomCrop) {
            correctedY = transY + (bottomCrop - bounds.bottom);
            needsAnimation = true;
        }

        if (!needsAnimation) return;

        final float startTransX = transX;
        final float startTransY = transY;
        final float endTransX = correctedX;
        final float endTransY = correctedY;

        if (bounceAnimator != null && bounceAnimator.isRunning()) {
            bounceAnimator.cancel();
        }

        bounceAnimator = ValueAnimator.ofFloat(0f, 1f);
        bounceAnimator.setDuration(BOUNCE_DURATION);
        bounceAnimator.setInterpolator(new DecelerateInterpolator());
        bounceAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float progress = (float) animation.getAnimatedValue();

                float currentTransX = startTransX + (endTransX - startTransX) * progress;
                float currentTransY = startTransY + (endTransY - startTransY) * progress;

                float[] values = new float[9];
                matrix.getValues(values);
                values[Matrix.MTRANS_X] = currentTransX;
                values[Matrix.MTRANS_Y] = currentTransY;
                matrix.setValues(values);

                setImageMatrix(matrix);
                invalidate();
            }
        });
        bounceAnimator.start();
    }

    private void animateToCenter() {
        if (getDrawable() == null) return;

        float imageWidth = getDrawable().getIntrinsicWidth();
        float imageHeight = getDrawable().getIntrinsicHeight();

        float effectiveWidth = imageWidth;
        float effectiveHeight = imageHeight;

        if (rotationDegrees % 180 != 0) {
            effectiveWidth = imageHeight;
            effectiveHeight = imageWidth;
        }

        float targetScale = Math.max(cropSize / effectiveWidth, cropSize / effectiveHeight);

        Matrix targetMatrix = new Matrix();
        targetMatrix.postScale(targetScale, targetScale);
        targetMatrix.postRotate(rotationDegrees, (imageWidth * targetScale) / 2f, (imageHeight * targetScale) / 2f);

        RectF rect = new RectF(0, 0, imageWidth, imageHeight);
        targetMatrix.mapRect(rect);

        int leftCrop = (viewWidth - cropSize) / 2;
        int topCrop = (viewHeight - cropSize) / 2;

        float cropCenterX = leftCrop + cropSize / 2f;
        float cropCenterY = topCrop + cropSize / 2f;

        float imageCenterX = rect.left + rect.width() / 2f;
        float imageCenterY = rect.top + rect.height() / 2f;

        float dx = cropCenterX - imageCenterX;
        float dy = cropCenterY - imageCenterY;

        targetMatrix.postTranslate(dx, dy);

        float[] startValues = new float[9];
        float[] endValues = new float[9];
        matrix.getValues(startValues);
        targetMatrix.getValues(endValues);

        if (bounceAnimator != null && bounceAnimator.isRunning()) {
            bounceAnimator.cancel();
        }

        bounceAnimator = ValueAnimator.ofFloat(0f, 1f);
        bounceAnimator.setDuration(BOUNCE_DURATION);
        bounceAnimator.setInterpolator(new OvershootInterpolator(0.8f));
        bounceAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float progress = (float) animation.getAnimatedValue();
                float[] currentValues = new float[9];

                for (int i = 0; i < 9; i++) {
                    currentValues[i] = startValues[i] + (endValues[i] - startValues[i]) * progress;
                }

                matrix.setValues(currentValues);
                currentScale = targetScale;
                setImageMatrix(matrix);
                invalidate();
            }
        });
        bounceAnimator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (cropSize == 0) return;

        int left = (viewWidth - cropSize) / 2;
        int top = (viewHeight - cropSize) / 2;
        int right = left + cropSize;
        int bottom = top + cropSize;

        canvas.drawRect(0, 0, viewWidth, top, overlayPaint);
        canvas.drawRect(0, bottom, viewWidth, viewHeight, overlayPaint);
        canvas.drawRect(0, top, left, bottom, overlayPaint);
        canvas.drawRect(right, top, viewWidth, bottom, overlayPaint);

        float cellWidth = cropSize / 3f;
        float cellHeight = cropSize / 3f;

        for (int i = 1; i < 3; i++) {
            float x = left + (i * cellWidth);
            canvas.drawLine(x, top, x, bottom, gridPaint);

            float y = top + (i * cellHeight);
            canvas.drawLine(left, y, right, y, gridPaint);
        }

        canvas.drawRect(left, top, right, bottom, gridPaint);

        canvas.drawCircle(fabCx, fabCy, fabRadius, fabPaint);
        canvas.drawCircle(fabCx, fabCy, fabRadius, fabStrokePaint);

        if (fabIcon != null) {
            int iconSize = (int) (fabRadius * 0.9f);
            left = (int) (fabCx - iconSize / 2f);
            top = (int) (fabCy - iconSize / 2f);
            right = left + iconSize;
            bottom = top + iconSize;

            fabIcon.setBounds(left, top, right, bottom);
            fabIcon.draw(canvas);
        }
    }

    public Bitmap getCroppedBitmap() {
        if (getDrawable() == null) return null;

        try {
            Bitmap originalBitmap = ((android.graphics.drawable.BitmapDrawable) getDrawable()).getBitmap();

            Bitmap transformedBitmap = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(transformedBitmap);

            canvas.save();
            canvas.concat(matrix);
            canvas.drawBitmap(originalBitmap, 0, 0, null);
            canvas.restore();

            int left = (viewWidth - cropSize) / 2;
            int top = (viewHeight - cropSize) / 2;

            Bitmap croppedBitmap = Bitmap.createBitmap(transformedBitmap, left, top, cropSize, cropSize);

            transformedBitmap.recycle();

            return croppedBitmap;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float scaleFactor = detector.getScaleFactor();
            float newScale = currentScale * scaleFactor;

            if (newScale <= maxScale) {
                matrix.postScale(scaleFactor, scaleFactor, detector.getFocusX(), detector.getFocusY());
                currentScale = newScale;
                setImageMatrix(matrix);
                invalidate();
            }

            return true;
        }
    }

    public void rotateImage() {
        if (getDrawable() == null) return;
        if (rotateAnimator != null && rotateAnimator.isRunning()) return;

        final int startRotation = rotationDegrees;
        final int endRotation = (rotationDegrees + 90) % 360;

        float imageWidth = getDrawable().getIntrinsicWidth();
        float imageHeight = getDrawable().getIntrinsicHeight();

        float[] startValues = new float[9];
        matrix.getValues(startValues);

        rotateAnimator = ValueAnimator.ofFloat(0f, 1f);
        rotateAnimator.setDuration(ROTATE_DURATION);
        rotateAnimator.setInterpolator(new DecelerateInterpolator());
        rotateAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float progress = (float) animation.getAnimatedValue();
                float currentRotation = startRotation + (endRotation - startRotation) * progress;

                float effectiveWidth = imageWidth;
                float effectiveHeight = imageHeight;

                if (((int) currentRotation) % 180 != 0) {
                    effectiveWidth = imageHeight;
                    effectiveHeight = imageWidth;
                }

                float scale = Math.max(cropSize / effectiveWidth, cropSize / effectiveHeight);

                Matrix tempMatrix = new Matrix();
                tempMatrix.postScale(scale, scale);
                tempMatrix.postRotate(currentRotation, (imageWidth * scale) / 2f, (imageHeight * scale) / 2f);

                RectF rect = new RectF(0, 0, imageWidth, imageHeight);
                tempMatrix.mapRect(rect);

                int leftCrop = (viewWidth - cropSize) / 2;
                int topCrop = (viewHeight - cropSize) / 2;

                float cropCenterX = leftCrop + cropSize / 2f;
                float cropCenterY = topCrop + cropSize / 2f;

                float imageCenterX = rect.left + rect.width() / 2f;
                float imageCenterY = rect.top + rect.height() / 2f;

                float dx = cropCenterX - imageCenterX;
                float dy = cropCenterY - imageCenterY;

                tempMatrix.postTranslate(dx, dy);

                matrix.set(tempMatrix);
                currentScale = scale;
                setImageMatrix(matrix);
                invalidate();
            }
        });

        rotateAnimator.addListener(new android.animation.Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(android.animation.Animator animation) {}

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                rotationDegrees = endRotation;
                minScale = Math.max(
                        cropSize / (rotationDegrees % 180 != 0 ? imageHeight : imageWidth),
                        cropSize / (rotationDegrees % 180 != 0 ? imageWidth : imageHeight)
                );
            }

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                rotationDegrees = endRotation;
            }

            @Override
            public void onAnimationRepeat(android.animation.Animator animation) {}
        });

        rotateAnimator.start();
    }

}
