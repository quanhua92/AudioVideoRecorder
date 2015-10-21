package com.quan404.recordvideotomp4;

import android.app.Activity;
import android.content.res.Resources;
import android.hardware.Camera;
import android.media.MediaCodec;
import android.media.MediaMuxer;
import android.os.Environment;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;

import java.io.File;

/**
 * Record the camera (no audio) to a MP4 file in sdcard.
 * Basically, we can use MediaRecorder to this task.
 * This example is for editing of the video as MediaCodec is encoding the video.
 *
 * Required: Android 4.3 ( API 18 )
 *
 * Output: /sdcard/recordvideo.mp4
 *
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final boolean DEBUG = true;

    /**
     * Get the sdcard path.
     * Permission: WRITE_EXTERNAL_STORAGE
     */
    private File SDCARD_DIR = Environment.getExternalStorageDirectory();

    /**
     * Encoder & Muxer
     */
    private MediaCodec mEncoder;
    private MediaMuxer mMuxer;

    private MediaCodec.BufferInfo mBufferInfo;

    // Encoder Parameters
    private static final String MIME_TYPE = "video/avc";    // H.264 Advanced Video Coding
    private static final int FRAME_WIDTH = 640;             // 640px
    private static final int FRAME_HEIGHT = 480;            // 480px
    private static final int FRAME_RATE = 30;               // 30fps
    private static final int IFRAME_INTERVAL = 5;           // 5 seconds between I-frames
    private static final long DURATION_SEC = 8;             // 8 seconds of video
    private static final long BITRATE = 6000000;            // 6000000 Mbps

    /**
     * Camera Stuff
     */
    private Camera mCamera;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);


        Button button = (Button) findViewById(R.id.recordVideoToMP4);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (DEBUG) {
                    Log.d(TAG, "Button recordVideoToMP4 onClick");
                }


                prepareCamera(FRAME_WIDTH, FRAME_HEIGHT);
            }
        });
    }


    @Override
    protected void onStop() {
        releaseCamera();

        super.onStop();
    }


    /**
     * Camera functions
     */
    private void prepareCamera(int expectedWidth, int expectedHeight){
        if (DEBUG){
            Log.d(TAG, "prepareCamera");
        }

        try{
            // Open front camera
            Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
            for(int i = 0 ; i < Camera.getNumberOfCameras() ; i ++){
                Camera.getCameraInfo(i, cameraInfo);
                if( cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT ){
                    mCamera = Camera.open(i);
                }
            }

            // Else open rear camera
            if (mCamera == null){
                mCamera = Camera.open();
            }

            // Else throw an Error
            if (mCamera == null){
                throw new RuntimeException("Can not open any camera");
            }

            // Select a preview size that match the expectedWidth & expectedHeight
            Camera.Parameters params = mCamera.getParameters();
            choosePreviewSize(params, expectedWidth, expectedHeight);

        }catch (Exception e){
            e.printStackTrace();
        }
    }
    private void choosePreviewSize(Camera.Parameters params , int width, int height){
        Camera.Size ppsfv = params.getPreferredPreviewSizeForVideo();

        if (DEBUG){
            Log.d(TAG, "choosePreviewSize: PreferredPreviewSize width = " + ppsfv.width + " height = " + ppsfv.height);
        }

        for (Camera.Size size : params.getSupportedPreviewSizes()) {
            if (size.width == width && size.height == height) {
                if(DEBUG) Log.d(TAG, "choosePreviewSize: setPreviewSize to " + width + " " + height);
                params.setPreviewSize(width, height);
                return;
            }
        }

        Log.d(TAG, "choosePreviewSize: Unable to set preview size to " + width + " " + height + " revert to " + ppsfv.width + " " + ppsfv.height);
        if(ppsfv != null){
            params.setPreviewSize(ppsfv.width, ppsfv.height);
        }
    }
    private void releaseCamera(){
        if (DEBUG){
            Log.d(TAG, "releaseCamera");
        }
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }
    }
}
