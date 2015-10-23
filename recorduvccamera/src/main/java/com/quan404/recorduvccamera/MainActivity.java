package com.quan404.recorduvccamera;

import android.app.Activity;
import android.graphics.SurfaceTexture;
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
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Environment;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class MainActivity extends Activity {
    private static final String TAG = "Record UVC Camera";
    private static final boolean DEBUG = true;

    private static final int FRAME_WIDTH = 640;             // 640px
    private static final int FRAME_HEIGHT = 480;            // 480px
    private static final int BITRATE = 6000000;            // 6000000 Mbps
    private static final long DURATION_SEC = 5;             // 8 seconds of video
    /**
     * Shader functions for frame editing
     */
    // Fragment shader that swaps color channels around.
    private static final String SWAPPED_FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "varying vec2 vTextureCoord;\n" +
                    "uniform samplerExternalOES sTexture;\n" +
                    "void main() {\n" +
                    "  gl_FragColor = texture2D(sTexture, vTextureCoord).gbra;\n" +
                    "}\n";
    /**
     * Encoder & Muxer
     */
    private CameraEncoder mCameraEncoder = new CameraEncoder();

    /**
     * Camera Stuff
     */
    private Camera mCamera;
    private SurfaceTextureManager mStManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);


        findViewById(R.id.record_uvc_camera).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Toast.makeText(getApplicationContext(), "Start recording ...", Toast.LENGTH_SHORT).show();
                Log.d(TAG, "Start recording ...");

                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        prepareCamera(FRAME_WIDTH, FRAME_HEIGHT);
                        mCameraEncoder.prepareEncoder(FRAME_WIDTH, FRAME_HEIGHT, BITRATE);
                        mCameraEncoder.mInputSurface.makeCurrent();
                        prepareSurfaceTexture();

                        mCamera.startPreview();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(getApplicationContext(), "Start recording", Toast.LENGTH_LONG).show();
                            }
                        });


                        long startWhen = System.nanoTime();
                        long desiredEnd = startWhen + DURATION_SEC * 1000000000L;
                        SurfaceTexture st = mStManager.getSurfaceTexture();
                        int frameCount = 0;

                        while (System.nanoTime() < desiredEnd) {
                            // Feed any pending encoder output into the muxer.
                            mCameraEncoder.drainEncoder(false);

                            // Switch up the colors every 15 frames.  Besides demonstrating the use of
                            // fragment shaders for video editing, this provides a visual indication of
                            // the frame rate: if the camera is capturing at 15fps, the colors will change
                            // once per second.
                            if ((frameCount % 15) == 0) {
                                Log.d(TAG, "((frameCount % 15) == 0): change color");

                                String fragmentShader = null;
                                if ((frameCount & 0x01) != 0) {
                                    fragmentShader = SWAPPED_FRAGMENT_SHADER;
                                }
                                mStManager.changeFragmentShader(fragmentShader);
                            }
                            frameCount++;

                            // Acquire a new frame of input, and render it to the Surface.  If we had a
                            // GLSurfaceView we could switch EGL contexts and call drawImage() a second
                            // time to render it on screen.  The texture can be shared between contexts by
                            // passing the GLSurfaceView's EGLContext as eglCreateContext()'s share_context
                            // argument.
                            mStManager.awaitNewImage();
                            mStManager.drawImage();

                            // Set the presentation time stamp from the SurfaceTexture's time stamp.  This
                            // will be used by MediaMuxer to set the PTS in the video.
                            if (DEBUG) {
                                Log.d(TAG, "present: " +
                                        ((st.getTimestamp() - startWhen) / 1000000.0) + "ms");
                            }
                            mCameraEncoder.mInputSurface.setPresentationTime(st.getTimestamp());

                            // Submit it to the encoder.  The eglSwapBuffers call will block if the input
                            // is full, which would be bad if it stayed full until we dequeued an output
                            // buffer (which we can't do, since we're stuck here).  So long as we fully drain
                            // the encoder before supplying additional input, the system guarantees that we
                            // can supply another frame without blocking.
                            if (DEBUG) Log.d(TAG, "sending frame to encoder");
                            mCameraEncoder.mInputSurface.swapBuffers();
                        }

                        // send end-of-stream to encoder, and drain remaining output
                        mCameraEncoder.drainEncoder(true);
                    }
                }).start();
            }
        });
    }

    @Override
    protected void onStop() {
        releaseCamera();
        mCameraEncoder.releaseEncoder();
        releaseSurfaceTexture();

        super.onStop();
    }

    /**
     * Camera functions
     */
    private void prepareCamera(int expectedWidth, int expectedHeight) {
        if (DEBUG) {
            Log.d(TAG, "prepareCamera");
        }

        try {
            // Open front camera
            Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
            for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
                Camera.getCameraInfo(i, cameraInfo);
                if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    mCamera = Camera.open(i);
                }
            }

            // Else open rear camera
            if (mCamera == null) {
                mCamera = Camera.open();
            }

            // Else throw an Error
            if (mCamera == null) {
                throw new RuntimeException("Can not open any camera");
            }

            // Select a preview size that match the expectedWidth & expectedHeight
            Camera.Parameters params = mCamera.getParameters();
            choosePreviewSize(params, expectedWidth, expectedHeight);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void choosePreviewSize(Camera.Parameters params, int width, int height) {
        Camera.Size ppsfv = params.getPreferredPreviewSizeForVideo();

        if (DEBUG) {
            Log.d(TAG, "choosePreviewSize: PreferredPreviewSize width = " + ppsfv.width + " height = " + ppsfv.height);
        }

        for (Camera.Size size : params.getSupportedPreviewSizes()) {
            if (size.width == width && size.height == height) {
                if (DEBUG)
                    Log.d(TAG, "choosePreviewSize: setPreviewSize to " + width + " " + height);
                params.setPreviewSize(width, height);
                return;
            }
        }

        Log.d(TAG, "choosePreviewSize: Unable to set preview size to " + width + " " + height + " revert to " + ppsfv.width + " " + ppsfv.height);
        if (ppsfv != null) {
            params.setPreviewSize(ppsfv.width, ppsfv.height);
        }
    }

    private void releaseCamera() {
        if (DEBUG) {
            Log.d(TAG, "releaseCamera");
        }
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }
    }



    /**
     * Configures SurfaceTexture for camera preview.  Initializes mStManager, and sets the
     * associated SurfaceTexture as the Camera's "preview texture".
     * <p/>
     * Configure the EGL surface that will be used for output before calling here.
     */
    private void prepareSurfaceTexture() {
        mStManager = new SurfaceTextureManager();
        SurfaceTexture st = mStManager.getSurfaceTexture();
        try {
            mCamera.setPreviewTexture(st);
        } catch (IOException ioe) {
            throw new RuntimeException("setPreviewTexture failed", ioe);
        }
    }

    /**
     * SurfaceTexture functions
     */

    /**
     * Releases the SurfaceTexture.
     */
    private void releaseSurfaceTexture() {
        if (mStManager != null) {
            mStManager.release();
            mStManager = null;
        }
    }


}
