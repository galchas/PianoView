package com.chengtao.pianoview.utils;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import com.chengtao.pianoview.entity.Piano;
import com.chengtao.pianoview.entity.PianoKey;
import com.chengtao.pianoview.listener.LoadAudioMessage;
import com.chengtao.pianoview.listener.OnLoadAudioListener;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by ChengTao on 2016-11-26.
 * Modified and improved by GalCha on 2025-10-19.
 */

/**
 * 音频工具类
 */
public class AudioUtils implements LoadAudioMessage {
  //线程池,用于加载和播放音频
  private ExecutorService service = Executors.newCachedThreadPool();
  //最大音频数目
  private final static int MAX_STREAM = 11;
  private static AudioUtils instance = null;
  //消息ID
  private final static int LOAD_START = 1;
  private final static int LOAD_FINISH = 2;
  private final static int LOAD_ERROR = 3;
  private final static int LOAD_PROGRESS = 4;
  //发送进度的间隙时间
  private final static int SEND_PROGRESS_MESSAGE_BREAK_TIME = 500;
  //音频池，用于播放音频
  private SoundPool pool;
  //上下文
  private Context context;
  //加载音频接口
  private OnLoadAudioListener loadAudioListener;
  //存放黑键和白键的音频加载后的ID的集合（位置 -> sampleId）
  private SparseIntArray whiteKeyMusics = new SparseIntArray();
  private SparseIntArray blackKeyMusics = new SparseIntArray();
  // 位置 -> resId（用于按需加载）
  private SparseIntArray whiteKeyResIds = new SparseIntArray();
  private SparseIntArray blackKeyResIds = new SparseIntArray();
  // sampleId -> 已加载标记
  private SparseBooleanArray loadedSamples = new SparseBooleanArray();
  // sampleId -> 待播放次数（在加载完成后立即播放）
  private SparseIntArray pendingPlays = new SparseIntArray();
  //是否加载成功
  private boolean isLoadFinish = false;
  //是否正在加载
  private boolean isLoading = false;
  //用于处理进度消息
  private Handler handler;
  private AudioManager audioManager;
  private long currentTime;
  private int loadNum;
  private int minSoundId = -1;
  private int maxSoundId = -1;

