/*
 * Copyright (C) 2013 Charon Chui <charon.chui@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.charon.video.view;

import java.io.IOException;

import com.charon.video.view.VideoView.OnStateChangeListener.State;

import android.R;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup.LayoutParams;

/**
 * A custom video view use system media player
 */
public class VideoView extends SurfaceView implements
		MediaController.MediaPlayerControl {
	private static final String TAG = "VideoView";

	/**
	 * True when playing live stream.
	 */
	private boolean isLive;

	/**
	 * If the media player is buffering now.
	 */
	private boolean mIsBuffering;

	// all possible internal states
	private static final int STATE_ERROR = -1;
	private static final int STATE_IDLE = 0;
	private static final int STATE_PREPARING = 1;
	private static final int STATE_PREPARED = 2;
	private static final int STATE_PLAYING = 3;
	private static final int STATE_PAUSED = 4;
	private static final int STATE_PLAYBACK_COMPLETED = 5;
	private static final int STATE_SUSPEND = 6;
	private static final int STATE_RESUME = 7;
	private static final int STATE_SUSPEND_UNSUPPORTED = 8;

	private Context mContext;
	private int mVideoWidth;
	private int mVideoHeight;
	private int mSurfaceWidth;
	private int mSurfaceHeight;

	private int mCurrentState = STATE_IDLE;
	private int mTargetState = STATE_IDLE;

	private SurfaceHolder mSurfaceHolder;
	private MediaPlayer mMediaPlayer;
	private Uri mUri;
	private MediaController mMediaController;

	private int mRealHeight;

	/**
	 * Recording the seek position while preparing
	 */
	private int mSeekWhenPrepared;

	/**
	 * The view will showing when is buffering, usually this will be a progress
	 * bar
	 */
	private View mMediaBufferingIndicator;

	private boolean mCanPause = true;
	private boolean mCanSeekBack = true;
	private boolean mCanSeekForward = true;
	private int mCurrentBufferPercentage;

	/**
	 * Original width and height of the video
	 */
	public static final int VIDEO_LAYOUT_ORIGIN = 0;

	/**
	 * Full screen(fill the view) but use the video width/height ratio. usually
	 * the width or height may can't fill the window
	 */
	public static final int VIDEO_LAYOUT_SCALE = 1;

	/**
	 * Full screen, video width and height equals the window width and height
	 */
	public static final int VIDEO_LAYOUT_STRETCH = 2;

	/**
	 * Unused
	 */
	public static final int VIDEO_LAYOUT_ZOOM = 3;

	private float mAspectRatio = 0;

	private int mVideoLayout = VIDEO_LAYOUT_SCALE;

	private float mVideoAspectRatio;

	private MediaPlayer.OnPreparedListener mOnPreparedListener;
	private MediaPlayer.OnCompletionListener mOnCompletionListener;
	private MediaPlayer.OnErrorListener mOnErrorListener;
	private MediaPlayer.OnInfoListener mOnInfoListener;
	private MediaPlayer.OnSeekCompleteListener mOnSeekCompleteListener;
	private MediaPlayer.OnBufferingUpdateListener mOnBufferingUpdateListener;

	private OnStateChangeListener mOnStateChangeListener;

	/**
	 * Get the changes of surface from SurfaceHolder.Callback()
	 */
	private SurfaceHolder.Callback mSHCallback = new SurfaceHolder.Callback() {

		@Override
		public void surfaceCreated(SurfaceHolder holder) {
			Log.d(TAG, "surfaceCreated");
			mSurfaceHolder = holder;
			if (mMediaPlayer != null && mCurrentState == STATE_SUSPEND
					&& mTargetState == STATE_RESUME) {
				// wake up from background after press Home key
				Log.d(TAG, "surfaceCreated... resume.");
				if (android.os.Build.VERSION.SDK_INT >= 11) {
					Log.d(TAG, "version sdk >= 11");
					mMediaPlayer.setDisplay(mSurfaceHolder);
					start();
				} else {
					Log.d(TAG, "version < 11");
					// If use mMediaPlayer.setDisplay(mSurfaceHolder) will have
					// no effect, it's will be black
					mSeekWhenPrepared = mMediaPlayer.getCurrentPosition();
					openVideo();
				}
			} else {
				openVideo();
			}
		}

		@Override
		public void surfaceChanged(SurfaceHolder holder, int format, int width,
				int height) {
			Log.i(TAG, "surfaceChanged...");
			mSurfaceWidth = width;
			mSurfaceHeight = height;
			boolean isValidState = (mTargetState == STATE_PLAYING);
			boolean hasValidSize = (mVideoWidth == width && mVideoHeight == height);
			if (mMediaPlayer != null && isValidState && hasValidSize) {
				if (mSeekWhenPrepared != 0) {
					seekTo(mSeekWhenPrepared);
				}
				start();
			}
		}

		@Override
		public void surfaceDestroyed(SurfaceHolder holder) {
			Log.d(TAG, "surfaceDestroyed");
			mSurfaceHolder = null;
			if (mMediaController != null)
				mMediaController.hide();
			if (mCurrentState != STATE_SUSPEND) {
				Log.d(TAG,
						"surfaceDestroyed and currentState is not suspend so we will release");
				release(true);
			}
		}
	};

	private MediaPlayer.OnPreparedListener mPreparedListener = new MediaPlayer.OnPreparedListener() {
		public void onPrepared(MediaPlayer mp) {
			mCurrentState = STATE_PREPARED;
			mTargetState = STATE_PLAYING;

			if (isLive) {
				mCanPause = false;
				mCanSeekBack = false;
				mCanSeekForward = false;
			}

			if (mOnPreparedListener != null) {
				mOnPreparedListener.onPrepared(mMediaPlayer);
			}
			if (mMediaController != null) {
				mMediaController.setEnabled(true);
			}
			mVideoWidth = mp.getVideoWidth();
			mVideoHeight = mp.getVideoHeight();

			int seekToPosition = mSeekWhenPrepared;
			mSeekWhenPrepared = 0;
			if (seekToPosition != 0) {
				seekTo(seekToPosition);
			}
			mVideoAspectRatio = (float) mVideoWidth / (float) mVideoHeight;

			if (mVideoWidth != 0 && mVideoHeight != 0) {
				// this video can be played
				// getHolder().setFixedSize(mVideoWidth, mVideoHeight);
				setVideoLayout(mVideoLayout, mAspectRatio);
				if (mSurfaceWidth == mVideoWidth
						&& mSurfaceHeight == mVideoHeight) {
					if (mTargetState == STATE_PLAYING) {
						start();
						if (mMediaController != null) {
							mMediaController.show();
						}
					} else if (!isPlaying()
							&& (seekToPosition != 0 || getCurrentPosition() > 0)) {
						if (mMediaController != null) {
							mMediaController.show(0);
						}
					}
				}
			} else {
				// this video may be can't be played, start here and may be we
				// can get the size later
				if (mTargetState == STATE_PLAYING) {
					start();
				}
			}

			stateChange(State.STATE_PREPARED);
		}
	};

	private MediaPlayer.OnVideoSizeChangedListener mSizeChangedListener = new MediaPlayer.OnVideoSizeChangedListener() {
		public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
			mVideoWidth = mp.getVideoWidth();
			mVideoHeight = mp.getVideoHeight();
			mVideoAspectRatio = (float) mVideoWidth / (float) mVideoHeight;
			if (mVideoWidth != 0 && mVideoHeight != 0) {
				// some video can't be play ,for this video the width and height
				// will be 0.
				// getHolder().setFixedSize(mVideoWidth, mVideoHeight);
				setVideoLayout(mVideoLayout, mAspectRatio);
				requestLayout();
			}
		}
	};

	private MediaPlayer.OnCompletionListener mCompletionListener = new MediaPlayer.OnCompletionListener() {
		public void onCompletion(MediaPlayer mp) {
			mCurrentState = STATE_PLAYBACK_COMPLETED;
			mTargetState = STATE_PLAYBACK_COMPLETED;
			if (mMediaController != null) {
				mMediaController.hide();
			}
			if (mOnCompletionListener != null) {
				mOnCompletionListener.onCompletion(mMediaPlayer);
			}

			stateChange(State.STATE_PLAYBACK_COMPLETED);
		}
	};

	private MediaPlayer.OnErrorListener mErrorListener = new MediaPlayer.OnErrorListener() {
		public boolean onError(MediaPlayer mp, int framework_err, int impl_err) {
			mCurrentState = STATE_ERROR;
			mTargetState = STATE_ERROR;

			stateChange(State.STATE_ERROR);

			if (mMediaController != null) {
				mMediaController.hide();
			}

			/* If an error handler has been supplied, use it and finish. */
			if (mOnErrorListener != null) {
				if (mOnErrorListener.onError(mMediaPlayer, framework_err,
						impl_err)) {
					return true;
				}
			}

			/*
			 * Otherwise, pop up an error dialog so the user knows that
			 * something bad has happened. Only try and pop up the dialog if
			 * we're attached to a window. When we're going away and no longer
			 * have a window, don't bother showing the user an error.
			 */
			if (getWindowToken() != null) {
				int messageId;

				if (framework_err == MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK) {
					messageId = R.string.VideoView_error_text_invalid_progressive_playback;
				} else {
					messageId = R.string.VideoView_error_text_unknown;
				}

				new AlertDialog.Builder(mContext)
						.setMessage(messageId)
						.setPositiveButton(R.string.VideoView_error_button,
								new DialogInterface.OnClickListener() {
									public void onClick(DialogInterface dialog,
											int whichButton) {
										/*
										 * If we get here, there is no onError
										 * listener, so at least inform them
										 * that the video is over.
										 */
										if (mOnCompletionListener != null) {
											mOnCompletionListener
													.onCompletion(mMediaPlayer);
										}
									}
								}).setCancelable(false).show();
			}
			return true;
		}
	};

	private MediaPlayer.OnInfoListener mInfoListener = new MediaPlayer.OnInfoListener() {
		@Override
		public boolean onInfo(MediaPlayer mp, int what, int extra) {
			if (mOnInfoListener != null) {
				mOnInfoListener.onInfo(mp, what, extra);
			} else if (mMediaPlayer != null) {
				if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
					mMediaPlayer.pause();
					mIsBuffering = true;
					if (mMediaBufferingIndicator != null) {
						mMediaBufferingIndicator.setVisibility(View.VISIBLE);
					}

					stateChange(State.STATE_BUFFERING_START);
				} else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
					mMediaPlayer.start();
					mIsBuffering = false;
					if (mMediaBufferingIndicator != null) {
						mMediaBufferingIndicator.setVisibility(View.GONE);
					}

					stateChange(State.STATE_BUFFERING_END);
				}
			}
			return true;
		}
	};

	private MediaPlayer.OnBufferingUpdateListener mBufferingUpdateListener = new MediaPlayer.OnBufferingUpdateListener() {
		public void onBufferingUpdate(MediaPlayer mp, int percent) {
			mCurrentBufferPercentage = percent;
			if (mOnBufferingUpdateListener != null) {
				mOnBufferingUpdateListener.onBufferingUpdate(mp, percent);
			}
		}
	};

	private MediaPlayer.OnSeekCompleteListener mSeekCompleteListener = new MediaPlayer.OnSeekCompleteListener() {
		@Override
		public void onSeekComplete(MediaPlayer mp) {
			if (mOnSeekCompleteListener != null) {
				mOnSeekCompleteListener.onSeekComplete(mp);
			}
		}
	};

	public VideoView(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
		initVideoView(context);
	}

	public VideoView(Context context, AttributeSet attrs) {
		super(context, attrs);
		initVideoView(context);
	}

	public VideoView(Context context) {
		super(context);
		initVideoView(context);
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		int width = getDefaultSize(mVideoWidth, widthMeasureSpec);
		int height = getDefaultSize(mVideoHeight, heightMeasureSpec);
		setMeasuredDimension(width, height);
	}

	@SuppressWarnings("deprecation")
	private void initVideoView(Context context) {
		mContext = context;
		mVideoWidth = 0;
		mVideoHeight = 0;

		getHolder().addCallback(mSHCallback);
		getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

		setFocusable(true);
		setFocusableInTouchMode(true);
		requestFocus();
		mCurrentState = STATE_IDLE;
		mTargetState = STATE_IDLE;
		stateChange(State.STATE_IDLE);
	}

	/**
	 * Set the path of the video and this contains the openVideo() method
	 * 
	 * @param path
	 *            the path of the video.
	 */
	public void setVideoPath(String path) {
		setVideoURI(Uri.parse(path));
	}

	/**
	 * Set the URI of the video and this contains the openVideo() method
	 * 
	 * @param uri
	 *            the Uri of the video
	 */
	public void setVideoURI(Uri uri) {
		mUri = uri;
		mSeekWhenPrepared = 0;
		openVideo();
		requestLayout();
		invalidate();
	}

	/**
	 * Stop and release the resource
	 */
	public void stopPlayback() {
		if (mMediaPlayer != null) {
			Log.d(TAG, "stop play back, release");
			mMediaPlayer.stop();
			mMediaPlayer.release();
			mMediaPlayer = null;
			mCurrentState = STATE_IDLE;
			mTargetState = STATE_IDLE;
			stateChange(State.STATE_STOPPLAYBACK);
		}
	}

	/**
	 * Set the display options
	 * 
	 * @param layout
	 *            <ul>
	 *            <li>{@link #VIDEO_LAYOUT_ORIGIN}
	 *            <li>{@link #VIDEO_LAYOUT_SCALE}
	 *            <li>{@link #VIDEO_LAYOUT_STRETCH}
	 *            <li>{@link #VIDEO_LAYOUT_ZOOM}
	 *            </ul>
	 * @param aspectRatio
	 *            video aspect ratio, will auto detect if 0.
	 */
	public void setVideoLayout(int layout, float aspectRatio) {
		LayoutParams lp = getLayoutParams();
		DisplayMetrics disp = mContext.getResources().getDisplayMetrics();
		int windowWidth = disp.widthPixels;
		if (mRealHeight != 0) {
			disp.heightPixels = mRealHeight;
			mRealHeight = 0;
		}
		int windowHeight = disp.heightPixels;
		float windowRatio = windowWidth / (float) windowHeight;

		// if aspectRatio is invalid, we will use video aspectratio
		float videoRatio = aspectRatio <= 0.01f ? mVideoAspectRatio
				: aspectRatio;

		mSurfaceHeight = mVideoHeight;
		mSurfaceWidth = mVideoWidth;

		if (VIDEO_LAYOUT_ORIGIN == layout && mSurfaceWidth < windowWidth
				&& mSurfaceHeight < windowHeight) {
			lp.width = (int) (mSurfaceHeight * videoRatio);
			lp.height = mSurfaceHeight;
		} else if (layout == VIDEO_LAYOUT_ZOOM) {
			lp.width = windowRatio > videoRatio ? windowWidth
					: (int) (videoRatio * windowHeight);
			lp.height = windowRatio < videoRatio ? windowHeight
					: (int) (windowWidth / videoRatio);
		} else {
			boolean full = layout == VIDEO_LAYOUT_STRETCH;
			// if is stretch we will set the width and height to windowWidth and
			// windowHeight
			lp.width = (full || windowRatio < videoRatio) ? LayoutParams.MATCH_PARENT
					: (int) (videoRatio * windowHeight);
			lp.height = (full || windowRatio > videoRatio) ? LayoutParams.MATCH_PARENT
					: (int) (windowWidth / videoRatio);

		}
		setLayoutParams(lp);

		getHolder().setFixedSize(lp.width, lp.height);

		mVideoLayout = layout;
		mAspectRatio = aspectRatio;
	}

	/**
	 * Set the display options
	 * 
	 * @param layout
	 * @param aspectRatio
	 * @param hideController
	 *            true will hide the media controller
	 */
	public void setVideoLayout(int layout, float aspectRatio,
			boolean hideController) {
		if (hideController && mMediaController != null) {
			mMediaController.hide();
		}
		setVideoLayout(layout, aspectRatio);
	}

	private void openVideo() {
		if (mUri == null || mSurfaceHolder == null) {
			// not ready for play back just yet, will try again later
			return;
		}
		// Tell the music play back service to pause
		Intent i = new Intent("com.android.music.musicservicecommand");
		i.putExtra("command", "pause");
		mContext.sendBroadcast(i);

		// we shouldn't clear the target state, because somebody might have
		// called start() previously
		release(false);
		try {
			mMediaPlayer = new MediaPlayer();
			mMediaPlayer.setOnPreparedListener(mPreparedListener);
			mMediaPlayer.setOnVideoSizeChangedListener(mSizeChangedListener);
			mMediaPlayer.setOnCompletionListener(mCompletionListener);
			mMediaPlayer.setOnErrorListener(mErrorListener);
			mMediaPlayer.setOnInfoListener(mInfoListener);
			mMediaPlayer.setOnBufferingUpdateListener(mBufferingUpdateListener);
			mMediaPlayer.setOnSeekCompleteListener(mSeekCompleteListener);
			mMediaPlayer.setDataSource(mContext, mUri);
			// let's surface view show the image
			mMediaPlayer.setDisplay(mSurfaceHolder);
			mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
			mMediaPlayer.setScreenOnWhilePlaying(true);
			mMediaPlayer.prepareAsync();

			mCurrentBufferPercentage = 0;
			// we don't set the target state here either, but preserve the
			// target state that was there before.
			mCurrentState = STATE_PREPARING;
			stateChange(State.STATE_PREPARING);
			attachMediaController();
		} catch (IOException ex) {
			ex.printStackTrace();
			mCurrentState = STATE_ERROR;
			mTargetState = STATE_ERROR;
			mErrorListener.onError(mMediaPlayer,
					MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
			stateChange(State.STATE_ERROR);
			return;
		} catch (IllegalArgumentException ex) {
			ex.printStackTrace();
			mCurrentState = STATE_ERROR;
			mTargetState = STATE_ERROR;
			mErrorListener.onError(mMediaPlayer,
					MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
			stateChange(State.STATE_ERROR);
			return;
		}
	}

	public void setMediaController(MediaController controller) {
		if (mMediaController != null) {
			mMediaController.hide();
		}
		mMediaController = controller;
		attachMediaController();
	}

	/**
	 * Set the real height of this view, if this view's height is not the window
	 * height you should use this method. this should be used before
	 * setVideoLayout() to make it useful.Must set before {@link #setVideoURI}
	 * 
	 * @param realHeight
	 *            The Height of this view in px.
	 */
	public void setRealHeight(int realHeight) {
		this.mRealHeight = realHeight;
	}

	private void attachMediaController() {
		if (mMediaPlayer != null && mMediaController != null) {
			mMediaController.setMediaPlayer(this);
			View anchorView = this.getParent() instanceof View ? (View) this
					.getParent() : this;
			mMediaController.setAnchorView(anchorView);
			mMediaController.setEnabled(isInPlaybackState());
		}
	}

	/**
	 * Release the media player in any state
	 */
	private void release(boolean cleartargetstate) {
		if (mMediaPlayer != null) {
			mMediaPlayer.reset();
			mMediaPlayer.release();
			mMediaPlayer = null;
			mCurrentState = STATE_IDLE;
			if (cleartargetstate) {
				mTargetState = STATE_IDLE;
			}
		}
		stateChange(State.STATE_IDLE);
	}

	/**
	 * Reset the media player but not release it, this is used when we want auto
	 * play next video.
	 * 
	 * @param cleartargetstate
	 */
	@SuppressWarnings("unused")
	private void reset(boolean cleartargetstate) {
		if (mMediaPlayer != null) {
			mMediaPlayer.reset();
			mCurrentState = STATE_IDLE;
			if (cleartargetstate) {
				mTargetState = STATE_IDLE;
			}
		}
		stateChange(State.STATE_IDLE);
	}

	@Override
	public boolean onTouchEvent(MotionEvent ev) {
		if (isInPlaybackState() && mMediaController != null) {
			toggleMediaControlsVisiblity();
		}
		return false;
	}

	@Override
	public boolean onTrackballEvent(MotionEvent ev) {
		if (isInPlaybackState() && mMediaController != null) {
			toggleMediaControlsVisiblity();
		}
		return false;
	}

	@SuppressLint("InlinedApi")
	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		boolean isKeyCodeSupported = keyCode != KeyEvent.KEYCODE_BACK
				&& keyCode != KeyEvent.KEYCODE_VOLUME_UP
				&& keyCode != KeyEvent.KEYCODE_VOLUME_DOWN
				&& keyCode != KeyEvent.KEYCODE_VOLUME_MUTE
				&& keyCode != KeyEvent.KEYCODE_MENU
				&& keyCode != KeyEvent.KEYCODE_CALL
				&& keyCode != KeyEvent.KEYCODE_ENDCALL;
		if (isInPlaybackState() && isKeyCodeSupported
				&& mMediaController != null) {
			if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK
					|| keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
				if (mMediaPlayer.isPlaying()) {
					pause();
					mMediaController.show();
				} else {
					start();
					mMediaController.hide();
				}
				return true;
			} else if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
				if (!mMediaPlayer.isPlaying()) {
					start();
					mMediaController.hide();
				}
				return true;
			} else if (keyCode == KeyEvent.KEYCODE_MEDIA_STOP
					|| keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
				if (mMediaPlayer.isPlaying()) {
					pause();
					mMediaController.show();
				}
				return true;
			} else {
				toggleMediaControlsVisiblity();
			}
		}

		return super.onKeyDown(keyCode, event);
	}

	private void toggleMediaControlsVisiblity() {
		if (mMediaController.isShowing()) {
			mMediaController.hide();
		} else {
			mMediaController.show();
		}
	}

	private boolean isInPlaybackState() {
		return (mMediaPlayer != null && mCurrentState != STATE_ERROR
				&& mCurrentState != STATE_IDLE && mCurrentState != STATE_PREPARING);
	}

	private void stateChange(State state) {
		if (mOnStateChangeListener != null) {
			mOnStateChangeListener.stateChange(state);
		}
	}

	/**
	 * set the view show when is buffering
	 * 
	 * @param mediaBufferingIndicator
	 */
	public void setMediaBufferingIndicator(View mediaBufferingIndicator) {
		if (mMediaBufferingIndicator != null)
			mMediaBufferingIndicator.setVisibility(View.GONE);
		mMediaBufferingIndicator = mediaBufferingIndicator;
	}

	public void setOnPreparedListener(
			MediaPlayer.OnPreparedListener onPreparedListener) {
		mOnPreparedListener = onPreparedListener;
	}

	public void setOnCompletionListener(
			MediaPlayer.OnCompletionListener onCompletionListener) {
		mOnCompletionListener = onCompletionListener;
	}

	public void setOnErrorListener(MediaPlayer.OnErrorListener onErrorListener) {
		mOnErrorListener = onErrorListener;
	}

	/**
	 * Usually don't need to use the method if you have used
	 * setMediaBufferingIndicator, we have handled this
	 * 
	 * @param onInfoListener
	 */
	public void setOnInfoListener(MediaPlayer.OnInfoListener onInfoListener) {
		mOnInfoListener = onInfoListener;
	}

	public void setOnSeekCompleteListener(
			MediaPlayer.OnSeekCompleteListener onSeekCompleteListener) {
		mOnSeekCompleteListener = onSeekCompleteListener;
	}

	public void setOnBufferingUpdateListener(
			MediaPlayer.OnBufferingUpdateListener onBufferingUpdateListener) {
		mOnBufferingUpdateListener = onBufferingUpdateListener;
	}

	public void setOnStateChangeListener(
			OnStateChangeListener stateChangeListener) {
		mOnStateChangeListener = stateChangeListener;
	}

	public boolean isLive() {
		return isLive;
	}

	/**
	 * Set if is playing live stream.Must set before {@link #setVideoURI}
	 * 
	 * @param isLive
	 */
	public void setIsLive(boolean isLive) {
		this.isLive = isLive;
	}

	/**
	 * Get the buffering state of the media player.
	 * 
	 * @return true if is in buffering, else return false.
	 */
	public boolean isBuffering() {
		if (mMediaPlayer != null)
			return mIsBuffering;
		return false;
	}

	// -------------------------------------------------
	@Override
	public boolean canPause() {
		return mCanPause;
	}

	@Override
	public boolean canSeekBackward() {
		return mCanSeekBack;
	}

	@Override
	public boolean canSeekForward() {
		return mCanSeekForward;
	}

	@Override
	public int getBufferPercentage() {
		if (mMediaPlayer != null) {
			return mCurrentBufferPercentage;
		}
		return 0;
	}

	@Override
	public int getCurrentPosition() {
		if (isInPlaybackState()) {
			return mMediaPlayer.getCurrentPosition();
		}
		return 0;
	}

	@Override
	public int getDuration() {
		if (isInPlaybackState()) {
			return mMediaPlayer.getDuration();
		}

		return -1;
	}

	@Override
	public boolean isPlaying() {
		return isInPlaybackState() && mMediaPlayer.isPlaying();
	}

	@Override
	public void pause() {
		if (isInPlaybackState()) {
			if (mMediaPlayer.isPlaying()) {
				mMediaPlayer.pause();
				mCurrentState = STATE_PAUSED;
			}
		}
		mTargetState = STATE_PAUSED;
		stateChange(State.STATE_PAUSED);
	}

	@Override
	public void seekTo(int pos) {
		if (isInPlaybackState()) {
			mMediaPlayer.seekTo((int) pos);
			mSeekWhenPrepared = 0;
		} else {
			mSeekWhenPrepared = (int) pos;
		}
	}

	@Override
	public void start() {
		if (isInPlaybackState()) {
			mMediaPlayer.start();
			mCurrentState = STATE_PLAYING;
		}
		mTargetState = STATE_PLAYING;
		stateChange(State.STATE_PLAYING);
	}

	/**
	 * if you want this can play from the pause position when wake up from
	 * background, you should you this on Activity's onPause() method. notice:
	 * can not use this on Activity's onStop() method, because onPause is before
	 * surface view destroy but when onStop is running the surface view has
	 * already destroyed.
	 */
	public void suspend() {
		if (isInPlaybackState()) {
			// do not release the resources used by the media player, so when we
			// wake up from the background we call use the start method but not
			// openVideo
			pause();
			mCurrentState = STATE_SUSPEND;
			stateChange(State.STATE_SUSPEND);
		} else {
			release(false);
			mCurrentState = STATE_SUSPEND_UNSUPPORTED;
			stateChange(State.STATE_SUSPEND_UNSUPPORTED);
		}
	}

	/**
	 * if you wake up from background you should use this method to start
	 * playing from the pause position. usually we should use this method on
	 * Activity's onResume() method
	 */
	public void resume() {
		Log.d(TAG, "resume");
		if (mSurfaceHolder == null && mCurrentState == STATE_SUSPEND) {
			mTargetState = STATE_RESUME;
		} else if (mCurrentState == STATE_SUSPEND_UNSUPPORTED) {
			openVideo();
		}
		stateChange(State.STATE_RESUME);
	}

	/**
	 * Get the current state of VideoView
	 */
	public interface OnStateChangeListener {

		/**
		 * All the state of VideoView
		 */
		public enum State {
			STATE_IDLE, STATE_PREPARING, STATE_PREPARED, STATE_PLAYING, STATE_PAUSED, //
			STATE_PLAYBACK_COMPLETED, STATE_SUSPEND_UNSUPPORTED, STATE_SUSPEND, STATE_ERROR, //
			STATE_RESUME, STATE_STOPPLAYBACK, STATE_BUFFERING_START, STATE_BUFFERING_END
		}

		/**
		 * State Changed
		 * 
		 * @param state
		 *            the current state
		 */
		public void stateChange(State state);
	}

}
