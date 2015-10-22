package com.quan404.recordvideotomp4;

import android.app.Activity;
import android.content.res.Resources;
import android.hardware.Camera;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Environment;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.view.View;
import android.widget.Button;

import java.io.File;
import java.io.IOException;

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
    private File OUTPUT_DIR = Environment.getExternalStorageDirectory();

    /**
     * Encoder & Muxer
     */
    private MediaCodec mEncoder;
    private MediaMuxer mMuxer;
    private int mTrackIndex;
    private boolean mMuxerStarted;
    private CodecInputSurface mInputSurface;
    private MediaCodec.BufferInfo mBufferInfo;

    // Encoder Parameters
    private static final String MIME_TYPE = "video/avc";    // H.264 Advanced Video Coding
    private static final int FRAME_WIDTH = 640;             // 640px
    private static final int FRAME_HEIGHT = 480;            // 480px
    private static final int FRAME_RATE = 30;               // 30fps
    private static final int IFRAME_INTERVAL = 5;           // 5 seconds between I-frames
    private static final long DURATION_SEC = 8;             // 8 seconds of video
    private static final int BITRATE = 6000000;            // 6000000 Mbps

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
                prepareEncoder(FRAME_WIDTH, FRAME_HEIGHT, BITRATE);

            }
        });
    }


    @Override
    protected void onStop() {
        releaseCamera();
        releaseEncoder();
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
        if(ppsfv != null) {
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


    /**
     * Encoder functions
     */
    private void prepareEncoder(int width, int height, int bitRate){
        mBufferInfo = new MediaCodec.BufferInfo();
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);

        // Set some properties.  Failing to specify some of these can cause the MediaCodec
        // configure() call to throw an unhelpful exception.
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);

        if (DEBUG)
            Log.d(TAG, "format: " + format);

        // Create a MediaCodec encoder, and configure it with our format.  Get a Surface
        // we can use for input and wrap it with a class that handles the EGL work.
        //
        // If you want to have two EGL contexts -- one for display, one for recording --
        // you will likely want to defer instantiation of CodecInputSurface until after the
        // "display" EGL context is created, then modify the eglCreateContext call to
        // take eglGetCurrentContext() as the share_context argument.
        try{
            mEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
            mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mInputSurface = new CodecInputSurface(mEncoder.createInputSurface());
            mEncoder.start();
        }catch (IOException ioe){
            throw new RuntimeException("MediaCodec creation failed", ioe);
        }

        // Output filename.  Ideally this would use Context.getFilesDir() rather than a
        // hard-coded output directory.
        String outputPath = new File(OUTPUT_DIR,
                "test." + width + "x" + height + ".mp4").toString();
        Log.i(TAG, "Output file is " + outputPath);


        // Create a MediaMuxer.  We can't add the video track and start() the muxer here,
        // because our MediaFormat doesn't have the Magic Goodies.  These can only be
        // obtained from the encoder after it has started processing data.
        //
        // We're not actually interested in multiplexing audio.  We just want to convert
        // the raw H.264 elementary stream we get from MediaCodec into a .mp4 file.
        try {
            mMuxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (IOException ioe) {
            throw new RuntimeException("MediaMuxer creation failed", ioe);
        }

        mTrackIndex = -1;
        mMuxerStarted = false;
    }

    /**
     * Releases encoder resources.
     */
    private void releaseEncoder() {
        if (DEBUG) Log.d(TAG, "releasing encoder objects");
        if (mEncoder != null) {
            mEncoder.stop();
            mEncoder.release();
            mEncoder = null;
        }
        if (mInputSurface != null) {
            mInputSurface.release();
            mInputSurface = null;
        }
        if (mMuxer != null) {
            try{
                mMuxer.stop();
                mMuxer.release();
                mMuxer = null;
            }catch (Exception e){
                Log.e(TAG, "You started a Muxer but haven't fed any data into it");
                e.printStackTrace();
            }
        }
    }

    private static class CodecInputSurface {
        /**
         * Creates a CodecInputSurface from a Surface.
         */
        public CodecInputSurface(Surface surface) {

        }

        /**
         * Discards all resources held by this class, notably the EGL context.  Also releases the
         * Surface that was passed to our constructor.
         */
        public void release() {

        }
    }
}