  private AudioUtils(Context context, OnLoadAudioListener loadAudioListener, int maxStream) {
    this.context = context;
    this.loadAudioListener = loadAudioListener;
    handler = new AudioStatusHandler(context.getMainLooper());
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      pool = new SoundPool.Builder().setMaxStreams(maxStream)
          .setAudioAttributes(
              new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                  .setUsage(AudioAttributes.USAGE_MEDIA)
                  .build())
          .build();
    } else {
      pool = new SoundPool(maxStream, AudioManager.STREAM_MUSIC, 1);
    }
    audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
  }

  //单例模式，只返回一个工具实例
  public static AudioUtils getInstance(Context context, OnLoadAudioListener listener) {
    return getInstance(context, listener, MAX_STREAM);
  }

  public static AudioUtils getInstance(Context context, OnLoadAudioListener listener,
      int maxStream) {
    if (instance == null || instance.pool == null) {
      synchronized (AudioUtils.class) {
        if (instance == null || instance.pool == null) {
          instance = new AudioUtils(context, listener, maxStream);
        }
      }
    }
    return instance;
  }

  /**
   * 加载音乐
   *
   * @param piano 钢琴实体
   * @throws Exception 异常
   */
  public void loadMusic(final Piano piano) throws Exception {
    if (pool == null) {
      throw new Exception("请初始化SoundPool");
    }
    if (piano != null) {
      if (!isLoading && !isLoadFinish) {
        isLoading = true;
        pool.setOnLoadCompleteListener((soundPool, sampleId, status) -> {
          loadedSamples.put(sampleId, true);
          int pending = pendingPlays.get(sampleId, 0);
          if (pending > 0) {
            for (int i = 0; i < pending; i++) {
              play(sampleId);
            }
            pendingPlays.delete(sampleId);
          }
          loadNum++;
          if (loadNum >= Piano.PIANO_NUMS) {
            isLoadFinish = true;
            sendProgressMessage(100);
            sendFinishMessage();
            // 静音预热，避免首次播放卡顿
            if (whiteKeyMusics.size() > 0) {
              pool.play(whiteKeyMusics.get(0), 0, 0, 1, -1, 2f);
            }
          } else {
            if (System.currentTimeMillis() - currentTime >= SEND_PROGRESS_MESSAGE_BREAK_TIME) {
              sendProgressMessage((int) (((float) loadNum / (float) Piano.PIANO_NUMS) * 100f));
              currentTime = System.currentTimeMillis();
            }
          }
        });
        service.execute(() -> {
          sendStartMessage();
          // 先收集所有resId与位置映射，便于按需加载
          ArrayList<PianoKey[]> whiteKeys = piano.getWhitePianoKeys();
          int whiteKeyPos = 0;
          for (int i = 0; i < whiteKeys.size(); i++) {
            for (PianoKey key : whiteKeys.get(i)) {
              whiteKeyResIds.put(whiteKeyPos, key.getVoiceId());
              whiteKeyPos++;
            }
          }
          ArrayList<PianoKey[]> blackKeys = piano.getBlackPianoKeys();
          int blackKeyPos = 0;
          for (int i = 0; i < blackKeys.size(); i++) {
            for (PianoKey key : blackKeys.get(i)) {
              blackKeyResIds.put(blackKeyPos, key.getVoiceId());
              blackKeyPos++;
            }
          }

          // 优先加载中间音区（第4组）以提升首屏可用性
          List<Integer> whiteOrder = new ArrayList<>();
          List<Integer> blackOrder = new ArrayList<>();
          // 计算全局位置的辅助函数
          // white: group==0 -> pos, else (group-1)*7+2+pos
          // black: group==0 -> pos, else (group-1)*5+1+pos
          int middleGroup = 4; // 经验选取
          // 先加入中间组
          for (int pos = 0; pos < 7; pos++) {
            int global = (middleGroup - 1) * 7 + 2 + pos;
            whiteOrder.add(global);
          }
          for (int pos = 0; pos < 5; pos++) {
            int global = (middleGroup - 1) * 5 + 1 + pos;
            blackOrder.add(global);
          }
          // 再加入剩余位置
          for (int i = 0; i < whiteKeyResIds.size(); i++) {
            int key = whiteKeyResIds.keyAt(i);
            if (!whiteOrder.contains(key)) whiteOrder.add(key);
          }
          for (int i = 0; i < blackKeyResIds.size(); i++) {
            int key = blackKeyResIds.keyAt(i);
            if (!blackOrder.contains(key)) blackOrder.add(key);
          }

          // 分批加载，先白再黑，以保持 loadNum 语义接近原逻辑
          try {
            for (Integer idx : whiteOrder) {
              int resId = whiteKeyResIds.get(idx);
              int soundID = pool.load(context, resId, 1);
              whiteKeyMusics.put(idx, soundID);
              if (minSoundId == -1) {
                minSoundId = soundID;
              }
            }
            for (Integer idx : blackOrder) {
              int resId = blackKeyResIds.get(idx);
              int soundID = pool.load(context, resId, 1);
              blackKeyMusics.put(idx, soundID);
              if (soundID > maxSoundId) {
                maxSoundId = soundID;
              }
            }
          } catch (Exception e) {
            isLoading = false;
            sendErrorMessage(e);
          }
        });
      }
    }
  }

  /**
   * 播放音乐
   *
   * @param key 钢琴键
   */
  public void playMusic(final PianoKey key) {
    if (key != null && key.getType() != null) {
      service.execute(() -> {
        switch (key.getType()) {
          case BLACK:
            playBlackKeyMusic(key.getGroup(), key.getPositionOfGroup());
            break;
          case WHITE:
            playWhiteKeyMusic(key.getGroup(), key.getPositionOfGroup());
            break;
        }
      });
    }
  }

  /**
   * 播放白键音乐
   *
   * @param group 组数，从0开始
   * @param positionOfGroup 组内位置
   */
  private void playWhiteKeyMusic(int group, int positionOfGroup) {
    int position;
    if (group == 0) {
      position = positionOfGroup;
    } else {
      position = (group - 1) * 7 + 2 + positionOfGroup;
    }
    playByPosition(true, position);
  }

  /**
   * 播放黑键音乐
   *
   * @param group 组数，从0开始
   * @param positionOfGroup 组内位置
   */
  private void playBlackKeyMusic(int group, int positionOfGroup) {
    int position;
    if (group == 0) {
      position = positionOfGroup;
    } else {
      position = (group - 1) * 5 + 1 + positionOfGroup;
    }
    playByPosition(false, position);
  }

  private void playByPosition(boolean isWhite, int position) {
    // 获取已知的sampleId
    SparseIntArray map = isWhite ? whiteKeyMusics : blackKeyMusics;
    SparseIntArray resIds = isWhite ? whiteKeyResIds : blackKeyResIds;
    int sampleId = map.get(position, 0);
    if (sampleId != 0) {
      if (loadedSamples.get(sampleId, false)) {
        play(sampleId);
      } else {
        // 尚未完成加载，记录待播放
        int count = pendingPlays.get(sampleId, 0);
        pendingPlays.put(sampleId, count + 1);
      }
      return;
    }
    // 未加载过，按需触发加载
    int resId = resIds.get(position, 0);
    if (resId != 0) {
      int newSampleId = pool.load(context, resId, 1);
      map.put(position, newSampleId);
      int count = pendingPlays.get(newSampleId, 0);
      pendingPlays.put(newSampleId, count + 1);
    }
  }

  private void play(int soundId) {
    float volume = 1;
    if (audioManager != null) {
      float actualVolume = (float) audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
      float maxVolume = (float) audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
      volume = actualVolume / maxVolume;
    }
    if (volume <= 0) {
      volume = 1f;
    }
    pool.play(soundId, volume, volume, 1, 0, 1f);
  }

  /**
   * 结束
   */
  public void stop() {
    context = null;
    pool.release();
    pool = null;
    whiteKeyMusics.clear();
    whiteKeyMusics = null;
    blackKeyMusics.clear();
    blackKeyMusics = null;
  }

  @Override public void sendStartMessage() {
    handler.sendEmptyMessage(LOAD_START);
  }

  @Override public void sendFinishMessage() {
    handler.sendEmptyMessage(LOAD_FINISH);
  }

  @Override public void sendErrorMessage(Exception e) {
    handler.sendMessage(Message.obtain(handler, LOAD_ERROR, e));
  }

  @Override public void sendProgressMessage(int progress) {
    handler.sendMessage(Message.obtain(handler, LOAD_PROGRESS, progress));
  }

  /**
   * 自定义handler,处理加载状态
   */
  private class AudioStatusHandler extends Handler {
    AudioStatusHandler(Looper looper) {
      super(looper);
    }

    @Override public void handleMessage(Message msg) {
      super.handleMessage(msg);
      handleAudioStatusMessage(msg);
    }
  }

  private void handleAudioStatusMessage(Message msg) {
    if (loadAudioListener != null) {
      switch (msg.what) {
        case LOAD_START:
          loadAudioListener.loadPianoAudioStart();
          break;
        case LOAD_FINISH:
          loadAudioListener.loadPianoAudioFinish();
          break;
        case LOAD_ERROR:
          loadAudioListener.loadPianoAudioError((Exception) msg.obj);
          break;
        case LOAD_PROGRESS:
          loadAudioListener.loadPianoAudioProgress((int) msg.obj);
          break;
        default:
          break;
      }
    }
  }
}
