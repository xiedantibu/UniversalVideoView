package com.charon.video.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.PopupWindow;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;

import com.charon.video.R;
import com.charon.video.util.StringUtils;

public class MediaController extends FrameLayout {

	private static final int sDefaultTimeout = 3000;
	private static final int FADE_OUT = 1;
	private static final int SHOW_PROGRESS = 2;

	private MediaPlayerControl mPlayer;
	private Context mContext;
	private PopupWindow mPopupWindow;
	private int mAnimStyle;
	/**
	 * View that acts as the anchor for the control view. this will be set on
	 * the setAnchorView method
	 */
	private View mAnchor;
	private View mMediaController;
	private SeekBar mProgress;
	private TextView mEndTime, mCurrentTime;
	private TextView mFileName;
	private TextView mInfoView;
	private String mTitle;

	private long mDuration;
	private boolean mShowing;
	private boolean mDragging;
	private boolean mInstantSeeking = true;
	private boolean mFromXml = false;

	private ImageButton mPauseButton;
	private AudioManager mAM;
	private OnShownListener mShownListener;
	private OnHiddenListener mHiddenListener;

	@SuppressLint("HandlerLeak")
	private Handler mHandler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			long pos;
			switch (msg.what) {
			case FADE_OUT:
				hide();
				break;
			case SHOW_PROGRESS:
				pos = setProgress();
				if (!mDragging && mShowing) {
					msg = obtainMessage(SHOW_PROGRESS);
					sendMessageDelayed(msg, 1000 - (pos % 1000));
					updatePausePlay();
				}
				break;
			}
		}
	};

	private View.OnClickListener mPauseListener = new View.OnClickListener() {
		public void onClick(View v) {
			doPauseResume();
			show(sDefaultTimeout);
		}
	};

	private OnSeekBarChangeListener mSeekListener = new OnSeekBarChangeListener() {
		public void onStartTrackingTouch(SeekBar bar) {
			mDragging = true;
			show(3600000);
			mHandler.removeMessages(SHOW_PROGRESS);
			if (mInstantSeeking)
				mAM.setStreamMute(AudioManager.STREAM_MUSIC, true);
			if (mInfoView != null) {
				mInfoView.setText("");
				mInfoView.setVisibility(View.VISIBLE);
			}
		}

		public void onProgressChanged(SeekBar bar, int progress,
				boolean fromuser) {
			if (!fromuser)
				return;

			long newposition = (mDuration * progress) / 1000;
			String time = StringUtils.generateTime(newposition);
			if (mInstantSeeking)
				mPlayer.seekTo((int) newposition);
			if (mInfoView != null)
				mInfoView.setText(time);
			if (mCurrentTime != null)
				mCurrentTime.setText(time);
		}

		public void onStopTrackingTouch(SeekBar bar) {
			if (!mInstantSeeking)
				mPlayer.seekTo((int) ((mDuration * bar.getProgress()) / 1000));
			if (mInfoView != null) {
				mInfoView.setText("");
				mInfoView.setVisibility(View.GONE);
			}
			show(sDefaultTimeout);
			mHandler.removeMessages(SHOW_PROGRESS);
			mAM.setStreamMute(AudioManager.STREAM_MUSIC, false);
			mDragging = false;
			mHandler.sendEmptyMessageDelayed(SHOW_PROGRESS, 1000);
		}
	};

	public MediaController(Context context, AttributeSet attrs) {
		super(context, attrs);
		mMediaController = this;
		mFromXml = true;
		initController(context);
	}

	public MediaController(Context context) {
		super(context);
		if (!mFromXml && initController(context))
			initFloatingWindow();
	}

	private boolean initController(Context context) {
		mContext = context;
		mAM = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
		return true;
	}

	@Override
	public void onFinishInflate() {
		if (mMediaController != null)
			initControllerView(mMediaController);
	}

	private void initFloatingWindow() {
		mPopupWindow = new PopupWindow(mContext);
		mPopupWindow.setFocusable(false);
		mPopupWindow.setBackgroundDrawable(null);
		mPopupWindow.setOutsideTouchable(true);
		mAnimStyle = android.R.style.Animation;
	}

	/**
	 * Set the view that acts as the anchor for the control view. This can for
	 * example be a VideoView, or your Activity's main view.
	 * 
	 * @param view
	 *            The view to which to anchor the controller when it is visible.
	 */
	public void setAnchorView(View view) {
		mAnchor = view;
		if (!mFromXml) {
			removeAllViews();
			mMediaController = makeControllerView();
			mPopupWindow.setContentView(mMediaController);
			mPopupWindow.setWidth(LayoutParams.MATCH_PARENT);
			mPopupWindow.setHeight(LayoutParams.WRAP_CONTENT);
		}
		initControllerView(mMediaController);
	}

	/**
	 * Create the view that holds the widgets that control playback. Derived
	 * classes can override this to create their own.
	 * 
	 * @return The controller view.
	 */
	protected View makeControllerView() {
		return ((LayoutInflater) mContext
				.getSystemService(Context.LAYOUT_INFLATER_SERVICE)).inflate(
				R.layout.mediacontroller, this);
	}

	/**
	 * Find the view of the media controller and set the listener of the
	 * component
	 * 
	 * @param v
	 */
	private void initControllerView(View v) {
		mPauseButton = (ImageButton) v
				.findViewById(R.id.mediacontroller_play_pause);
		if (mPauseButton != null) {
			mPauseButton.requestFocus();
			mPauseButton.setOnClickListener(mPauseListener);
		}

		mProgress = (SeekBar) v.findViewById(R.id.mediacontroller_seekbar);
		if (mProgress != null) {
			if (mProgress instanceof SeekBar) {
				SeekBar seeker = (SeekBar) mProgress;
				seeker.setOnSeekBarChangeListener(mSeekListener);
				seeker.setThumbOffset(1);
			}
			mProgress.setMax(1000);
		}

		mEndTime = (TextView) v.findViewById(R.id.mediacontroller_time_total);
		mCurrentTime = (TextView) v
				.findViewById(R.id.mediacontroller_time_current);
		mFileName = (TextView) v.findViewById(R.id.mediacontroller_file_name);
		if (mFileName != null)
			mFileName.setText(mTitle);
	}

	public void setMediaPlayer(MediaPlayerControl player) {
		mPlayer = player;
		updatePausePlay();
	}

	/**
	 * Control the action when the seekbar dragged by user
	 * 
	 * @param seekWhenDragging
	 *            True the media will seek periodically
	 */
	public void setInstantSeeking(boolean seekWhenDragging) {
		mInstantSeeking = seekWhenDragging;
	}

	public void show() {
		show(sDefaultTimeout);
	}

	/**
	 * Set the content of the file_name TextView
	 * 
	 * @param name
	 */
	public void setFileName(String name) {
		mTitle = name;
		if (mFileName != null)
			mFileName.setText(mTitle);
	}

	/**
	 * Set the View to hold some information when interact with the
	 * MediaController
	 * 
	 * @param v
	 */
	public void setInfoView(TextView v) {
		mInfoView = v;
	}

	private void disableUnsupportedButtons() {
		try {
			if (mPauseButton != null && !mPlayer.canPause())
				mPauseButton.setEnabled(false);
		} catch (IncompatibleClassChangeError ex) {
		}
	}

	/**
	 * <p>
	 * Change the animation style resource for this controller.
	 * </p>
	 * <p/>
	 * <p>
	 * If the controller is showing, calling this method will take effect only
	 * the next time the controller is shown.
	 * </p>
	 * 
	 * @param animationStyle
	 *            animation style to use when the controller appears and
	 *            disappears. Set to -1 for the default animation, 0 for no
	 *            animation, or a resource identifier for an explicit animation.
	 */
	public void setAnimationStyle(int animationStyle) {
		mAnimStyle = animationStyle;
	}

	/**
	 * Show the controller on screen. It will go away automatically after
	 * 'timeout' milliseconds of inactivity.
	 * 
	 * @param timeout
	 *            The timeout in milliseconds. Use 0 to show the controller
	 *            until hide() is called.
	 */
	public void show(final int timeout) {
		if (!mShowing && mAnchor != null && mAnchor.getWindowToken() != null) {
			if (mPauseButton != null)
				mPauseButton.requestFocus();
			disableUnsupportedButtons();

			if (mFromXml) {
				setVisibility(View.VISIBLE);
			} else {
				final int[] location = new int[2];
				// getLocationOnScreen may return 0 if the view has not attach
				// to window. so we must use view.post(new runnable()), this
				// will be executed when the view is attached to window.
				// if you want this controller show on activity's onCreate or
				// onResume method or when the video is already execute
				// onPrepared method but anchorView is not attach to the window,
				// at this time getLocationOnScreen may return 0.
				mAnchor.post(new Runnable() {

					@Override
					public void run() {
						mAnchor.getLocationOnScreen(location);

						Rect anchorRect = new Rect(location[0], location[1],
								location[0] + mAnchor.getWidth(), location[1]
										+ mAnchor.getHeight());

						mPopupWindow.setAnimationStyle(mAnimStyle);
						int popupWindowHeight = mPopupWindow.getContentView()
								.getHeight();
						if (popupWindowHeight == 0) {
							measureView(mPopupWindow.getContentView());
							popupWindowHeight = mPopupWindow.getContentView()
									.getMeasuredHeight();
						}
						// PopupWindow will show at the location of the anchor
						// view
						updatePausePlay();
						mPopupWindow.showAtLocation(
								mAnchor,
								Gravity.NO_GRAVITY,
								anchorRect.left,
								anchorRect.bottom
										- mMediaController.getMeasuredHeight());
						mShowing = true;
						if (mShownListener != null)
							mShownListener.onShown();
						Log.d("@@@", "show the contoller");
						mHandler.sendEmptyMessageDelayed(SHOW_PROGRESS, 1000);
						if (timeout != 0) {
							mHandler.removeMessages(FADE_OUT);
							mHandler.sendMessageDelayed(
									mHandler.obtainMessage(FADE_OUT), timeout);
						}
					}
				});
			}

		}

	}

	public boolean isShowing() {
		return mShowing;
	}

	public void hide() {
		if (mAnchor == null)
			return;

		if (mShowing) {
			try {
				mHandler.removeMessages(SHOW_PROGRESS);
				if (mFromXml)
					setVisibility(View.GONE);
				else
					mPopupWindow.dismiss();
			} catch (IllegalArgumentException ex) {
				ex.printStackTrace();
			}
			mShowing = false;
			if (mHiddenListener != null)
				mHiddenListener.onHidden();
		}
	}

	public void setOnShownListener(OnShownListener l) {
		mShownListener = l;
	}

	public void setOnHiddenListener(OnHiddenListener l) {
		mHiddenListener = l;
	}

	private long setProgress() {
		if (mPlayer == null || mDragging)
			return 0;

		long position = mPlayer.getCurrentPosition();
		long duration = mPlayer.getDuration();
		if (mProgress != null) {
			if (duration > 0) {
				long pos = 1000L * position / duration;
				mProgress.setProgress((int) pos);
			}
			int percent = mPlayer.getBufferPercentage();
			mProgress.setSecondaryProgress(percent * 10);
		}

		mDuration = duration;

		if (mEndTime != null)
			mEndTime.setText(StringUtils.generateTime(mDuration));
		if (mCurrentTime != null)
			mCurrentTime.setText(StringUtils.generateTime(position));

		return position;
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		show(sDefaultTimeout);
		return true;
	}

	@Override
	public boolean onTrackballEvent(MotionEvent ev) {
		show(sDefaultTimeout);
		return false;
	}

	@Override
	public boolean dispatchKeyEvent(KeyEvent event) {
		int keyCode = event.getKeyCode();
		if (event.getRepeatCount() == 0
				&& (keyCode == KeyEvent.KEYCODE_HEADSETHOOK
						|| keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_SPACE)) {
			doPauseResume();
			show(sDefaultTimeout);
			if (mPauseButton != null)
				mPauseButton.requestFocus();
			return true;
		} else if (keyCode == KeyEvent.KEYCODE_MEDIA_STOP) {
			if (mPlayer.isPlaying()) {
				mPlayer.pause();
				updatePausePlay();
			}
			return true;
		} else if (keyCode == KeyEvent.KEYCODE_BACK
				|| keyCode == KeyEvent.KEYCODE_MENU) {
			hide();
			return true;
		} else {
			show(sDefaultTimeout);
		}
		return super.dispatchKeyEvent(event);
	}

	private void updatePausePlay() {
		if (mMediaController == null || mPauseButton == null)
			return;

		if (mPlayer.isPlaying())
			mPauseButton
					.setImageResource(R.drawable.mediacontroller_pause_button);
		else
			mPauseButton
					.setImageResource(R.drawable.mediacontroller_play_button);
	}

	private void doPauseResume() {
		if (mPlayer.isPlaying())
			mPlayer.pause();
		else
			mPlayer.start();
		updatePausePlay();
	}

	@Override
	public void setEnabled(boolean enabled) {
		if (mPauseButton != null)
			mPauseButton.setEnabled(enabled);
		if (mProgress != null)
			mProgress.setEnabled(enabled);
		disableUnsupportedButtons();
		super.setEnabled(enabled);
	}

	/**
	 * Measure the view when showing.
	 * 
	 * @param view
	 *            The view will be measure.
	 */
	private void measureView(View view) {
		ViewGroup.LayoutParams lp = view.getLayoutParams();
		if (lp == null) {
			lp = new ViewGroup.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT);
		}

		int childMeasureWidth = ViewGroup.getChildMeasureSpec(0, 0, lp.width);
		int childMeasureHeight;
		if (lp.height > 0) {
			childMeasureHeight = MeasureSpec.makeMeasureSpec(lp.height,
					MeasureSpec.EXACTLY);
		} else {
			// Measure specification mode: The parent has not imposed any
			// constraint on the child. It can be whatever size it wants.
			childMeasureHeight = MeasureSpec.makeMeasureSpec(0,
					MeasureSpec.UNSPECIFIED);
		}
		view.measure(childMeasureWidth, childMeasureHeight);
	}

	public interface OnShownListener {
		public void onShown();
	}

	public interface OnHiddenListener {
		public void onHidden();
	}

	public interface MediaPlayerControl {
		void start();

		void pause();

		int getDuration();

		int getCurrentPosition();

		void seekTo(int pos);

		boolean isPlaying();

		int getBufferPercentage();

		boolean canPause();

		boolean canSeekBackward();

		boolean canSeekForward();
	}
}
