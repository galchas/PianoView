package com.chengtao.pianoview.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Parcelable;
import androidx.core.content.ContextCompat;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import com.chengtao.pianoview.R;
import com.chengtao.pianoview.entity.AutoPlayEntity;
import com.chengtao.pianoview.entity.Piano;
import com.chengtao.pianoview.entity.PianoKey;
import com.chengtao.pianoview.listener.OnLoadAudioListener;
import com.chengtao.pianoview.listener.OnPianoAutoPlayListener;
import com.chengtao.pianoview.listener.OnPianoListener;
import com.chengtao.pianoview.utils.AudioUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Created by ChengTao on 2016-11-25.
 * Modified and improved by GalCha on 2025-10-19.
 */

public class PianoView extends View {
  private final static String TAG = "PianoView";
  // Define piano keys
  private Piano piano = null;
  private ArrayList<PianoKey[]> whitePianoKeys;
  private ArrayList<PianoKey[]> blackPianoKeys;
  // Pressed piano keys
  private CopyOnWriteArrayList<PianoKey> pressedKeys = new CopyOnWriteArrayList<>();
  // Paint object
  private Paint paint;
  // Square used to display the note name
  private RectF square;
  // Background colors for the note-name square
  // Default octave colors (pastel palette)
  // Order: octave 1..9
  private String pianoColors[] = {
      "#F8BBD0", // pastel pink
      "#B3E5FC", // pastel light blue
      "#C8E6C9", // pastel green
      "#FFECB3", // pastel yellow
      "#D1C4E9", // pastel purple
      "#FFE0B2", // pastel orange
      "#B2DFDB", // pastel teal
      "#DCEDC8", // pastel lime
      "#F0F4C3"  // pastel light lime
  };
  // Cached parsed colors to avoid Color.parseColor in onDraw loop
  private int[] pianoColorsInt = null;
  // Audio player utility
  private AudioUtils utils = null;
  // Context
  private Context context;
  // Layout width
  private int layoutWidth = 0;
  // Vertical (height) scale factor, derived from the view height
  private float scaleY = 1;
  // Horizontal (width) scale factor, derived from the width mode
  private float scaleX = 1;
  // Number of white keys on an 88-key piano (used for fit-to-width math)
  private static final int WHITE_KEY_COUNT = 52;

  /**
   * Controls how the keyboard width (key width) is computed, independently of height.
   */
  public enum WidthMode {
    // Current behavior: intrinsic key width, horizontally scrollable.
    INTRINSIC,
    // All white keys span the full view width (no horizontal scrolling).
    FIT_WIDTH,
    // A fixed number of white keys span the full view width.
    FIXED_VISIBLE_KEYS
  }

  // Active width mode (default reproduces the original behavior)
  private WidthMode widthMode = WidthMode.INTRINSIC;
  // Number of visible white keys for FIXED_VISIBLE_KEYS mode
  private int visibleWhiteKeyCount = 0;
  // Geometry actually used to build the current Piano (for rebuild detection)
  private float builtScaleX = -1f;
  private float builtScaleY = -1f;
  // Audio loading listener
  private OnLoadAudioListener loadAudioListener;
  // Auto-play listener
  private OnPianoAutoPlayListener autoPlayListener;
  // Piano event listener
  private OnPianoListener pianoListener;
  // Scroll progress properties for the piano view
  private int progress = 0;
  // Whether keys can be pressed
  private boolean canPress = true;
  // Whether auto-play is running
  private boolean isAutoPlaying = false;
  // Initialization finished flag
  private boolean isInitFinish = false;
  private int minRange = 0;
  private int maxRange = 0;
  //
  private int maxStream;
  // Auto-play Handler
  private Handler autoPlayHandler = new Handler(Looper.myLooper()) {
    @Override public void handleMessage(Message msg) {
      handleAutoPlay(msg);
    }
  };
  // Message IDs
  private static final int HANDLE_AUTO_PLAY_START = 0;
  private static final int HANDLE_AUTO_PLAY_END = 1;
  private static final int HANDLE_AUTO_PLAY_BLACK_DOWN = 2;
  private static final int HANDLE_AUTO_PLAY_WHITE_DOWN = 3;
  private static final int HANDLE_AUTO_PLAY_KEY_UP = 4;

