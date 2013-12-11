package com.charon.universalvideoview;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.LinearLayout.LayoutParams;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;

import com.charon.universalvideoview.util.DenstyUtil;
import com.charon.universalvideoview.video.MediaController;
import com.charon.universalvideoview.video.UniversalVideoView;
import com.charon.universalvideoview.video.UniversalVideoView.OnStateChangeListener;

/*
 * We can use setOnPreparedListener to set the loading view or use setOnStateChangeListener to get 
 * the preparing or prepared state to control the loading view
 */
public class UniversalVideoViewActivity extends Activity {
	private static final String TAG = "UniversalVideoViewActivity";
	private UniversalVideoView mUniversalVideoView;
	private ProgressBar pb;
	private RelativeLayout rl_video;
	private LinearLayout ll_root;
	/*
	 * TODO Set the path variable to a streaming video URL or a local media file
	 * path.
	 */
	private String path = "mnt/sdcard/1.mp4";

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (isLandScape(this)) {
			setFullscreen(true);
		}

		setContentView(R.layout.activity_universalvideoview);
		findView();
		initView();
	}

	private void findView() {
		mUniversalVideoView = (UniversalVideoView) findViewById(R.id.uvv);
		pb = (ProgressBar) findViewById(R.id.pb);
		rl_video = (RelativeLayout) findViewById(R.id.rl_video);
		ll_root = (LinearLayout) findViewById(R.id.ll_root);
	}

	private void initView() {
		mUniversalVideoView.setMediaController(new MediaController(this));
		// Set the real height of this video view, so it will not use the window
		// height to calculate the ratio
		if (!isLandScape(this)) {
			mUniversalVideoView.setRealHeight((int) DenstyUtil
					.convertDpToPixel(240, UniversalVideoViewActivity.this));
		}
		mUniversalVideoView.setVideoPath(path, true);
		// pb.setVisibility(View.VISIBLE);
		mUniversalVideoView.setOnCompletionListener(new OnCompletionListener() {

			@Override
			public void onCompletion(MediaPlayer mp) {
				// mUniversalVideoView.setVideoPath("mnt/sdcard/1.mp4");
				// pb.setVisibility(View.VISIBLE);
			}
		});

		// mVideoView.setOnPreparedListener(new OnPreparedListener() {
		//
		// @Override
		// public void onPrepared(MediaPlayer mp) {
		// pb.setVisibility(View.GONE);
		// }
		// });

		mUniversalVideoView
				.setOnStateChangeListener(new OnStateChangeListener() {

					@Override
					public void stateChange(State state) {
						if (state == State.PREPARING) {
							pb.setVisibility(View.VISIBLE);
						} else if (state == State.PREPARED) {
							pb.setVisibility(View.GONE);
						} else if (state == State.ERROR) {
							pb.setVisibility(View.GONE);
						} else if (state == State.VITAMIO_INITIALIZING) {
							pb.setVisibility(View.VISIBLE);
						}
					}
				});
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
			Log.i(TAG, "change to landscape..");
			setFullscreen(true);
			// make other view invisible
			int count = ll_root.getChildCount();
			for (int i = 0; i < count; i++) {
				View v = ll_root.getChildAt(i);

				if (v.getId() == R.id.rl_video) {
					continue;
				}
				v.setVisibility(View.GONE);
			}

			LayoutParams layoutParams = (LayoutParams) rl_video
					.getLayoutParams();
			layoutParams.width = LayoutParams.MATCH_PARENT;
			layoutParams.height = LayoutParams.MATCH_PARENT;
			rl_video.setLayoutParams(layoutParams);
			rl_video.requestLayout();

			mUniversalVideoView.setVideoLayout(
					UniversalVideoView.VIDEO_LAYOUT_SCALE, 0, true);
		} else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
			setFullscreen(false);
			int count = ll_root.getChildCount();
			for (int i = 0; i < count; i++) {
				View v = ll_root.getChildAt(i);
				if (v.getId() == R.id.rl_video) {
					LayoutParams params = (LayoutParams) rl_video
							.getLayoutParams();
					params.height = (int) DenstyUtil.convertDpToPixel(240,
							UniversalVideoViewActivity.this);
					params.width = RelativeLayout.LayoutParams.MATCH_PARENT;
					params.gravity = Gravity.CENTER;
					rl_video.setLayoutParams(params);
					rl_video.requestLayout();
					continue;
				}
				v.setVisibility(View.VISIBLE);
			}

			// set the real height of this video view, so it will not use the
			// window height to calculate the ratio
			mUniversalVideoView.setRealHeight((int) DenstyUtil
					.convertDpToPixel(240, UniversalVideoViewActivity.this));
			mUniversalVideoView.setVideoLayout(
					UniversalVideoView.VIDEO_LAYOUT_SCALE, 0, true);
		}
	}

	@Override
	protected void onPause() {
		super.onPause();
		Log.d(TAG, "onPause");
		if (mUniversalVideoView != null)
			mUniversalVideoView.suspend();
	}

	@Override
	protected void onResume() {
		super.onResume();
		Log.d(TAG, "onResume");
		if (mUniversalVideoView != null)
			mUniversalVideoView.resume();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		Log.d(TAG, "onDestroy");
		if (mUniversalVideoView != null)
			mUniversalVideoView.stopPlayback();
	}

	public static boolean isLandScape(Context context) {
		return context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
	}

	private void setFullscreen(boolean on) {
		Window window = getWindow();
		WindowManager.LayoutParams winParams = window.getAttributes();
		final int bits = WindowManager.LayoutParams.FLAG_FULLSCREEN;
		if (on) {
			winParams.flags |= bits;
		} else {
			winParams.flags &= ~bits;
		}
		window.setAttributes(winParams);
	}

}
