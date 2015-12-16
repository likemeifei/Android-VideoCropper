package com.crust87.videocropper;

import android.app.ProgressDialog;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.provider.MediaStore;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import com.crust87.ffmpegexecutor.FFmpegExecutor;
import com.crust87.videocropview.VideoCropView;
import com.crust87.videotrackview.VideoTrackView;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {
    // Layout Components
    private VideoCropView mVideoCropView;
    private VideoTrackView mAnchorVideoTrackView;
    private AnchorOverlay mAnchorOverlay;
    private ProgressDialog mProgressDialog;

    private TextView mTextSeek;
    private TextView mTextDuration;

    // Component
    private FFmpegExecutor mExecutor;

    // Attributes
    private String originalPath;

    // Working Variables
    private int mVideoSeek;			// generated video seek
    private int mVideoDuration;		// generated video duration


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        loadGUI();
        initFFmpeg();
        bindEvent();
    }

    private void loadGUI() {
        setContentView(R.layout.activity_main);

        mVideoCropView = (VideoCropView) findViewById(R.id.cropVideoView);
        mAnchorVideoTrackView = (VideoTrackView) findViewById(R.id.anchorVideoTrackView);
        mTextSeek = (TextView) findViewById(R.id.textSeek);
        mTextDuration = (TextView) findViewById(R.id.textDuration);

        mAnchorOverlay = new AnchorOverlay(getApplicationContext());
        mAnchorVideoTrackView.setVideoTrackOverlay(mAnchorOverlay);
    }

    private void initFFmpeg() {
        File ffmpegDirPath = new File(getApplicationContext().getFilesDir().getAbsolutePath() + "/ffmpeg");
        if(!ffmpegDirPath.exists()) {
            ffmpegDirPath.mkdir();
        }

        try {
            InputStream ffmpegInputStream = getApplicationContext().getAssets().open("ffmpeg");
            FileMover fm = new FileMover(ffmpegInputStream, ffmpegDirPath.getAbsolutePath() + "/ffmpeg");
            fm.moveIt();
        } catch (IOException e) {
            e.printStackTrace();
        }

        try {
            String[] args = { "/system/bin/chmod", "755", ffmpegDirPath.getAbsolutePath() + "/ffmpeg" };
            Process process = new ProcessBuilder(args).start();
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            process.destroy();
        } catch (IOException e) {
            e.printStackTrace();
        }

        mExecutor = new FFmpegExecutor(getApplicationContext(), ffmpegDirPath.getAbsolutePath() + "/ffmpeg");
    }

    private void bindEvent() {
        mVideoCropView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {

            @Override
            public void onPrepared(MediaPlayer mp) {
                mVideoCropView.start();
            }
        });

        mExecutor.setOnReadProcessLineListener(new FFmpegExecutor.OnReadProcessLineListener() {
            @Override
            public void onReadProcessLine(String line) {
                Message message = Message.obtain();
                message.obj = line;
                message.setTarget(mMessageHandler);
                message.sendToTarget();
            }
        });

        mAnchorOverlay.setOnUpdateAnchorListener(new AnchorOverlay.OnUpdateAnchorListener() {
            @Override
            public void onUpdatePositionStart() {
                mVideoCropView.pause();
            }

            @Override
            public void onUpdatePosition(int seek, int duration) {
                mVideoSeek = seek;
                mVideoDuration = duration;

                mTextSeek.setText("seek: " + mVideoSeek / 1000f);
                mTextDuration.setText("duration: " + mVideoDuration / 1000f);
            }

            @Override
            public void onUpdatePositionEnd(int seek, int duration) {
                mVideoSeek = seek;
                mVideoDuration = duration;

                mVideoCropView.seekTo(mVideoSeek);
                mVideoCropView.start();
            }
        });
    }

    private Handler mMessageHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);

            String message = (String) msg.obj;
            if(mProgressDialog != null) {
                mProgressDialog.setMessage(message);
            }
        }
    };

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == 1000 && resultCode == RESULT_OK) {
            setOriginalVideo(data.getData());
        }
    }

    public void onButtonLoadClick(View v) {
        Intent lIntent = new Intent(Intent.ACTION_PICK);
        lIntent.setType("video/*");
        lIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivityForResult(lIntent, 1000);
    }

    public void onButtonCropClick(View v) {
        mVideoCropView.pause();

        new AsyncTask<Void, Void, Void>() {

            float scale;
            int viewWidth;
            int viewHeight;
            int width;
            int height;
            int positionX;
            int positionY;
            int videoWidth;
            int videoHeight;
            int rotate;
            String start;
            String dur;

            @Override
            protected void onPreExecute() {
                mExecutor.init();
                mProgressDialog = ProgressDialog.show(MainActivity.this, null, "execute....", true);

                scale = mVideoCropView.getScale();
                viewWidth = mVideoCropView.getWidth();
                viewHeight = mVideoCropView.getHeight();
                width = (int)(viewWidth * scale);
                height = (int)(viewHeight * scale);
                positionX = (int) mVideoCropView.getRealPositionX();
                positionY = (int) mVideoCropView.getRealPositionY();
                videoWidth = mVideoCropView.getVideoWidth();
                videoHeight = mVideoCropView.getVideoHeight();
                rotate = mVideoCropView.getRotate();

                int startMinute = mVideoSeek / 60000;
                int startSeconds = mVideoSeek - startMinute*60000;
                start = String.format("00:%02d:%02.2f", startMinute, startSeconds/1000f);
                dur = String.format("00:00:%02.2f", mVideoDuration/1000f);
            }

            @Override
            protected Void doInBackground(Void... params) {
                try {
                    String filter = "";

                    if(rotate == 0) {
                        filter = "crop="+width+":"+height+":"+positionX+":"+positionY+", scale=640:640, setsar=1:1";
                    } else if(rotate == 90) {
                        filter = "crop="+height+":"+width+":"+positionY+":"+positionX +", scale=640:640, setsar=1:1";
                    } else if(rotate == 180) {
                        filter = "crop="+width+":"+height+":"+(videoWidth - positionX - width)+":"+positionY+ ", scale=640:640, setsar=1:1";
                    } else if(rotate == 270) {
                        filter = "crop="+height+":"+width+":"+(videoHeight - positionY - height)+":"+positionX + ", scale=640:640, setsar=1:1";
                    } else {
                        filter = "crop="+width+":"+height+":"+positionX+":"+positionY+", scale=640:640, setsar=1:1";
                    }

                    mExecutor.putCommand("-y")
                            .putCommand("-i")
                            .putCommand(originalPath)
                            .putCommand("-vcodec")
                            .putCommand("libx264")
                            .putCommand("-profile:v")
                            .putCommand("baseline")
                            .putCommand("-level")
                            .putCommand("3.1")
                            .putCommand("-b:v")
                            .putCommand("1000k")
                            .putCommand("-ss")
                            .putCommand(start)
                            .putCommand("-t")
                            .putCommand(dur)
                            .putCommand("-vf")
                            .putCommand(filter)
                            .putCommand("-c:a")
                            .putCommand("copy")
                            .putCommand(Environment.getExternalStorageDirectory().getAbsolutePath() + "/result.mp4")
                            .executeCommand();
                } catch (IOException e) {
                    e.printStackTrace();
                }

                return null;
            }

            @Override
            protected void onPostExecute(Void aVoid) {
                mProgressDialog.dismiss();
                mProgressDialog = null;
            }

        }.execute();
    }

    // Initialization original video
    private void setOriginalVideo(Uri uri) {
        originalPath = getRealPathFromURI(uri);

        mVideoCropView.setVideoURI(uri);
        mVideoCropView.seekTo(1);

        mAnchorVideoTrackView.setVideo(originalPath);
    }

    public String getRealPathFromURI(Uri contentUri) {
        Cursor cursor = null;
        try {
            String[] proj = { MediaStore.Images.Media.DATA };
            cursor = getApplicationContext().getContentResolver().query(contentUri, proj, null, null, null);
            int column_index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
            cursor.moveToFirst();
            return cursor.getString(column_index);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    // Copy file
    private class FileMover {

        private InputStream mInputStream;
        private String mDestination;

        public FileMover(InputStream inputStream, String destination) {
            mInputStream = inputStream;
            mDestination = destination;
        }

        public void moveIt() throws IOException {

            File destinationFile = new File(mDestination);
            OutputStream destinationOut = new BufferedOutputStream(new FileOutputStream(destinationFile));

            int numRead;
            byte[] buf = new byte[1024];
            while ((numRead = mInputStream.read(buf) ) >= 0) {
                destinationOut.write(buf, 0, numRead);
            }

            destinationOut.flush();
            destinationOut.close();
        }
    }
}