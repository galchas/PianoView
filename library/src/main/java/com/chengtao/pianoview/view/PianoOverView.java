package com.chengtao.pianoview.view;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import com.chengtao.pianoview.R;
import java.lang.ref.WeakReference;
import java.util.List;

/**
 * 钢琴键盘的缩略图(minimap)概览视图。
 *
 * <p>它绘制整个 88 键键盘的缩小版，并用一个高亮矩形标记 {@link PianoView}
 * 当前可见的区域。可以拖动该高亮矩形(或点按)来滚动钢琴；当钢琴通过其他方式
 * (SeekBar、箭头、自动播放)滚动时，高亮区域也会同步更新。
 *
 * <p>用法:
 * <pre>
 *   pianoOverView.attachTo(pianoView);
 * </pre>
 *
 * Created by GalCha.
 */
public class PianoOverView extends View {
  private WeakReference<PianoView> pianoRef;

  // Cached scroll state pushed by the PianoView scroll listener
  private int scrollX = 0;
  private int pianoWidth = 0;
  private int layoutWidth = 0;

  // Cached key geometry (refreshed when the keyboard geometry changes)
  private List<Rect> whiteKeyBounds;
  private List<Rect> blackKeyBounds;
  private int boundsForPianoWidth = -1;
  private int keyboardHeightPx = 0;

  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF rectF = new RectF();

  private int backgroundColor = Color.parseColor("#1F000000");
  private int whiteKeyColor = Color.parseColor("#FFFFFF");
  private int blackKeyColor = Color.parseColor("#333333");
  private int highlightColor = Color.parseColor("#552196F3");
  private int highlightBorderColor = Color.parseColor("#2196F3");

  private final PianoView.OnPianoScrollListener scrollListener =
      new PianoView.OnPianoScrollListener() {
        @Override public void onPianoScroll(int scrollX, int pianoWidth, int layoutWidth) {
          PianoOverView.this.scrollX = scrollX;
          PianoOverView.this.pianoWidth = pianoWidth;
          PianoOverView.this.layoutWidth = layoutWidth;
          invalidate();
        }
      };

  public PianoOverView(Context context) {
    this(context, null);
  }

  public PianoOverView(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public PianoOverView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    if (attrs != null) {
      TypedArray a =
          context.obtainStyledAttributes(attrs, R.styleable.PianoOverView, defStyleAttr, 0);
      try {
        backgroundColor =
            a.getColor(R.styleable.PianoOverView_overviewBackgroundColor, backgroundColor);
        whiteKeyColor = a.getColor(R.styleable.PianoOverView_overviewWhiteKeyColor, whiteKeyColor);
        blackKeyColor = a.getColor(R.styleable.PianoOverView_overviewBlackKeyColor, blackKeyColor);
        highlightColor =
            a.getColor(R.styleable.PianoOverView_overviewHighlightColor, highlightColor);
        highlightBorderColor =
            a.getColor(R.styleable.PianoOverView_overviewHighlightBorderColor, highlightBorderColor);
      } finally {
        a.recycle();
      }
    }
  }

  /**
   * 将该概览视图与一个 {@link PianoView} 关联起来。
   *
   * @param piano 要观察并控制的钢琴视图
   */
  public void attachTo(PianoView piano) {
    this.pianoRef = new WeakReference<>(piano);
    // Force a fresh geometry fetch on next draw.
    boundsForPianoWidth = -1;
    if (piano != null) {
      // Registering immediately pushes the current scroll state to us.
      piano.addOnPianoScrollListener(scrollListener);
    }
    invalidate();
  }

