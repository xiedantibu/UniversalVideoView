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

import io.vov.vitamio.Metadata;
import io.vov.vitamio.Vitamio;

import java.io.IOException;

import com.charon.video.view.UniversalVideoView.OnStateChangeListener.State;

import android.R;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.AsyncTask;
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
 * A custom video view that use system media player default, but if system media
 * player can not play this video, we will change to use Vitamio instead.
 * 
 * @author Charon Chui
 * 
 */
public class UniversalVideoView extends SurfaceView implements
		MediaController.MediaPlayerControl {
	private static final String TAG = "UniversalVideoView";

	/**
	 * Use Vitamio MediaPlayer if true.
	 */
	private boolean isUseVitamio;

	/**
	 * True when playing live stream.
	 */
	private boolean isLive;

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

	// vitamio
	private io.vov.vitamio.MediaPlayer mVitamioMediaPlayer;
	// vitamio
	private int mVideoChroma = io.vov.vitamio.MediaPlayer.VIDEOCHROMA_RGBA;

	private Uri mUri;
	private MediaController mMediaController;

	/**
	 * Max height of this view
	 */
	private int mRealHeight;

	/**
	 * Recording the seek position when stopped or destroyed when need continue
	 * play
	 */
	private int mSeekWhenPrepared;

	/**
	 * View will showing when is buffering, usually this will be a progress bar
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

	/**
	 * Current video layout, the default is scale.
	 */
	private int mVideoLayout = VIDEO_LAYOUT_SCALE;

	private float mVideoAspectRatio;

	/**
	 * If the media player is buffering now.
	 */
	private boolean mIsBuffering;

	private MediaPlayer.OnPreparedListener mOnPreparedListener;
	private MediaPlayer.OnCompletionListener mOnCompletionListener;
	private MediaPlayer.OnErrorListener mOnErrorListener;
	private MediaPlayer.OnInfoListener mOnInfoListener;
	private MediaPlayer.OnSeekCompleteListener mOnSeekCompleteListener;
	private MediaPlayer.OnBufferingUpdateListener mOnBufferingUpdateListener;

	// vitamio
	private io.vov.vitamio.MediaPlayer.OnPreparedListener mVitamioOnPreparedListener;
	private io.vov.vitamio.MediaPlayer.OnCompletionListener mVitamioOnCompletionListener;
	private io.vov.vitamio.MediaPlayer.OnErrorListener mVitamioOnErrorListener;
	private io.vov.vitamio.MediaPlayer.OnInfoListener mVitamioOnInfoListener;
	private io.vov.vitamio.MediaPlayer.OnSeekCompleteListener mVitamioOnSeekCompleteListener;
	private io.vov.vitamio.MediaPlayer.OnBufferingUpdateListener mVitamioOnBufferingUpdateListener;

	// The listener of the current video state.
	private OnStateChangeListener mOnStateChangeListener;

	/**
	 * we can get the changes of surface from SurfaceHolder.Callback()
	 */
	private SurfaceHolder.Callback mSHCallback = new SurfaceHolder.Callback() {

		@Override
		public void surfaceCreated(SurfaceHolder holder) {
			Log.d(TAG, "surfaceCreated");
			mSurfaceHolder = holder;
			if (isUseVitamio) {
				// vitamio
				if (mVitamioMediaPlayer != null
						&& mCurrentState == STATE_SUSPEND
						&& mTargetState == STATE_RESUME) {
					// wake up from background after press Home key
					Log.d(TAG, "surfaceCreated... resume.");
					if (android.os.Build.VERSION.SDK_INT >= 11) {
						Log.d(TAG, "version sdk >= 11");
						mVitamioMediaPlayer.setDisplay(mSurfaceHolder);
						start();
					} else {
						// If use mMediaPlayer.setDisplay(mSurfaceHolder) will
						// have no effect, it's will be black in Android 2.3.5
						mSeekWhenPrepared = (int) mVitamioMediaPlayer
								.getCurrentPosition();
						Log.d(TAG, "version < 11" + "currentPosition:"
								+ mSeekWhenPrepared + "we need to open video");
						openVideo();
					}
				} else {
					openVideo();
				}
			} else {
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
						// If use mMediaPlayer.setDisplay(mSurfaceHolder) will
						// have
						// no effect, it's will be black
						mSeekWhenPrepared = mMediaPlayer.getCurrentPosition();
						openVideo();
					}
				} else {
					openVideo();
				}
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
			if (isUseVitamio) {
				if (mVitamioMediaPlayer != null && isValidState && hasValidSize) {
					if (mSeekWhenPrepared != 0) {
						seekTo(mSeekWhenPrepared);
					}
					start();
				}
			} else {
				if (mMediaPlayer != null && isValidState && hasValidSize) {
					if (mSeekWhenPrepared != 0) {
						seekTo(mSeekWhenPrepared);
					}
					start();
				}
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

			stateChange(State.PREPARED);
		}
	};

	// vitamio
	private io.vov.vitamio.MediaPlayer.OnPreparedListener mVitamioPreparedListener = new io.vov.vitamio.MediaPlayer.OnPreparedListener() {
		public void onPrepared(io.vov.vitamio.MediaPlayer mp) {
			Log.i(TAG, "vitamio on prepared.");
			mCurrentState = STATE_PREPARED;
			mTargetState = STATE_PLAYING;

			// Get the capabilities of the player for this stream
			Metadata data = mp.getMetadata();

			if (data != null) {
				mCanPause = !data.has(Metadata.PAUSE_AVAILABLE)
						|| data.getBoolean(Metadata.PAUSE_AVAILABLE);
				mCanSeekBack = !data.has(Metadata.SEEK_BACKWARD_AVAILABLE)
						|| data.getBoolean(Metadata.SEEK_BACKWARD_AVAILABLE);
				mCanSeekForward = !data.has(Metadata.SEEK_FORWARD_AVAILABLE)
						|| data.getBoolean(Metadata.SEEK_FORWARD_AVAILABLE);
			} else {
				mCanPause = mCanSeekBack = mCanSeekForward = true;
			}

			if (mVitamioOnPreparedListener != null) {
				mVitamioOnPreparedListener.onPrepared(mVitamioMediaPlayer);
			}
			if (mMediaController != null) {
				mMediaController.setEnabled(true);
			}
			mVideoWidth = mp.getVideoWidth();
			mVideoHeight = mp.getVideoHeight();

			int seekToPosition = mSeekWhenPrepared;
			mSeekWhenPrepared = 0;
			Log.i(TAG, "vitamio on prepared. seek to position:"
					+ seekToPosition);
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

			stateChange(State.PREPARED);
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

	// vitamio
	private io.vov.vitamio.MediaPlayer.OnVideoSizeChangedListener mVitamioSizeChangedListener = new io.vov.vitamio.MediaPlayer.OnVideoSizeChangedListener() {
		public void onVideoSizeChanged(io.vov.vitamio.MediaPlayer mp,
				int width, int height) {
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

			stateChange(State.PLAYBACK_COMPLETED);
		}
	};

	// vitamio
	private io.vov.vitamio.MediaPlayer.OnCompletionListener mVitamioCompletionListener = new io.vov.vitamio.MediaPlayer.OnCompletionListener() {
		public void onCompletion(io.vov.vitamio.MediaPlayer mp) {
			mCurrentState = STATE_PLAYBACK_COMPLETED;
			mTargetState = STATE_PLAYBACK_COMPLETED;
			if (mMediaController != null) {
				mMediaController.hide();
			}
			if (mVitamioOnCompletionListener != null) {
				mVitamioOnCompletionListener.onCompletion(mVitamioMediaPlayer);
			}

			stateChange(State.PLAYBACK_COMPLETED);
		}
	};

	private MediaPlayer.OnErrorListener mErrorListener = new MediaPlayer.OnErrorListener() {
		public boolean onError(MediaPlayer mp, int framework_err, int impl_err) {
			mCurrentState = STATE_ERROR;
			mTargetState = STATE_ERROR;

			stateChange(State.ERROR);

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

	// vitamio
	private io.vov.vitamio.MediaPlayer.OnErrorListener mVitamioErrorListener = new io.vov.vitamio.MediaPlayer.OnErrorListener() {
		public boolean onError(io.vov.vitamio.MediaPlayer mp,
				int framework_err, int impl_err) {
			mCurrentState = STATE_ERROR;
			mTargetState = STATE_ERROR;

			stateChange(State.ERROR);

			if (mMediaController != null) {
				mMediaController.hide();
			}

			/* If an error handler has been supplied, use it and finish. */
			if (mVitamioOnErrorListener != null) {
				if (mVitamioOnErrorListener.onError(mVitamioMediaPlayer,
						framework_err, impl_err)) {
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
										if (mVitamioOnCompletionListener != null) {
											mVitamioOnCompletionListener
													.onCompletion(mVitamioMediaPlayer);
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
					mIsBuffering = true;
					mMediaPlayer.pause();
					if (mMediaBufferingIndicator != null)
						mMediaBufferingIndicator.setVisibility(View.VISIBLE);

					stateChange(State.BUFFERING_START);
				} else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
					mIsBuffering = false;
					mMediaPlayer.start();
					if (mMediaBufferingIndicator != null)
						mMediaBufferingIndicator.setVisibility(View.GONE);
					stateChange(State.BUFFERING_END);
				}
			}
			return true;
		}
	};

	// vitamio
	private io.vov.vitamio.MediaPlayer.OnInfoListener mVitamioInfoListener = new io.vov.vitamio.MediaPlayer.OnInfoListener() {
		@Override
		public boolean onInfo(io.vov.vitamio.MediaPlayer mp, int what, int extra) {
			if (mVitamioOnInfoListener != null) {
				mVitamioOnInfoListener.onInfo(mp, what, extra);
			} else if (mVitamioMediaPlayer != null) {
				if (what == io.vov.vitamio.MediaPlayer.MEDIA_INFO_BUFFERING_START) {
					mVitamioMediaPlayer.pause();
					if (mMediaBufferingIndicator != null)
						mMediaBufferingIndicator.setVisibility(View.VISIBLE);

					stateChange(State.BUFFERING_START);
				} else if (what == io.vov.vitamio.MediaPlayer.MEDIA_INFO_BUFFERING_END) {
					mVitamioMediaPlayer.start();
					if (mMediaBufferingIndicator != null)
						mMediaBufferingIndicator.setVisibility(View.GONE);
					stateChange(State.BUFFERING_END);
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

	// vitamio
	private io.vov.vitamio.MediaPlayer.OnBufferingUpdateListener mVitamioBufferingUpdateListener = new io.vov.vitamio.MediaPlayer.OnBufferingUpdateListener() {
		public void onBufferingUpdate(io.vov.vitamio.MediaPlayer mp, int percent) {
			mCurrentBufferPercentage = percent;
			if (mOnBufferingUpdateListener != null) {
				mVitamioOnBufferingUpdateListener
						.onBufferingUpdate(mp, percent);
			}
		}
	};

	private MediaPlayer.OnSeekCompleteListener mSeekCompleteListener = new MediaPlayer.OnSeekCompleteListener() {
		@Override
		public void onSeekComplete(MediaPlayer mp) {
			if (mOnSeekCompleteListener != null)
				mOnSeekCompleteListener.onSeekComplete(mp);
		}
	};

	// vitamio
	private io.vov.vitamio.MediaPlayer.OnSeekCompleteListener mVitamioSeekCompleteListener = new io.vov.vitamio.MediaPlayer.OnSeekCompleteListener() {
		@Override
		public void onSeekComplete(io.vov.vitamio.MediaPlayer mp) {
			if (mOnSeekCompleteListener != null)
				mVitamioOnSeekCompleteListener.onSeekComplete(mp);
		}
	};

	public UniversalVideoView(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
		initVideoView(context);
	}

	public UniversalVideoView(Context context, AttributeSet attrs) {
		super(context, attrs);
		initVideoView(context);
	}

	public UniversalVideoView(Context context) {
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

		if (isUseVitamio) {
			// vitamio
			getHolder().setFormat(PixelFormat.RGBA_8888);
		} else {
			getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
		}

		// vitamio
		if (context instanceof Activity) {
			Log.d(TAG, "vitamio set volume control stream to music");
			((Activity) context)
					.setVolumeControlStream(AudioManager.STREAM_MUSIC);
		}

		setFocusable(true);
		setFocusableInTouchMode(true);
		requestFocus();
		mCurrentState = STATE_IDLE;
		mTargetState = STATE_IDLE;
		stateChange(State.IDLE);
	}

	/**
	 * Set the path of the video and this contains the openVideo() method
	 * 
	 * @param path
	 */
	public void setVideoPath(String path) {
		setVideoURI(Uri.parse(path));
	}

	public void setVideoPath(String path, boolean isUseVitamio) {
		setVideoURI(Uri.parse(path), isUseVitamio);
	}

	public void setVideoPath(String path, boolean isUseVitamio, boolean isLive) {
		setVideoURI(Uri.parse(path), isUseVitamio, isLive);
	}

	/**
	 * Set the URI of the video and this contains the openVideo() method
	 * 
	 * @param uri
	 */
	public void setVideoURI(Uri uri) {
		mUri = uri;
		mSeekWhenPrepared = 0;
		openVideo();
		requestLayout();
		invalidate();
	}

	@SuppressWarnings("deprecation")
	public void setVideoURI(Uri uri, boolean isUseVitamio) {
		this.isUseVitamio = isUseVitamio;
		if (isUseVitamio) {
			getHolder().setFormat(PixelFormat.RGBA_8888);
			getHolder().setType(SurfaceHolder.SURFACE_TYPE_NORMAL);

			if (mContext instanceof Activity) {
				((Activity) mContext)
						.setVolumeControlStream(AudioManager.STREAM_MUSIC);
			}
		}

		setVideoURI(uri);
	}

	public void setVideoURI(Uri uri, boolean isUseVitamio, boolean isLive) {
		this.isLive = isLive;
		setVideoURI(uri, isUseVitamio);
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
			stateChange(State.STOPPLAYBACK);
		}

		// vitamio
		if (mVitamioMediaPlayer != null) {
			Log.d(TAG, "vitamio stop play back, release");
			mVitamioMediaPlayer.stop();
			mVitamioMediaPlayer.release();
			mVitamioMediaPlayer = null;
			mCurrentState = STATE_IDLE;
			mTargetState = STATE_IDLE;
			stateChange(State.STOPPLAYBACK);
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

		getHolder().setFixedSize(mVideoWidth, mVideoHeight);

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
			// vitamio
			if (isUseVitamio) {
				if (Vitamio.isInitialized(mContext)) {
					Log.d(TAG,
							"vitamio has already initialized and create vitamio media player");
					createVitamioMediaPlayer();
					return;
				} else {
					Log.e(TAG, "first use vitamio, start to initialize now");
					initializeVitamio();
				}
			} else {
				Log.d(TAG, "create system media player");
				mMediaPlayer = new MediaPlayer();
				mMediaPlayer.setOnPreparedListener(mPreparedListener);
				mMediaPlayer
						.setOnVideoSizeChangedListener(mSizeChangedListener);
				mMediaPlayer.setOnCompletionListener(mCompletionListener);
				mMediaPlayer.setOnErrorListener(mErrorListener);
				mMediaPlayer.setOnInfoListener(mInfoListener);
				mMediaPlayer
						.setOnBufferingUpdateListener(mBufferingUpdateListener);
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
				stateChange(State.PREPARING);
				attachMediaController();
			}

		} catch (IOException ex) {
			ex.printStackTrace();
			mCurrentState = STATE_ERROR;
			mTargetState = STATE_ERROR;
			mErrorListener.onError(mMediaPlayer,
					MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
			stateChange(State.ERROR);
			return;
		} catch (IllegalArgumentException ex) {
			ex.printStackTrace();
			mCurrentState = STATE_ERROR;
			mTargetState = STATE_ERROR;
			mErrorListener.onError(mMediaPlayer,
					MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
			stateChange(State.ERROR);
			return;
		}
	}

	// vitamio
	private void initializeVitamio() {
		new AsyncTask<Object, Object, Boolean>() {
			@Override
			protected void onPreExecute() {
				stateChange(State.VITAMIO_INITIALIZING);
			}

			@Override
			protected Boolean doInBackground(Object... params) {
				Log.d(TAG, "initilize vitamio in backgroud ..");
				return Vitamio
						.initialize(mContext, io.vov.vitamio.R.raw.libarm);
			}

			@Override
			protected void onPostExecute(Boolean inited) {
				if (inited) {
					// initial Vitamio success
					try {
						Log.d(TAG,
								"after initialize vitamio. create vitamio media player");
						createVitamioMediaPlayer();
					} catch (IOException ex) {
						ex.printStackTrace();
						mCurrentState = STATE_ERROR;
						mTargetState = STATE_ERROR;
						mVitamioErrorListener.onError(mVitamioMediaPlayer,
								MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
						stateChange(State.ERROR);
						return;
					} catch (IllegalArgumentException ex) {
						ex.printStackTrace();
						mCurrentState = STATE_ERROR;
						mTargetState = STATE_ERROR;
						mVitamioErrorListener.onError(mVitamioMediaPlayer,
								MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
						stateChange(State.ERROR);
						return;
					}
				} else {
					// failed to initial Vitamio
					Log.e(TAG, "vitamio init failed....change state to error");
					stateChange(State.ERROR);
				}
			}

		}.execute();
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
	 *            The height of this view in px.
	 */
	public void setRealHeight(int realHeight) {
		this.mRealHeight = realHeight;
	}

	private void attachMediaController() {
		if (isUseVitamio) {
			if (mVitamioMediaPlayer != null && mMediaController != null) {
				mMediaController.setMediaPlayer(this);
				View anchorView = this.getParent() instanceof View ? (View) this
						.getParent() : this;
				mMediaController.setAnchorView(anchorView);
				mMediaController.setEnabled(isInPlaybackState());
			}
		} else {
			if (mMediaPlayer != null && mMediaController != null) {
				mMediaController.setMediaPlayer(this);
				View anchorView = this.getParent() instanceof View ? (View) this
						.getParent() : this;
				mMediaController.setAnchorView(anchorView);
				mMediaController.setEnabled(isInPlaybackState());
			}
		}
	}

	/*
	 * release the media player in any state
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

		// vitamio
		if (mVitamioMediaPlayer != null) {
			mVitamioMediaPlayer.reset();
			mVitamioMediaPlayer.release();
			mVitamioMediaPlayer = null;
			mCurrentState = STATE_IDLE;
			if (cleartargetstate) {
				mTargetState = STATE_IDLE;
			}
		}

		stateChange(State.IDLE);
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

		// vitamio
		if (mVitamioMediaPlayer != null) {
			mVitamioMediaPlayer.reset();
			mCurrentState = STATE_IDLE;
			if (cleartargetstate) {
				mTargetState = STATE_IDLE;
			}
		}

		stateChange(State.IDLE);
	}

	// vitamio
	private void createVitamioMediaPlayer() throws IllegalArgumentException,
			SecurityException, IllegalStateException, IOException {
		mVitamioMediaPlayer = new io.vov.vitamio.MediaPlayer(mContext);
		mVitamioMediaPlayer.setOnPreparedListener(mVitamioPreparedListener);
		mVitamioMediaPlayer
				.setOnVideoSizeChangedListener(mVitamioSizeChangedListener);
		mVitamioMediaPlayer.setOnCompletionListener(mVitamioCompletionListener);
		mVitamioMediaPlayer.setOnErrorListener(mVitamioErrorListener);
		mVitamioMediaPlayer.setOnInfoListener(mVitamioInfoListener);
		mVitamioMediaPlayer
				.setOnBufferingUpdateListener(mVitamioBufferingUpdateListener);
		mVitamioMediaPlayer
				.setOnSeekCompleteListener(mVitamioSeekCompleteListener);
		mVitamioMediaPlayer.setDataSource(mContext, mUri);
		// let's surface view show the image
		mVitamioMediaPlayer.setDisplay(mSurfaceHolder);
		mVitamioMediaPlayer
				.setVideoChroma(mVideoChroma == io.vov.vitamio.MediaPlayer.VIDEOCHROMA_RGB565 ? io.vov.vitamio.MediaPlayer.VIDEOCHROMA_RGB565
						: io.vov.vitamio.MediaPlayer.VIDEOCHROMA_RGBA);
		mVitamioMediaPlayer.setScreenOnWhilePlaying(true);
		mVitamioMediaPlayer.prepareAsync();

		mCurrentBufferPercentage = 0;
		// we don't set the target state here either, but preserve
		// the
		// target state that was there before.
		mCurrentState = STATE_PREPARING;
		stateChange(State.PREPARING);
		attachMediaController();
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
				if (isUseVitamio) {
					// vitamio
					if (mVitamioMediaPlayer != null
							&& mVitamioMediaPlayer.isPlaying()) {
						pause();
						mMediaController.show();
					} else {
						start();
						mMediaController.hide();
					}
				} else {
					if (mMediaPlayer != null && mMediaPlayer.isPlaying()) {
						pause();
						mMediaController.show();
					} else {
						start();
						mMediaController.hide();
					}
				}
				return true;
			} else if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
				if (isUseVitamio) {
					// vitamio
					if (mVitamioMediaPlayer != null
							&& !mVitamioMediaPlayer.isPlaying()) {
						start();
						mMediaController.hide();
					}
				} else {
					if (mMediaPlayer != null && !mMediaPlayer.isPlaying()) {
						start();
						mMediaController.hide();
					}
				}

				return true;
			} else if (keyCode == KeyEvent.KEYCODE_MEDIA_STOP
					|| keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
				if (isUseVitamio) {
					// vitamio
					if (mVitamioMediaPlayer != null
							&& mVitamioMediaPlayer.isPlaying()) {
						pause();
						mMediaController.show();
					}
				} else {
					if (mMediaPlayer != null && mMediaPlayer.isPlaying()) {
						pause();
						mMediaController.show();
					}
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
		if (isUseVitamio) {
			// vitamio
			return (mVitamioMediaPlayer != null && mCurrentState != STATE_ERROR
					&& mCurrentState != STATE_IDLE && mCurrentState != STATE_PREPARING);
		} else {
			return (mMediaPlayer != null && mCurrentState != STATE_ERROR
					&& mCurrentState != STATE_IDLE && mCurrentState != STATE_PREPARING);
		}
	}

	private void stateChange(State state) {
		if (mOnStateChangeListener != null) {
			mOnStateChangeListener.stateChange(state);
		}
	}

	/**
	 * Set the view show when is buffering
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

	// vitamio
	public void setOnPreparedListener(
			io.vov.vitamio.MediaPlayer.OnPreparedListener onPreparedListener) {
		mVitamioOnPreparedListener = onPreparedListener;
	}

	public void setOnCompletionListener(
			MediaPlayer.OnCompletionListener onCompletionListener) {
		mOnCompletionListener = onCompletionListener;
	}

	// vitamio
	public void setOnCompletionListener(
			io.vov.vitamio.MediaPlayer.OnCompletionListener onCompletionListener) {
		mVitamioOnCompletionListener = onCompletionListener;
	}

	public void setOnErrorListener(MediaPlayer.OnErrorListener onErrorListener) {
		mOnErrorListener = onErrorListener;
	}

	// vitamio
	public void setOnErrorListener(
			io.vov.vitamio.MediaPlayer.OnErrorListener onErrorListener) {
		mVitamioOnErrorListener = onErrorListener;
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

	// vitamio
	public void setOnInfoListener(
			io.vov.vitamio.MediaPlayer.OnInfoListener onInfoListener) {
		mVitamioOnInfoListener = onInfoListener;
	}

	public void setOnSeekCompleteListener(
			MediaPlayer.OnSeekCompleteListener onSeekCompleteListener) {
		mOnSeekCompleteListener = onSeekCompleteListener;
	}

	// vitamio
	public void setOnSeekCompleteListener(
			io.vov.vitamio.MediaPlayer.OnSeekCompleteListener onSeekCompleteListener) {
		mVitamioOnSeekCompleteListener = onSeekCompleteListener;
	}

	public void setOnBufferingUpdateListener(
			MediaPlayer.OnBufferingUpdateListener onBufferingUpdateListener) {
		mOnBufferingUpdateListener = onBufferingUpdateListener;
	}

	// vitamio
	public void setOnBufferingUpdateListener(
			io.vov.vitamio.MediaPlayer.OnBufferingUpdateListener onBufferingUpdateListener) {
		mVitamioOnBufferingUpdateListener = onBufferingUpdateListener;
	}

	/**
	 * Set the OnStateChangeListener, from the callback method can get the
	 * current state of the video player.
	 * 
	 * @param stateChangeListener
	 */
	public void setOnStateChangeListener(
			OnStateChangeListener stateChangeListener) {
		mOnStateChangeListener = stateChangeListener;
	}

	/**
	 * Must set before {@link #setVideoURI} This is only useful when use vitamio
	 * media player
	 * 
	 * @param chroma
	 */
	public void setVideoChroma(int chroma) {
		// vitamio
		getHolder()
				.setFormat(
						chroma == io.vov.vitamio.MediaPlayer.VIDEOCHROMA_RGB565 ? PixelFormat.RGB_565
								: PixelFormat.RGBA_8888); // PixelFormat.RGB_565
		mVideoChroma = chroma;
	}

	/**
	 * Only useful when use vitamio media player.
	 * 
	 * @param quality
	 */
	public void setVideoQuality(int quality) {
		// vitamio
		if (mVitamioMediaPlayer != null)
			mVitamioMediaPlayer.setVideoQuality(quality);
	}

	public void setVolume(float leftVolume, float rightVolume) {
		if (isUseVitamio) {
			// vitamio
			if (mVitamioMediaPlayer != null) {
				mVitamioMediaPlayer.setVolume(leftVolume, rightVolume);
			}
		} else {
			if (mMediaPlayer != null) {
				mMediaPlayer.setVolume(leftVolume, rightVolume);
			}
		}
	}

	public int getVideoWidth() {
		return mVideoWidth;
	}

	public int getVideoHeight() {
		return mVideoHeight;
	}

	public float getVideoAspectRatio() {
		return mVideoAspectRatio;
	}

	/**
	 * Get the buffering state of the media player.
	 * 
	 * @return true if is in buffering, else return false.
	 */
	public boolean isBuffering() {
		if (isUseVitamio) {
			if (mVitamioMediaPlayer != null) {
				return mVitamioMediaPlayer.isBuffering();
			}
		} else {
			if (mMediaPlayer != null)
				return mIsBuffering;
		}
		return false;
	}

	public boolean isUseVitamio() {
		return isUseVitamio;
	}

	/**
	 * Set whether to use vitamio media player.Must set before
	 * {@link #setVideoURI}.
	 * 
	 * @param mIsUseVitamio
	 */
	public void setIsUseVitamio(boolean isUseVitamio) {
		this.isUseVitamio = isUseVitamio;
	}

	public boolean isLive() {
		return isLive;
	}

	/**
	 * Set if is playing live stream.Must set before {@link #setVideoURI}
	 * 
	 * @param mIsLive
	 */
	public void setIsLive(boolean isLive) {
		this.isLive = isLive;
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
		if (isUseVitamio) {
			// vitamio
			if (mVitamioMediaPlayer != null) {
				return mCurrentBufferPercentage;
			}
		} else {
			if (mMediaPlayer != null) {
				return mCurrentBufferPercentage;
			}
		}
		return 0;
	}

	@Override
	public int getCurrentPosition() {
		if (isUseVitamio) {
			// vitamio
			if (isInPlaybackState() && mVitamioMediaPlayer != null) {
				return (int) mVitamioMediaPlayer.getCurrentPosition();
			}
		} else {
			if (isInPlaybackState() && mMediaPlayer != null) {
				return mMediaPlayer.getCurrentPosition();
			}
		}
		return 0;
	}

	@Override
	public int getDuration() {
		if (isUseVitamio) {
			// vitamio
			if (isInPlaybackState() && mVitamioMediaPlayer != null) {
				return (int) mVitamioMediaPlayer.getDuration();
			}
		} else {
			if (isInPlaybackState() && mMediaPlayer != null) {
				return mMediaPlayer.getDuration();
			}
		}

		return -1;
	}

	@Override
	public boolean isPlaying() {
		if (isUseVitamio) {
			// vitamio
			return isInPlaybackState() && mVitamioMediaPlayer != null
					&& mVitamioMediaPlayer.isPlaying();
		} else {
			return isInPlaybackState() && mMediaPlayer != null
					&& mMediaPlayer.isPlaying();
		}
	}

	@Override
	public void pause() {
		if (isInPlaybackState()) {
			if (mMediaPlayer != null && mMediaPlayer.isPlaying()) {
				mMediaPlayer.pause();
				mCurrentState = STATE_PAUSED;
			}

			// vitamio
			if (mVitamioMediaPlayer != null && mVitamioMediaPlayer.isPlaying()) {
				mVitamioMediaPlayer.pause();
				mCurrentState = STATE_PAUSED;
			}
		}
		mTargetState = STATE_PAUSED;
		stateChange(State.PAUSED);
	}

	@Override
	public void seekTo(int pos) {
		if (isInPlaybackState()) {
			if (isUseVitamio) {
				// vitamio
				if (mVitamioMediaPlayer != null) {
					mVitamioMediaPlayer.seekTo((int) pos);
				}
			} else {
				if (mMediaPlayer != null) {
					mMediaPlayer.seekTo((int) pos);
				}
			}
			mSeekWhenPrepared = 0;
		} else {
			mSeekWhenPrepared = (int) pos;
		}
	}

	@Override
	public void start() {
		if (isInPlaybackState()) {
			if (isUseVitamio) {
				// vitamio
				mVitamioMediaPlayer.start();
			} else {
				mMediaPlayer.start();
			}
			mCurrentState = STATE_PLAYING;
		}
		mTargetState = STATE_PLAYING;
		stateChange(State.PLAYING);
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
			stateChange(State.SUSPEND);
		} else {
			release(false);
			mCurrentState = STATE_SUSPEND_UNSUPPORTED;
			stateChange(State.SUSPEND_UNSUPPORTED);
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
		stateChange(State.RESUME);
	}

	/**
	 * Get the current state of VideoView
	 */
	public interface OnStateChangeListener {

		/**
		 * All the state of VideoView
		 */
		public enum State {
			IDLE, PREPARING, PREPARED, PLAYING, PAUSED, //
			PLAYBACK_COMPLETED, SUSPEND_UNSUPPORTED, SUSPEND, ERROR, //
			RESUME, STOPPLAYBACK, BUFFERING_START, BUFFERING_END, VITAMIO_INITIALIZING
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