  // Constructors
  public PianoView(Context context) {
    this(context, null);
  }

  public PianoView(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public PianoView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    this.context = context;
    paint = new Paint();
    paint.setAntiAlias(true);
    // Initialize paint
    paint.setStyle(Paint.Style.FILL);
    // Initialize the note-name square rect
    square = new RectF();
    // Pre-parse default colors to ints
    parsePianoColorsIfNeeded();
    // Read XML attributes (width mode / visible white keys)
    if (attrs != null) {
      android.content.res.TypedArray a =
          context.obtainStyledAttributes(attrs, R.styleable.PianoView, defStyleAttr, 0);
      try {
        boolean hasMode = a.hasValue(R.styleable.PianoView_keyboardWidthMode);
        int modeValue = a.getInt(R.styleable.PianoView_keyboardWidthMode, 0);
        int visibleKeys = a.getInt(R.styleable.PianoView_visibleWhiteKeys, 0);
        if (visibleKeys > 0) {
          visibleWhiteKeyCount = visibleKeys;
        }
        if (hasMode) {
          widthMode = widthModeFromAttr(modeValue);
        } else if (visibleKeys > 0) {
          // visibleWhiteKeys implies fixed-visible-keys mode unless overridden.
          widthMode = WidthMode.FIXED_VISIBLE_KEYS;
        }
      } finally {
        a.recycle();
      }
    }
  }

  private WidthMode widthModeFromAttr(int value) {
    switch (value) {
      case 1:
        return WidthMode.FIT_WIDTH;
      case 2:
        return WidthMode.FIXED_VISIBLE_KEYS;
      default:
        return WidthMode.INTRINSIC;
    }
  }