  @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    int width = resolveSize(dpToPx(240), widthMeasureSpec);
    int height = resolveSize(dpToPx(40), heightMeasureSpec);
    setMeasuredDimension(width, height);
  }

  @Override protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    int width = getWidth() - getPaddingLeft() - getPaddingRight();
    int height = getHeight() - getPaddingTop() - getPaddingBottom();
    if (width <= 0 || height <= 0) {
      return;
    }
    // Background strip
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(backgroundColor);
    canvas.drawRect(getPaddingLeft(), getPaddingTop(), getPaddingLeft() + width,
        getPaddingTop() + height, paint);

    refreshGeometryIfNeeded();
    if (pianoWidth <= 0 || keyboardHeightPx <= 0) {
      return;
    }

    float scaleX = (float) width / (float) pianoWidth;
    float scaleY = (float) height / (float) keyboardHeightPx;
    int left = getPaddingLeft();
    int top = getPaddingTop();

    // Miniature white keys
    paint.setColor(whiteKeyColor);
    if (whiteKeyBounds != null) {
      for (Rect r : whiteKeyBounds) {
        rectF.set(left + r.left * scaleX, top + r.top * scaleY, left + r.right * scaleX,
            top + r.bottom * scaleY);
        canvas.drawRect(rectF, paint);
      }
    }
    // Miniature black keys
    paint.setColor(blackKeyColor);
    if (blackKeyBounds != null) {
      for (Rect r : blackKeyBounds) {
        rectF.set(left + r.left * scaleX, top + r.top * scaleY, left + r.right * scaleX,
            top + r.bottom * scaleY);
        canvas.drawRect(rectF, paint);
      }
    }

    // Visible-window highlight
    int visibleWidth = layoutWidth > 0 ? layoutWidth : pianoWidth;
    float hl = left + scrollX * scaleX;
    float hr = left + (scrollX + visibleWidth) * scaleX;
    float maxRight = left + width;
    if (hr > maxRight) {
      hr = maxRight;
    }
    rectF.set(hl, top, hr, top + height);
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(highlightColor);
    canvas.drawRect(rectF, paint);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(Math.max(2f, dpToPx(1)));
    paint.setColor(highlightBorderColor);
    canvas.drawRect(rectF, paint);
    paint.setStyle(Paint.Style.FILL);
  }

  private void refreshGeometryIfNeeded() {
    PianoView piano = pianoRef != null ? pianoRef.get() : null;
    if (piano == null) {
      return;
    }
    // Always pull the latest geometry so mode/size changes are reflected.
    int currentPianoWidth = piano.getPianoWidth();
    int currentLayoutWidth = piano.getLayoutWidth();
    if (currentPianoWidth > 0) {
      pianoWidth = currentPianoWidth;
    }
    if (currentLayoutWidth > 0) {
      layoutWidth = currentLayoutWidth;
    }
    if (whiteKeyBounds == null || boundsForPianoWidth != pianoWidth) {
      whiteKeyBounds = piano.getWhiteKeyDrawBounds();
      blackKeyBounds = piano.getBlackKeyDrawBounds();
      boundsForPianoWidth = pianoWidth;
      keyboardHeightPx = 0;
      if (whiteKeyBounds != null) {
        for (Rect r : whiteKeyBounds) {
          if (r.bottom > keyboardHeightPx) {
            keyboardHeightPx = r.bottom;
          }
        }
      }
    }
  }

  @Override public boolean onTouchEvent(MotionEvent event) {
    PianoView piano = pianoRef != null ? pianoRef.get() : null;
    if (piano == null || pianoWidth <= 0) {
      return super.onTouchEvent(event);
    }
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
      case MotionEvent.ACTION_MOVE:
        getParent().requestDisallowInterceptTouchEvent(true);
        int width = getWidth() - getPaddingLeft() - getPaddingRight();
        if (width <= 0) {
          return true;
        }
        float scaleX = (float) width / (float) pianoWidth;
        float touchX = event.getX() - getPaddingLeft();
        int visibleWidth = layoutWidth > 0 ? layoutWidth : pianoWidth;
        // Center the visible window on the touch point.
        int pianoPixelX = Math.round(touchX / scaleX - visibleWidth / 2f);
        piano.scrollToPixel(pianoPixelX);
        return true;
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        getParent().requestDisallowInterceptTouchEvent(false);
        return true;
      default:
        return super.onTouchEvent(event);
    }
  }

  private int dpToPx(int dp) {
    DisplayMetrics dm = getResources().getDisplayMetrics();
    return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, dm));
  }
}
