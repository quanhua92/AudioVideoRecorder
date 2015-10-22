package com.quan404.recordvideotomp4;

import android.app.Activity;
import android.content.res.Resources;
import android.hardware.Camera;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
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

    /**
     * Holds state associated with a Surface used for MediaCodec encoder input.
     * <p>
     * The constructor takes a Surface obtained from MediaCodec.createInputSurface(), and uses
     * that to create an EGL window surface. Calls to eglSwapBuffers() cause a frame of data to
     * be sent to the video encoder.
     * <p>
     * This object owns the Surface -- releasing this will release the Surface too.
     */
    private static class CodecInputSurface {

        private static final int EGL_RECORDABLE_ANDROID = 0x3142;

        private EGLDisplay mEGLDisplay = EGL14.EGL_NO_DISPLAY;
        private EGLContext mEGLContext = EGL14.EGL_NO_CONTEXT;
        private EGLSurface mEGLSurface = EGL14.EGL_NO_SURFACE;

        private Surface mSurface;

        /**
         * Creates a CodecInputSurface from a Surface.
         */
        public CodecInputSurface(Surface surface) {
            if (surface == null) {
                throw new NullPointerException();
            }
            mSurface = surface;

            eglSetup();
        }

        /**
         * Prepares EGL.  We want a GLES 2.0 context and a surface that supports recording.
         */
        private void eglSetup() {
            mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (mEGLDisplay == EGL14.EGL_NO_DISPLAY) {
                throw new RuntimeException("unable to get EGL14 display");
            }
            int[] version = new int[2];
            if (!EGL14.eglInitialize(mEGLDisplay, version, 0, version, 1)) {
                throw new RuntimeException("unable to initialize EGL14");
            }

            // Configure EGL for recording and OpenGL ES 2.0.
            int[] attribList = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL_RECORDABLE_ANDROID, 1,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            EGL14.eglChooseConfig(mEGLDisplay, attribList, 0, configs, 0, configs.length,
                    numConfigs, 0);
            checkEglError("eglCreateContext RGB888+recordable ES2");

            // Configure context for OpenGL ES 2.0.
            int[] attrib_list = {
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL14.EGL_NONE
            };
            mEGLContext = EGL14.eglCreateContext(mEGLDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
                    attrib_list, 0);
            checkEglError("eglCreateContext");

            // Create a window surface, and attach it to the Surface we received.
            int[] surfaceAttribs = {
                    EGL14.EGL_NONE
            };
            mEGLSurface = EGL14.eglCreateWindowSurface(mEGLDisplay, configs[0], mSurface,
                    surfaceAttribs, 0);
            checkEglError("eglCreateWindowSurface");
        }

        /**
         * Discards all resources held by this class, notably the EGL context.  Also releases the
         * Surface that was passed to our constructor.
         */
        public void release() {
            if (mEGLDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(mEGLDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT);
                EGL14.eglDestroySurface(mEGLDisplay, mEGLSurface);
                EGL14.eglDestroyContext(mEGLDisplay, mEGLContext);
                EGL14.eglReleaseThread();
                EGL14.eglTerminate(mEGLDisplay);
            }
            mSurface.release();

            mEGLDisplay = EGL14.EGL_NO_DISPLAY;
            mEGLContext = EGL14.EGL_NO_CONTEXT;
            mEGLSurface = EGL14.EGL_NO_SURFACE;

            mSurface = null;
        }
        /**
         * Makes our EGL context and surface current.
         */
        public void makeCurrent() {
            EGL14.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, mEGLContext);
            checkEglError("eglMakeCurrent");
        }

        /**
         * Calls eglSwapBuffers.  Use this to "publish" the current frame.
         */
        public boolean swapBuffers() {
            boolean result = EGL14.eglSwapBuffers(mEGLDisplay, mEGLSurface);
            checkEglError("eglSwapBuffers");
            return result;
        }

        /**
         * Sends the presentation time stamp to EGL.  Time is expressed in nanoseconds.
         */
        public void setPresentationTime(long nsecs) {
            EGLExt.eglPresentationTimeANDROID(mEGLDisplay, mEGLSurface, nsecs);
            checkEglError("eglPresentationTimeANDROID");
        }
        /**
         * Checks for EGL errors.  Throws an exception if one is found.
         */
        private void checkEglError(String msg) {
            int error;
            if ((error = EGL14.eglGetError()) != EGL14.EGL_SUCCESS) {
                throw new RuntimeException(msg + ": EGL error: 0x" + Integer.toHexString(error));
            }
        }

    }
}