  @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    Drawable whiteKeyDrawable = ContextCompat.getDrawable(context, R.drawable.white_piano_key);
    // Minimum height
    int whiteKeyHeight = whiteKeyDrawable.getIntrinsicHeight();
    // Get measured width/height and their modes
    int width = MeasureSpec.getSize(widthMeasureSpec);
    int heightMode = MeasureSpec.getMode(heightMeasureSpec);
    int height = MeasureSpec.getSize(heightMeasureSpec);
    // Adjust height based on mode
    switch (heightMode) {
      case MeasureSpec.AT_MOST:
        height = Math.min(height, whiteKeyHeight);
        break;
      case MeasureSpec.UNSPECIFIED:
        height = whiteKeyHeight;
        break;
      default:
        break;
    }
    // Compute vertical scale factor from the available height
    scaleY = (float) (height - getPaddingTop() - getPaddingBottom()) / (float) (whiteKeyHeight);
    layoutWidth = width - getPaddingLeft() - getPaddingRight();
    // Compute horizontal scale factor from the active width mode
    int intrinsicWhiteKeyWidth = whiteKeyDrawable.getIntrinsicWidth();
    int totalIntrinsicWhiteWidth = WHITE_KEY_COUNT * intrinsicWhiteKeyWidth;
    switch (widthMode) {
      case FIT_WIDTH:
        scaleX = (totalIntrinsicWhiteWidth > 0 && layoutWidth > 0)
            ? (float) layoutWidth / (float) totalIntrinsicWhiteWidth
            : 1f;
        break;
      case FIXED_VISIBLE_KEYS:
        int visible = visibleWhiteKeyCount > 0 ? visibleWhiteKeyCount : WHITE_KEY_COUNT;
        int visibleIntrinsicWidth = visible * intrinsicWhiteKeyWidth;
        scaleX = (visibleIntrinsicWidth > 0 && layoutWidth > 0)
            ? (float) layoutWidth / (float) visibleIntrinsicWidth
            : 1f;
        break;
      case INTRINSIC:
      default:
        // Reproduce the original behavior exactly (intrinsic key width).
        scaleX = 1f;
        break;
    }
    // Set measured dimensions
    setMeasuredDimension(width, height);
  }

  @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    // Build (or rebuild) the keyboard now that the size/scale is known
    ensurePiano();
  }

  /**
   * 构建钢琴键盘。在首次测量后以及当几何参数(scaleX/scaleY/宽度模式)发生变化时调用，
   * 以便后续的宽度/高度变化能够正确地重新布局。重建时保留按下的琴键和播放状态，
   * 音频只在首次构建时加载(音频与几何无关)。
   */
  private void ensurePiano() {
    if (scaleX <= 0 || scaleY <= 0 || layoutWidth <= 0) {
      return;
    }
    boolean geometryChanged =
        piano == null || builtScaleX != scaleX || builtScaleY != scaleY;
    if (!geometryChanged) {
      return;
    }
    // Capture currently pressed keys so we can restore them after the rebuild
    ArrayList<int[]> pressedIds = new ArrayList<>();
    for (PianoKey key : pressedKeys) {
      pressedIds.add(
          new int[] { key.getType().getValue(), key.getGroup(), key.getPositionOfGroup() });
    }
    pressedKeys.clear();

    piano = new Piano(context, scaleX, scaleY);
    whitePianoKeys = piano.getWhitePianoKeys();
    blackPianoKeys = piano.getBlackPianoKeys();
    builtScaleX = scaleX;
    builtScaleY = scaleY;

    // Load audio only once; it is keyed by voice/position and geometry-independent
    if (utils == null) {
      if (maxStream > 0) {
        utils = AudioUtils.getInstance(getContext(), loadAudioListener, maxStream);
      } else {
        utils = AudioUtils.getInstance(getContext(), loadAudioListener);
      }
      try {
        utils.loadMusic(piano);
      } catch (Exception e) {
        Log.e(TAG, e.getMessage());
      }
    }

    // Restore pressed state onto the freshly built keys (without replaying audio)
    if (!pressedIds.isEmpty()) {
      restorePressedKeys(pressedIds);
    }

    // Re-apply the current scroll position against the new geometry
    minRange = 0;
    maxRange = layoutWidth;
    if (progress != 0) {
      scroll(progress);
    }
    invalidate();
  }

  private void restorePressedKeys(ArrayList<int[]> pressedIds) {
    restorePressedKeysIn(whitePianoKeys, pressedIds);
    restorePressedKeysIn(blackPianoKeys, pressedIds);
  }

  private void restorePressedKeysIn(ArrayList<PianoKey[]> keys, ArrayList<int[]> pressedIds) {
    if (keys == null) {
      return;
    }
    for (int i = 0; i < keys.size(); i++) {
      for (PianoKey key : keys.get(i)) {
        for (int[] id : pressedIds) {
          if (key.getType().getValue() == id[0]
              && key.getGroup() == id[1]
              && key.getPositionOfGroup() == id[2]) {
            key.getKeyDrawable().setState(new int[] { android.R.attr.state_pressed });
            key.setPressed(true);
            pressedKeys.add(key);
            break;
          }
        }
      }
    }
  }

  @Override protected void onDraw(Canvas canvas) {
    // Piano and audio initialized in onSizeChanged()/ensurePiano()
    // Rebuild if a runtime geometry change (e.g. width mode) re-measured scaleX/scaleY
    ensurePiano();
    // Draw white keys and note labels
    if (whitePianoKeys != null) {
      for (int i = 0; i < whitePianoKeys.size(); i++) {
        for (PianoKey key : whitePianoKeys.get(i)) {
          // Use cached parsed colors
          if (pianoColorsInt != null && i < pianoColorsInt.length) {
            paint.setColor(pianoColorsInt[i]);
          } else {
            paint.setColor(Color.parseColor(pianoColors[i]));
          }
          key.getKeyDrawable().draw(canvas);
          // Initialize note-name area
          Rect r = key.getKeyDrawable().getBounds();
          int sideLength = (r.right - r.left) / 2;
          int left = r.left + sideLength / 2;
          int top = r.bottom - sideLength - sideLength / 3;
          int right = r.right - sideLength / 2;
          int bottom = r.bottom - sideLength / 3;
          square.set(left, top, right, bottom);
          canvas.drawRoundRect(square, 6f, 6f, paint);
          paint.setColor(Color.BLACK);
          paint.setTextSize(sideLength / 1.8f);
          Paint.FontMetricsInt fontMetrics = paint.getFontMetricsInt();
          int baseline =
              (int) ((square.bottom + square.top - fontMetrics.bottom - fontMetrics.top) / 2);
          paint.setTextAlign(Paint.Align.CENTER);
          canvas.drawText(key.getLetterName(), square.centerX(), baseline, paint);
        }
      }
    }
    // Draw black keys
    if (blackPianoKeys != null) {
      for (int i = 0; i < blackPianoKeys.size(); i++) {
        for (PianoKey key : blackPianoKeys.get(i)) {
          key.getKeyDrawable().draw(canvas);
        }
      }
    }
    if (!isInitFinish && piano != null && pianoListener != null) {
      isInitFinish = true;
      pianoListener.onPianoInitFinish();
    }
  }

  @Override public boolean onTouchEvent(MotionEvent event) {
    int action = event.getActionMasked();
    if (!canPress) {
      return false;
    }
    switch (action) {
      //当第一个手指点击按键的时候
      case MotionEvent.ACTION_DOWN:
        //多点触控，当其他手指点击键盘的手
      case MotionEvent.ACTION_POINTER_DOWN:
        handleDown(event.getActionIndex(), event);
        break;
      //当手指在键盘上滑动的时候
      case MotionEvent.ACTION_MOVE:
        for (int i = 0; i < event.getPointerCount(); i++) {
          handleMove(i, event);
        }
        for (int i = 0; i < event.getPointerCount(); i++) {
          handleDown(i, event);
        }
        break;
      //多点触控，当其他手指抬起的时候
      case MotionEvent.ACTION_POINTER_UP:
        handlePointerUp(event.getPointerId(event.getActionIndex()));
        break;
      //但最后一个手指抬起的时候
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        handleUp();
        return false;
      default:
        break;
    }
    return true;
  }

  /**
   * 处理按下事件
   *
   * @param which 那个触摸点
   * @param event 事件对象
   */
  private void handleDown(int which, MotionEvent event) {
    int x = (int) event.getX(which) + this.getScrollX();
    int y = (int) event.getY(which);
    //检查白键
    for (int i = 0; i < whitePianoKeys.size(); i++) {
      for (PianoKey key : whitePianoKeys.get(i)) {
        if (!key.isPressed() && key.contains(x, y)) {
          handleWhiteKeyDown(which, event, key);
        }
      }
    }
    //检查黑键
    for (int i = 0; i < blackPianoKeys.size(); i++) {
      for (PianoKey key : blackPianoKeys.get(i)) {
        if (!key.isPressed() && key.contains(x, y)) {
          handleBlackKeyDown(which, event, key);
        }
      }
    }
  }

  /**
   * 处理白键点击
   *
   * @param which 那个触摸点
   * @param event 事件
   * @param key 钢琴按键
   */
  private void handleWhiteKeyDown(int which, MotionEvent event, PianoKey key) {
    key.getKeyDrawable().setState(new int[] { android.R.attr.state_pressed });
    key.setPressed(true);
    if (event != null) {
      key.setFingerID(event.getPointerId(which));
    }
    pressedKeys.add(key);
    invalidate(key.getKeyDrawable().getBounds());
    utils.playMusic(key);
    if (pianoListener != null) {
      pianoListener.onPianoClick(key.getType(), key.getVoice(), key.getGroup(),
          key.getPositionOfGroup());
    }
  }

  /**
   * 处理黑键点击
   *
   * @param which 那个触摸点
   * @param event 事件
   * @param key 钢琴按键
   */
  private void handleBlackKeyDown(int which, MotionEvent event, PianoKey key) {
    key.getKeyDrawable().setState(new int[] { android.R.attr.state_pressed });
    key.setPressed(true);
    if (event != null) {
      key.setFingerID(event.getPointerId(which));
    }
    pressedKeys.add(key);
    invalidate(key.getKeyDrawable().getBounds());
    utils.playMusic(key);
    if (pianoListener != null) {
      pianoListener.onPianoClick(key.getType(), key.getVoice(), key.getGroup(),
          key.getPositionOfGroup());
    }
  }

  /**
   * 处理滑动
   *
   * @param which 触摸点下标
   * @param event 事件对象
   */
  private void handleMove(int which, MotionEvent event) {
    int x = (int) event.getX(which) + this.getScrollX();
    int y = (int) event.getY(which);
    for (PianoKey key : pressedKeys) {
      if (key.getFingerID() == event.getPointerId(which)) {
        if (!key.contains(x, y)) {
          key.getKeyDrawable().setState(new int[] { -android.R.attr.state_pressed });
          invalidate(key.getKeyDrawable().getBounds());
          key.setPressed(false);
          key.resetFingerID();
          pressedKeys.remove(key);
        }
      }
    }
  }

  /**
   * 处理多点触控时，手指抬起事件
   *
   * @param pointerId 触摸点ID
   */
  private void handlePointerUp(int pointerId) {
    for (PianoKey key : pressedKeys) {
      if (key.getFingerID() == pointerId) {
        key.setPressed(false);
        key.resetFingerID();
        key.getKeyDrawable().setState(new int[] { -android.R.attr.state_pressed });
        invalidate(key.getKeyDrawable().getBounds());
        pressedKeys.remove(key);
        break;
      }
    }
  }

  /**
   * 处理最后一个手指抬起事件
   */
  private void handleUp() {
    if (pressedKeys.size() > 0) {
      for (PianoKey key : pressedKeys) {
        key.getKeyDrawable().setState(new int[] { -android.R.attr.state_pressed });
        key.setPressed(false);
        invalidate(key.getKeyDrawable().getBounds());
      }
      pressedKeys.clear();
    }
  }

  //-----公共方法

  /**
   * 自动播放
   *
   * @param autoPlayEntities 自动播放实体列表
   */
  public void autoPlay(final List<AutoPlayEntity> autoPlayEntities) {
    if (isAutoPlaying) {
      return;
    }
    isAutoPlaying = true;
    setCanPress(false);
    new Thread() {
      @Override public void run() {
        //开始
        if (autoPlayHandler != null) {
          autoPlayHandler.sendEmptyMessage(HANDLE_AUTO_PLAY_START);
        }
        //播放
        try {
          if (autoPlayEntities != null) {
            for (AutoPlayEntity entity : autoPlayEntities) {
              if (entity != null) {
                if (entity.getType() != null) {
                  switch (entity.getType()) {
                    case BLACK://黑键
                      PianoKey blackKey = null;
                      if (entity.getGroup() == 0) {
                        if (entity.getPosition() == 0) {
                          blackKey = blackPianoKeys.get(0)[0];
                        }
                      } else if (entity.getGroup() > 0 && entity.getGroup() <= 7) {
                        if (entity.getPosition() >= 0 && entity.getPosition() <= 4) {
                          blackKey = blackPianoKeys.get(entity.getGroup())[entity.getPosition()];
                        }
                      }
                      if (blackKey != null) {
                        Message msg = Message.obtain();
                        msg.what = HANDLE_AUTO_PLAY_BLACK_DOWN;
                        msg.obj = blackKey;
                        autoPlayHandler.sendMessage(msg);
                      }
                      break;
                    case WHITE://白键
                      PianoKey whiteKey = null;
                      if (entity.getGroup() == 0) {
                        if (entity.getPosition() == 0) {
                          whiteKey = whitePianoKeys.get(0)[0];
                        } else if (entity.getPosition() == 1) {
                          whiteKey = whitePianoKeys.get(0)[1];
                        }
                      } else if (entity.getGroup() >= 0 && entity.getGroup() <= 7) {
                        if (entity.getPosition() >= 0 && entity.getPosition() <= 6) {
                          whiteKey = whitePianoKeys.get(entity.getGroup())[entity.getPosition()];
                        }
                      } else if (entity.getGroup() == 8) {
                        if (entity.getPosition() == 0) {
                          whiteKey = whitePianoKeys.get(8)[0];
                        }
                      }
                      if (whiteKey != null) {
                        Message msg = Message.obtain();
                        msg.what = HANDLE_AUTO_PLAY_WHITE_DOWN;
                        msg.obj = whiteKey;
                        autoPlayHandler.sendMessage(msg);
                      }
                      break;
                    default:
                      break;
                  }
                }
                Thread.sleep(entity.getCurrentBreakTime() / 2);
                autoPlayHandler.sendEmptyMessage(HANDLE_AUTO_PLAY_KEY_UP);
                Thread.sleep(entity.getCurrentBreakTime() / 2);
              }
            }
          }
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
        //结束
        if (autoPlayHandler != null) {
          autoPlayHandler.sendEmptyMessage(HANDLE_AUTO_PLAY_END);
        }
      }
    }.start();
  }

  /**
   * 释放自动播放
   */
  public void releaseAutoPlay() {
    if (utils != null) {
      utils.stop();
    }
  }

  /**
   * 获取钢琴控件的总长度
   *
   * @return 钢琴控件的总长度
   */
  public int getPianoWidth() {
    if (piano != null) {
      return piano.getPianoWith();
    }
    return 0;
  }

  /**
   * 获取钢琴布局的实际宽度
   *
   * @return 钢琴布局的实际宽度
   */
  public int getLayoutWidth() {
    return layoutWidth;
  }

  /**
   * 设置键盘宽度模式(独立于高度控制键宽)。
   * <ul>
   *   <li>{@link WidthMode#INTRINSIC}: 原始行为,键使用固有宽度,可水平滚动。</li>
   *   <li>{@link WidthMode#FIT_WIDTH}: 所有白键铺满视图宽度,无水平滚动。</li>
   *   <li>{@link WidthMode#FIXED_VISIBLE_KEYS}: 指定数量的白键铺满视图宽度。</li>
   * </ul>
   *
   * @param mode 宽度模式
   */
  public void setKeyboardWidthMode(WidthMode mode) {
    if (mode == null || mode == widthMode) {
      return;
    }
    widthMode = mode;
    requestLayout();
    invalidate();
  }

  /**
   * 获取当前键盘宽度模式
   *
   * @return 当前宽度模式
   */
  public WidthMode getKeyboardWidthMode() {
    return widthMode;
  }

  /**
   * 设置在视图宽度内铺满的白键数量,独立于高度。
   * 等价于将宽度模式设置为 {@link WidthMode#FIXED_VISIBLE_KEYS}。
   *
   * @param count 白键数量(必须大于0)
   */
  public void setVisibleWhiteKeyCount(int count) {
    if (count <= 0) {
      return;
    }
    visibleWhiteKeyCount = count;
    widthMode = WidthMode.FIXED_VISIBLE_KEYS;
    requestLayout();
    invalidate();
  }

  /**
   * 设置显示音名的矩形的颜色<br>
   * <b>注:一共9中颜色</b>
   *
   * @param pianoColors 颜色数组，长度为9
   */
  public void setPianoColors(String[] pianoColors) {
    if (pianoColors.length == 9) {
      this.pianoColors = pianoColors;
      this.pianoColorsInt = null;
      parsePianoColorsIfNeeded();
      invalidate();
    }
  }

  /**
   * 设置是否可点击
   *
   * @param canPress 是否可点击
   */
  public void setCanPress(boolean canPress) {
    this.canPress = canPress;
  }

  /**
   * 移动
   *
   * @param progress 移动百分比
   */
  public void scroll(int progress) {
    int scrollableWidth = getPianoWidth() - getLayoutWidth();
    if (scrollableWidth < 0) {
      scrollableWidth = 0;
    }
    int x;
    switch (progress) {
      case 0:
        x = 0;
        break;
      case 100:
        x = scrollableWidth;
        break;
      default:
        x = (int) (((float) progress / 100f) * (float) scrollableWidth);
        break;
    }
    minRange = x;
    maxRange = x + getLayoutWidth();
    this.scrollTo(x, 0);
    this.progress = progress;
  }

  /**
   * 设置soundPool maxStream
   *
   * @param maxStream maxStream
   */
  public void setSoundPollMaxStream(int maxStream) {
    this.maxStream = maxStream;
  }

  //接口

  /**
   * 初始化钢琴相关界面
   *
   * @param pianoListener 钢琴接口
   */
  public void setPianoListener(OnPianoListener pianoListener) {
    this.pianoListener = pianoListener;
  }

  /**
   * 设置加载音频接口
   *
   * @param loadAudioListener 　音频接口
   */
  public void setLoadAudioListener(OnLoadAudioListener loadAudioListener) {
    this.loadAudioListener = loadAudioListener;
  }

  /**
   * 设置自动播放接口
   *
   * @param autoPlayListener 　自动播放接口
   */
  public void setAutoPlayListener(OnPianoAutoPlayListener autoPlayListener) {
    this.autoPlayListener = autoPlayListener;
  }

  //-----私有方法

  // Parse and cache pianoColors into ints to avoid Color.parseColor in onDraw
  private void parsePianoColorsIfNeeded() {
    if (pianoColorsInt == null && pianoColors != null) {
      pianoColorsInt = new int[pianoColors.length];
      for (int i = 0; i < pianoColors.length; i++) {
        try {
          pianoColorsInt[i] = Color.parseColor(pianoColors[i]);
        } catch (Exception e) {
          pianoColorsInt[i] = Color.GRAY;
        }
      }
    }
  }

  /**
   * 将dp装换成px
   *
   * @param dp dp值
   * @return px值
   */
  private int dpToPx(int dp) {
    DisplayMetrics displayMetrics = getContext().getResources().getDisplayMetrics();
    return Math.round(dp * (displayMetrics.xdpi / DisplayMetrics.DENSITY_DEFAULT));
  }

  /**
   * 处理自动播放
   *
   * @param msg 消息实体
   */
  private void handleAutoPlay(Message msg) {
    switch (msg.what) {
      case HANDLE_AUTO_PLAY_BLACK_DOWN://播放黑键
        if (msg.obj != null) {
          try {
            PianoKey key = (PianoKey) msg.obj;
            autoScroll(key);
            handleBlackKeyDown(-1, null, key);
          } catch (Exception e) {
            Log.e("TAG", "黑键对象有问题:" + e.getMessage());
          }
        }
        break;
      case HANDLE_AUTO_PLAY_WHITE_DOWN://播放白键
        if (msg.obj != null) {
          try {
            PianoKey key = (PianoKey) msg.obj;
            autoScroll(key);
            handleWhiteKeyDown(-1, null, key);
          } catch (Exception e) {
            Log.e("TAG", "白键对象有问题:" + e.getMessage());
          }
        }
        break;
      case HANDLE_AUTO_PLAY_KEY_UP:
        handleUp();
        break;
      case HANDLE_AUTO_PLAY_START://开始
        if (autoPlayListener != null) {
          autoPlayListener.onPianoAutoPlayStart();
        }
        break;
      case HANDLE_AUTO_PLAY_END://结束
        isAutoPlaying = false;
        setCanPress(true);
        if (autoPlayListener != null) {
          autoPlayListener.onPianoAutoPlayEnd();
        }
        break;
    }
  }

  /**
   * 自动滚动
   *
   * @param key 　钢琴键
   */
  private void autoScroll(PianoKey key) {
    if (isAutoPlaying) {//正在自动播放
      if (key != null) {
        Rect[] areas = key.getAreaOfKey();
        if (areas != null && areas.length > 0 && areas[0] != null) {
          int left = areas[0].left, right = key.getAreaOfKey()[0].right;
          for (int i = 1; i < areas.length; i++) {
            if (areas[i] != null) {
              if (areas[i].left < left) {
                left = areas[i].left;
              }
              if (areas[i].right > right) {
                right = areas[i].right;
              }
            }
          }
          if (left < minRange || right > maxRange) {//不在当前可见区域的范围之类
            int pianoWidth = getPianoWidth();
            if (pianoWidth > 0) {
              int progress = (int) ((float) left * 100 / (float) pianoWidth);
              scroll(progress);
            }
          }
        }
      }
    }
  }

  @Override protected void onRestoreInstanceState(Parcelable state) {
    super.onRestoreInstanceState(state);
    postDelayed(() -> scroll(progress), 200);
  }
}
